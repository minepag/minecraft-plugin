package com.minepag.plugin.delivery;

import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.ResultStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registro local das execuções, com semântica at-most-once: a linha {@code started} é gravada (e sincronizada em
 * disco) antes do dispatch; uma linha que continua {@code started} depois de um crash vira {@code unknown} e nunca é
 * executada de novo.
 */
public interface Journal extends AutoCloseable {
    String ORPHAN_OUTPUT =
            "Execução interrompida: o servidor parou durante o comando. Confira manualmente se a entrega aconteceu.";

    record PendingAck(String commandId, ResultStatus status, String output, Instant finishedAt) {}

    record Stats(int awaitingAck, int started) {}

    /** Reserva a execução. Retorna {@code false} se o comando já passou por aqui (nunca execute nesse caso). */
    boolean tryStart(String commandId, int attempt, Instant now);

    void finish(String commandId, ResultStatus status, String output, Instant now);

    /** Converte execuções interrompidas em {@code unknown}. Chame só na inicialização, antes de qualquer dispatch. */
    int markOrphansAsUnknown(Instant now);

    Set<String> knownIds(Collection<String> commandIds);

    List<PendingAck> pendingAcks(int limit);

    void markAcked(Collection<String> commandIds, Instant now);

    int pruneAckedBefore(Instant cutoff);

    Stats stats();

    @Override
    void close();

    static Journal open(Path file) {
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS executions (
                          command_id TEXT PRIMARY KEY,
                          status TEXT NOT NULL CHECK (status IN ('started', 'succeeded', 'failed', 'unknown')),
                          attempt INTEGER NOT NULL,
                          output TEXT,
                          started_at INTEGER NOT NULL,
                          finished_at INTEGER,
                          acked_at INTEGER
                        )""");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS executions_pending_ack_idx
                          ON executions (finished_at) WHERE acked_at IS NULL AND status <> 'started'""");
            }
            return over(connection);
        } catch (ClassNotFoundException missingDriver) {
            throw new IllegalStateException("Driver SQLite (org.xerial:sqlite-jdbc) não encontrado.", missingDriver);
        } catch (Exception failure) {
            throw new IllegalStateException("Não foi possível abrir o journal em " + file + ": " + failure.getMessage(), failure);
        }
    }

    private static Journal over(Connection connection) {
        Object lock = new Object();

        return new Journal() {
            @Override
            public boolean tryStart(String commandId, int attempt, Instant now) {
                return update(
                                "INSERT OR IGNORE INTO executions (command_id, status, attempt, started_at) VALUES (?, 'started', ?, ?)",
                                commandId,
                                attempt,
                                now.toEpochMilli())
                        == 1;
            }

            @Override
            public void finish(String commandId, ResultStatus status, String output, Instant now) {
                update(
                        "UPDATE executions SET status = ?, output = ?, finished_at = ? WHERE command_id = ? AND status = 'started'",
                        status.wireName(),
                        AgentProtocol.truncate(output, AgentProtocol.MAX_ACK_OUTPUT_LENGTH),
                        now.toEpochMilli(),
                        commandId);
            }

            @Override
            public int markOrphansAsUnknown(Instant now) {
                return update(
                        "UPDATE executions SET status = 'unknown', output = ?, finished_at = ? WHERE status = 'started'",
                        ORPHAN_OUTPUT,
                        now.toEpochMilli());
            }

            @Override
            public Set<String> knownIds(Collection<String> commandIds) {
                if (commandIds.isEmpty()) return Set.of();
                String placeholders = commandIds.stream().map(id -> "?").collect(Collectors.joining(", "));
                Set<String> known = new HashSet<>();
                query(
                        "SELECT command_id FROM executions WHERE command_id IN (" + placeholders + ")",
                        commandIds.toArray(),
                        row -> known.add(row.getString(1)));
                return known;
            }

            @Override
            public List<PendingAck> pendingAcks(int limit) {
                List<PendingAck> pending = new ArrayList<>();
                query(
                        "SELECT command_id, status, output, finished_at FROM executions"
                                + " WHERE acked_at IS NULL AND status <> 'started' ORDER BY finished_at LIMIT ?",
                        new Object[] {limit},
                        row -> pending.add(new PendingAck(
                                row.getString(1),
                                ResultStatus.fromWireName(row.getString(2)),
                                row.getString(3),
                                Instant.ofEpochMilli(row.getLong(4)))));
                return pending;
            }

            @Override
            public void markAcked(Collection<String> commandIds, Instant now) {
                for (String commandId : commandIds) {
                    update(
                            "UPDATE executions SET acked_at = ? WHERE command_id = ? AND acked_at IS NULL AND status <> 'started'",
                            now.toEpochMilli(),
                            commandId);
                }
            }

            @Override
            public int pruneAckedBefore(Instant cutoff) {
                return update("DELETE FROM executions WHERE acked_at IS NOT NULL AND acked_at < ?", cutoff.toEpochMilli());
            }

            @Override
            public Stats stats() {
                int[] counts = new int[2];
                query(
                        "SELECT"
                                + " COALESCE(SUM(CASE WHEN acked_at IS NULL AND status <> 'started' THEN 1 ELSE 0 END), 0),"
                                + " COALESCE(SUM(CASE WHEN status = 'started' THEN 1 ELSE 0 END), 0)"
                                + " FROM executions",
                        new Object[0],
                        row -> {
                            counts[0] = row.getInt(1);
                            counts[1] = row.getInt(2);
                        });
                return new Stats(counts[0], counts[1]);
            }

            @Override
            public void close() {
                synchronized (lock) {
                    try {
                        connection.close();
                    } catch (SQLException ignored) {
                        // Encerramento do servidor: nada a fazer.
                    }
                }
            }

            private int update(String sql, Object... parameters) {
                synchronized (lock) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        bind(statement, parameters);
                        return statement.executeUpdate();
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Falha no journal: " + failure.getMessage(), failure);
                    }
                }
            }

            private void query(String sql, Object[] parameters, RowConsumer consumer) {
                synchronized (lock) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        bind(statement, parameters);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) consumer.accept(rows);
                        }
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Falha no journal: " + failure.getMessage(), failure);
                    }
                }
            }
        };
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    @FunctionalInterface
    interface RowConsumer {
        void accept(ResultSet row) throws SQLException;
    }
}
