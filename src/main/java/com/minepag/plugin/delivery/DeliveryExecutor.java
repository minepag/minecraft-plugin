package com.minepag.plugin.delivery;

import com.minepag.plugin.config.Messages;
import com.minepag.plugin.platform.Chat;
import com.minepag.plugin.platform.SchedulerAdapter;
import com.minepag.plugin.protocol.AgentProtocol.AckResult;
import com.minepag.plugin.protocol.AgentProtocol.AgentCommand;
import com.minepag.plugin.protocol.AgentProtocol.RejectedCommand;
import com.minepag.plugin.protocol.AgentProtocol.ResultStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Executa os comandos recebidos no pull. Fluxo por comando: política local → jogador online e espaço no inventário
 * (thread do jogador) → {@code started} no journal (async) → dispatch no console (thread global) → resultado no
 * journal (async). Comandos do mesmo jogador rodam em ordem; jogadores diferentes rodam em paralelo.
 */
public interface DeliveryExecutor {
    CompletableFuture<Void> execute(List<AgentCommand> commands, List<RejectedCommand> rejected);

    /**
     * Resultados {@code deferred} (jogador saiu ou inventário cheio) acumulados desde a última chamada. Não passam pelo
     * journal: o comando não foi executado e pode voltar num próximo pull.
     */
    List<AckResult> drainDeferred();

    Duration INVENTORY_NOTICE_INTERVAL = Duration.ofMinutes(1);
    String CONSOLE_GROUP = "console";

    enum Readiness { READY, OFFLINE, INVENTORY_FULL }

    record DispatchResult(ResultStatus status, String output) {}

    static DeliveryExecutor create(
            SchedulerAdapter scheduler,
            Journal journal,
            CommandPolicy policy,
            Chat chat,
            Messages messages,
            Clock clock,
            Logger logger,
            boolean debug) {
        Map<UUID, Instant> lastInventoryNotice = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<AckResult> deferred = new ConcurrentLinkedQueue<>();
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);

        return new DeliveryExecutor() {
            @Override
            public List<AckResult> drainDeferred() {
                List<AckResult> drained = new ArrayList<>();
                for (AckResult result = deferred.poll(); result != null; result = deferred.poll()) drained.add(result);
                return drained;
            }

            @Override
            public CompletableFuture<Void> execute(List<AgentCommand> commands, List<RejectedCommand> rejected) {
                return CompletableFuture.runAsync(() -> rejected.forEach(this::recordRejected), scheduler.asyncExecutor())
                        .thenCompose(ignored -> {
                            Set<String> known = journal.knownIds(commands.stream().map(AgentCommand::id).toList());
                            Map<String, List<AgentCommand>> byPlayer = new LinkedHashMap<>();
                            commands.stream()
                                    .filter(command -> !known.contains(command.id()))
                                    .forEach(command -> byPlayer
                                            .computeIfAbsent(Objects.requireNonNullElse(command.playerUuid(), CONSOLE_GROUP), key -> new ArrayList<>())
                                            .add(command));
                            return CompletableFuture.allOf(byPlayer.values().stream()
                                    .map(this::runInOrder)
                                    .toArray(CompletableFuture[]::new));
                        });
            }

            private CompletableFuture<Void> runInOrder(List<AgentCommand> playerCommands) {
                CompletableFuture<Void> chain = done;
                for (AgentCommand command : playerCommands) {
                    chain = chain.thenCompose(ignored -> process(command)).exceptionally(failure -> {
                        logger.log(Level.SEVERE, "Falha ao processar o comando " + command.id() + ".", failure);
                        return null;
                    });
                }
                return chain;
            }

            private CompletableFuture<Void> process(AgentCommand command) {
                var rejection = policy.rejectionReason(command);
                if (rejection.isPresent()) {
                    logger.warning("Comando " + command.id() + " recusado: " + rejection.get());
                    recordRejected(new RejectedCommand(command.id(), command.attempt(), rejection.get()));
                    return done;
                }
                if (isLeaseExpired(command)) {
                    debugLog("Reserva do comando " + command.id() + " expirou; ele volta na próxima busca.");
                    return done;
                }
                if (!command.requireOnline()) return dispatch(command);
                return readiness(command).thenCompose(readiness -> switch (readiness) {
                    case READY -> dispatch(command);
                    case OFFLINE -> {
                        debugLog("Jogador do comando " + command.id() + " está offline; aguardando a entrada.");
                        defer(command, "O jogador saiu do servidor antes da entrega.");
                        yield done;
                    }
                    case INVENTORY_FULL -> {
                        debugLog("Inventário cheio para o comando " + command.id() + ".");
                        defer(command, "O jogador está sem espaço no inventário.");
                        yield done;
                    }
                });
            }

            private CompletableFuture<Readiness> readiness(AgentCommand command) {
                CompletableFuture<Readiness> result = new CompletableFuture<>();
                Player player = Bukkit.getPlayer(UUID.fromString(command.playerUuid()));
                if (player == null) return CompletableFuture.completedFuture(Readiness.OFFLINE);
                scheduler.runForPlayer(
                        player,
                        () -> {
                            if (!player.isOnline()) {
                                result.complete(Readiness.OFFLINE);
                                return;
                            }
                            if (freeSlots(player) >= command.requiredSlots()) {
                                result.complete(Readiness.READY);
                                return;
                            }
                            notifyInventoryFull(player, command.requiredSlots());
                            result.complete(Readiness.INVENTORY_FULL);
                        },
                        () -> result.complete(Readiness.OFFLINE));
                return result;
            }

            private CompletableFuture<Void> dispatch(AgentCommand command) {
                return CompletableFuture.supplyAsync(
                                () -> journal.tryStart(command.id(), command.attempt(), clock.instant()),
                                scheduler.asyncExecutor())
                        .thenCompose(started -> {
                            if (!started) return done;
                            return CompletableFuture.supplyAsync(() -> runOnConsole(command.command()), scheduler.globalExecutor())
                                    .thenAcceptAsync(
                                            result -> {
                                                journal.finish(command.id(), result.status(), result.output(), clock.instant());
                                                debugLog("Comando " + command.id() + " executado: " + result.status().wireName() + ".");
                                            },
                                            scheduler.asyncExecutor());
                        });
            }

            private DispatchResult runOnConsole(String commandLine) {
                try {
                    boolean handled = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
                    return handled
                            ? new DispatchResult(ResultStatus.SUCCEEDED, null)
                            : new DispatchResult(ResultStatus.FAILED, "O servidor não reconheceu o comando ou ele retornou falha.");
                } catch (RuntimeException failure) {
                    return new DispatchResult(ResultStatus.FAILED, "Erro ao executar o comando: " + failure);
                }
            }

            private void recordRejected(RejectedCommand command) {
                Instant now = clock.instant();
                if (journal.tryStart(command.id(), command.attempt(), now)) {
                    journal.finish(command.id(), ResultStatus.FAILED, "Recusado pelo plugin: " + command.reason(), now);
                }
            }

            private void defer(AgentCommand command, String reason) {
                deferred.add(new AckResult(command.id(), ResultStatus.DEFERRED.wireName(), reason, clock.instant().toString()));
            }

            private boolean isLeaseExpired(AgentCommand command) {
                return OffsetDateTime.parse(command.leaseExpiresAt()).toInstant().isBefore(clock.instant());
            }

            private int freeSlots(Player player) {
                int free = 0;
                for (ItemStack item : player.getInventory().getStorageContents()) {
                    if (item == null || item.getType().isAir()) free++;
                }
                return free;
            }

            private void notifyInventoryFull(Player player, int requiredSlots) {
                Instant now = clock.instant();
                Instant last = lastInventoryNotice.get(player.getUniqueId());
                if (last != null && last.plus(INVENTORY_NOTICE_INTERVAL).isAfter(now)) return;
                lastInventoryNotice.put(player.getUniqueId(), now);
                chat.send(player, messages.text("prefix") + messages.text("inventory-full"), Map.of("slots", String.valueOf(requiredSlots)));
            }

            private void debugLog(String message) {
                if (debug) logger.info("[debug] " + message);
            }
        };
    }
}
