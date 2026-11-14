package com.minepag.plugin.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minepag.plugin.protocol.AgentProtocol.ResultStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");

    @TempDir
    Path directory;

    @Test
    void startsEachCommandAtMostOnce() {
        try (Journal journal = Journal.open(directory.resolve("data/journal.db"))) {
            assertTrue(journal.tryStart("a", 1, NOW));
            assertFalse(journal.tryStart("a", 2, NOW), "segunda tentativa nunca pode executar");

            journal.finish("a", ResultStatus.SUCCEEDED, null, NOW);
            assertFalse(journal.tryStart("a", 3, NOW), "comando concluído também não volta");
        }
    }

    @Test
    void reportsFinishedCommandsUntilAcked() {
        try (Journal journal = Journal.open(directory.resolve("journal.db"))) {
            journal.tryStart("a", 1, NOW);
            journal.tryStart("b", 1, NOW);
            journal.tryStart("c", 1, NOW);
            journal.finish("b", ResultStatus.FAILED, "x".repeat(5000), NOW.plusSeconds(1));
            journal.finish("a", ResultStatus.SUCCEEDED, null, NOW.plusSeconds(2));

            List<Journal.PendingAck> pending = journal.pendingAcks(100);
            assertEquals(List.of("b", "a"), pending.stream().map(Journal.PendingAck::commandId).toList());
            assertEquals(1000, pending.getFirst().output().length(), "saída truncada no limite do contrato");
            assertEquals(new Journal.Stats(2, 1), journal.stats());

            journal.markAcked(Set.of("a", "b", "c"), NOW.plusSeconds(3));
            assertTrue(journal.pendingAcks(100).isEmpty());
            assertEquals(new Journal.Stats(0, 1), journal.stats(), "ack não encerra comando ainda em execução");
        }
    }

    @Test
    void orphanedStartsBecomeUnknownAndAreNeverRetried() {
        Path file = directory.resolve("journal.db");
        try (Journal beforeCrash = Journal.open(file)) {
            beforeCrash.tryStart("interrompido", 1, NOW);
        }

        try (Journal afterRestart = Journal.open(file)) {
            assertEquals(1, afterRestart.markOrphansAsUnknown(NOW.plusSeconds(60)));
            assertFalse(afterRestart.tryStart("interrompido", 2, NOW.plusSeconds(61)));

            Journal.PendingAck pending = afterRestart.pendingAcks(10).getFirst();
            assertEquals(ResultStatus.UNKNOWN, pending.status());
            assertEquals(Journal.ORPHAN_OUTPUT, pending.output());

            afterRestart.finish("interrompido", ResultStatus.SUCCEEDED, null, NOW.plusSeconds(62));
            assertEquals(ResultStatus.UNKNOWN, afterRestart.pendingAcks(10).getFirst().status(), "unknown é definitivo");
        }
    }

    @Test
    void findsKnownIdsAndPrunesOldAckedRows() {
        try (Journal journal = Journal.open(directory.resolve("journal.db"))) {
            journal.tryStart("antigo", 1, NOW);
            journal.finish("antigo", ResultStatus.SUCCEEDED, null, NOW);
            journal.markAcked(List.of("antigo"), NOW);
            journal.tryStart("recente", 1, NOW);

            assertEquals(Set.of("antigo", "recente"), journal.knownIds(List.of("antigo", "recente", "novo")));
            assertEquals(Set.of(), journal.knownIds(List.of()));

            assertEquals(1, journal.pruneAckedBefore(NOW.plus(Duration.ofDays(31))));
            assertEquals(Set.of("recente"), journal.knownIds(List.of("antigo", "recente")));
        }
    }
}
