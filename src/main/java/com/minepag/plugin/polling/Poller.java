package com.minepag.plugin.polling;

import com.minepag.plugin.delivery.Announcer;
import com.minepag.plugin.delivery.DeliveryExecutor;
import com.minepag.plugin.delivery.Journal;
import com.minepag.plugin.platform.SchedulerAdapter;
import com.minepag.plugin.platform.SchedulerAdapter.TaskHandle;
import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.AckRequest;
import com.minepag.plugin.protocol.AgentProtocol.AckResult;
import com.minepag.plugin.protocol.AgentProtocol.HeartbeatRequest;
import com.minepag.plugin.protocol.AgentProtocol.OnlinePlayer;
import com.minepag.plugin.protocol.AgentProtocol.PullRequest;
import com.minepag.plugin.protocol.ApiClient;
import com.minepag.plugin.protocol.ApiOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Laço do agente: pull periódico (intervalo do servidor, backoff com jitter, Retry-After), pull imediato quando um
 * jogador entra, ack em lote com retry e heartbeat. Para de vez em 401/403 (token revogado) e 426 (plugin
 * desatualizado), até um novo pareamento ou um forcecheck. Com o servidor pausado no painel (423
 * {@code server_paused}), continua consultando devagar e volta sozinho quando o servidor for reativado.
 */
public interface Poller {
    void start();

    void stop();

    /** Pull imediato respeitando o cooldown. Retorna os segundos restantes de cooldown (0 se iniciou). */
    long forceCheck();

    void playerJoined(UUID playerUuid);

    Status status();

    record ServerInfo(String pluginVersion, String platform, String mcVersion, String javaVersion) {}

    record ServerSnapshot(List<OnlinePlayer> players, int onlineCount, int maxPlayers, Optional<Double> tps) {}

    record Status(
            boolean running,
            boolean pausedInPanel,
            Optional<String> haltReason,
            Optional<Instant> lastPollAt,
            Optional<Instant> nextPollAt,
            int consecutiveFailures,
            Optional<String> lastError) {}

    Duration FORCE_CHECK_COOLDOWN = Duration.ofSeconds(10);
    long PAUSED_POLL_SECONDS = AgentProtocol.MAX_POLL_SECONDS;
    Duration JOIN_DEBOUNCE = Duration.ofSeconds(2);
    Duration JOURNAL_RETENTION = Duration.ofDays(30);
    long HAS_MORE_DELAY_SECONDS = 1;
    int PULL_LIMIT = 50;

    static Poller create(
            ApiClient api,
            Journal journal,
            DeliveryExecutor executor,
            Announcer announcer,
            SchedulerAdapter scheduler,
            ServerInfo serverInfo,
            Supplier<ServerSnapshot> snapshots,
            long configuredPollSeconds,
            boolean checkOnJoin,
            Clock clock,
            Logger logger,
            boolean debug) {
        return new Poller() {
            private volatile boolean running;
            private volatile String haltReason;
            private volatile Instant lastPollAt;
            private volatile Instant nextPollAt;
            private volatile String lastError;
            private volatile int pullFailures;
            private volatile int ackFailures;
            private volatile long serverPollSeconds = AgentProtocol.DEFAULT_POLL_SECONDS;
            private volatile Instant lastForceCheckAt = Instant.EPOCH;
            private volatile boolean outdatedWarningLogged;
            private volatile boolean pausedInPanel;

            private final AtomicBoolean pulling = new AtomicBoolean();
            private final AtomicBoolean acking = new AtomicBoolean();
            private final AtomicBoolean joinPollScheduled = new AtomicBoolean();
            private final Set<String> pendingJoinUuids = new LinkedHashSet<>();
            private final AtomicReference<TaskHandle> mainTask = new AtomicReference<>(SchedulerAdapter.NOOP_HANDLE);
            private final AtomicReference<TaskHandle> heartbeatTask = new AtomicReference<>(SchedulerAdapter.NOOP_HANDLE);
            private final AtomicReference<TaskHandle> ackRetryTask = new AtomicReference<>(SchedulerAdapter.NOOP_HANDLE);

            @Override
            public void start() {
                running = true;
                haltReason = null;
                pullFailures = 0;
                scheduleMain(0);
                scheduleHeartbeat(0);
                scheduler.runAsync(this::flushAcks);
            }

            @Override
            public void stop() {
                running = false;
                mainTask.getAndSet(SchedulerAdapter.NOOP_HANDLE).cancel();
                heartbeatTask.getAndSet(SchedulerAdapter.NOOP_HANDLE).cancel();
                ackRetryTask.getAndSet(SchedulerAdapter.NOOP_HANDLE).cancel();
                nextPollAt = null;
            }

            @Override
            public long forceCheck() {
                Instant now = clock.instant();
                Duration sinceLast = Duration.between(lastForceCheckAt, now);
                if (sinceLast.compareTo(FORCE_CHECK_COOLDOWN) < 0) {
                    return Math.max(1, FORCE_CHECK_COOLDOWN.minus(sinceLast).toSeconds());
                }
                lastForceCheckAt = now;
                if (running) scheduleMain(0);
                else start();
                return 0;
            }

            @Override
            public void playerJoined(UUID playerUuid) {
                if (!running || !checkOnJoin) return;
                synchronized (pendingJoinUuids) {
                    pendingJoinUuids.add(playerUuid.toString());
                }
                if (joinPollScheduled.compareAndSet(false, true)) {
                    scheduler.runAsyncLater(this::runJoinPoll, JOIN_DEBOUNCE.toMillis());
                }
            }

            @Override
            public Status status() {
                return new Status(
                        running,
                        pausedInPanel,
                        Optional.ofNullable(haltReason),
                        Optional.ofNullable(lastPollAt),
                        Optional.ofNullable(nextPollAt),
                        pullFailures,
                        Optional.ofNullable(lastError));
            }

            private void scheduleMain(long delaySeconds) {
                if (!running) return;
                nextPollAt = clock.instant().plusSeconds(delaySeconds);
                TaskHandle next = scheduler.runAsyncLater(() -> pull(List.of(), true), delaySeconds * 1000);
                mainTask.getAndSet(next).cancel();
            }

            private void scheduleHeartbeat(long delaySeconds) {
                if (!running) return;
                TaskHandle next = scheduler.runAsyncLater(this::sendHeartbeat, delaySeconds * 1000);
                heartbeatTask.getAndSet(next).cancel();
            }

            private void runJoinPoll() {
                joinPollScheduled.set(false);
                List<String> uuids;
                synchronized (pendingJoinUuids) {
                    uuids = pendingJoinUuids.stream().limit(AgentProtocol.MAX_ONLY_PLAYER_UUIDS).toList();
                    uuids.forEach(pendingJoinUuids::remove);
                }
                if (!uuids.isEmpty()) pull(uuids, false);
            }

            private void requeueJoinPoll(List<String> uuids) {
                synchronized (pendingJoinUuids) {
                    pendingJoinUuids.addAll(uuids);
                }
                if (joinPollScheduled.compareAndSet(false, true)) {
                    scheduler.runAsyncLater(this::runJoinPoll, JOIN_DEBOUNCE.toMillis());
                }
            }

            private void pull(List<String> onlyPlayerUuids, boolean mainLoop) {
                if (!running) return;
                if (!pulling.compareAndSet(false, true)) {
                    if (mainLoop) scheduleMain(JOIN_DEBOUNCE.toSeconds());
                    else requeueJoinPoll(onlyPlayerUuids);
                    return;
                }
                long nextDelaySeconds;
                try {
                    nextDelaySeconds = pullOnce(onlyPlayerUuids, mainLoop);
                } catch (RuntimeException failure) {
                    pullFailures++;
                    lastError = failure.getMessage();
                    logger.log(Level.WARNING, "Falha ao buscar entregas na MinePag.", failure);
                    nextDelaySeconds = PollTiming.backoffSeconds(pullFailures, ThreadLocalRandom.current()::nextDouble);
                } finally {
                    pulling.set(false);
                }
                if (mainLoop && nextDelaySeconds >= 0) scheduleMain(nextDelaySeconds);
            }

            /** Retorna o atraso até o próximo pull, ou −1 se o agente parou. */
            private long pullOnce(List<String> onlyPlayerUuids, boolean mainLoop) {
                ServerSnapshot snapshot = snapshots.get();
                PullRequest request = new PullRequest(
                        serverInfo.pluginVersion(),
                        serverInfo.platform(),
                        serverInfo.mcVersion(),
                        snapshot.players(),
                        PULL_LIMIT,
                        onlyPlayerUuids.isEmpty() ? null : onlyPlayerUuids);
                ApiOutcome<AgentProtocol.PullBatch> outcome = api.pull(request);
                lastPollAt = clock.instant();

                return switch (outcome) {
                    case ApiOutcome.Ok<AgentProtocol.PullBatch> ok -> {
                        var batch = ok.value();
                        resumeIfPaused();
                        pullFailures = 0;
                        lastError = null;
                        serverPollSeconds = batch.nextPollSeconds();
                        announcer.enqueue(batch.announcements());
                        if (!batch.commands().isEmpty() || !batch.rejected().isEmpty()) {
                            debugLog("Pull trouxe " + batch.commands().size() + " comando(s) e "
                                    + batch.rejected().size() + " recusado(s).");
                            executor.execute(batch.commands(), batch.rejected()).whenComplete((ignored, failure) -> {
                                if (failure != null) logger.log(Level.SEVERE, "Falha ao executar entregas.", failure);
                                flushAcks();
                                sendDeferredAcks();
                            });
                        }
                        if (batch.hasMore() && !mainLoop) scheduleMain(HAS_MORE_DELAY_SECONDS);
                        yield batch.hasMore()
                                ? HAS_MORE_DELAY_SECONDS
                                : PollTiming.effectivePollSeconds(serverPollSeconds, configuredPollSeconds);
                    }
                    case ApiOutcome.HttpFailure<AgentProtocol.PullBatch> failure -> delayAfterHttpFailure(failure, ++pullFailures);
                    case ApiOutcome.NetworkFailure<AgentProtocol.PullBatch> failure -> {
                        pullFailures++;
                        lastError = failure.message();
                        long delay = PollTiming.backoffSeconds(pullFailures, ThreadLocalRandom.current()::nextDouble);
                        logger.warning("MinePag indisponível (" + failure.message() + "). Nova tentativa em " + delay + " s.");
                        yield delay;
                    }
                };
            }

            private long delayAfterHttpFailure(ApiOutcome.HttpFailure<?> failure, int consecutiveFailures) {
                if (failure.isServerPaused()) {
                    if (!pausedInPanel) {
                        logger.warning("Este servidor está pausado no painel da MinePag. As entregas voltam sozinhas "
                                + "quando ele for reativado em Entregas › Minecraft.");
                    }
                    pausedInPanel = true;
                    lastError = null;
                    return Math.max(PAUSED_POLL_SECONDS, failure.retryAfterSeconds().orElse(0));
                }
                lastError = failure.code() + ": " + failure.message();
                if (failure.isAuthFailure()) {
                    halt("token inválido ou revogado. Gere um novo código no painel e use /minepag pair <código>.");
                    return -1;
                }
                if (failure.isPluginOutdated()) {
                    halt("plugin desatualizado. Baixe a versão mais recente no painel da MinePag.");
                    return -1;
                }
                long delay = PollTiming.delayAfterFailure(
                        consecutiveFailures, failure.retryAfterSeconds(), ThreadLocalRandom.current()::nextDouble);
                logger.warning("A MinePag respondeu HTTP " + failure.status() + " (" + failure.code() + "). Nova tentativa em "
                        + delay + " s.");
                return delay;
            }

            private void resumeIfPaused() {
                if (!pausedInPanel) return;
                pausedInPanel = false;
                logger.info("Servidor reativado no painel da MinePag. As entregas voltaram.");
                scheduler.runAsync(this::flushAcks);
            }

            private synchronized void halt(String reason) {
                if (!running) return;
                haltReason = reason;
                logger.severe("Entregas pausadas: " + reason);
                stop();
            }

            private void flushAcks() {
                if (!acking.compareAndSet(false, true)) return;
                try {
                    while (running) {
                        List<Journal.PendingAck> pending = journal.pendingAcks(AgentProtocol.MAX_ACK_BATCH);
                        if (pending.isEmpty()) {
                            ackFailures = 0;
                            return;
                        }
                        List<AckResult> results = pending.stream()
                                .map(ack -> new AckResult(
                                        ack.commandId(),
                                        ack.status().wireName(),
                                        ack.output(),
                                        ack.finishedAt().toString()))
                                .toList();
                        ApiOutcome<AgentProtocol.AckResponse> outcome = api.ack(new AckRequest(results));
                        if (outcome instanceof ApiOutcome.Ok<AgentProtocol.AckResponse> ok) {
                            Set<String> settled = new HashSet<>(ok.value().accepted());
                            settled.addAll(ok.value().ignored());
                            journal.markAcked(settled, clock.instant());
                            if (settled.size() < pending.size()) {
                                ackFailures++;
                                scheduleAckRetry();
                                return;
                            }
                            ackFailures = 0;
                            continue;
                        }
                        ackFailures++;
                        if (outcome instanceof ApiOutcome.HttpFailure<AgentProtocol.AckResponse> failure) {
                            long delay = delayAfterHttpFailure(failure, ackFailures);
                            if (delay >= 0) scheduleAckRetry(delay);
                        } else if (outcome instanceof ApiOutcome.NetworkFailure<AgentProtocol.AckResponse> failure) {
                            lastError = failure.message();
                            scheduleAckRetry();
                        }
                        return;
                    }
                } catch (RuntimeException failure) {
                    logger.log(Level.WARNING, "Falha ao confirmar entregas na MinePag.", failure);
                    ackFailures++;
                    scheduleAckRetry();
                } finally {
                    acking.set(false);
                }
            }

            /** Melhor esforço: se a confirmação se perder, a reserva vence na MinePag e o comando volta na fila. */
            private void sendDeferredAcks() {
                List<AckResult> deferred = executor.drainDeferred();
                if (deferred.isEmpty() || !running) return;
                try {
                    for (int start = 0; start < deferred.size(); start += AgentProtocol.MAX_ACK_BATCH) {
                        var batch = deferred.subList(start, Math.min(deferred.size(), start + AgentProtocol.MAX_ACK_BATCH));
                        if (!(api.ack(new AckRequest(batch)) instanceof ApiOutcome.Ok<AgentProtocol.AckResponse>)) {
                            debugLog("Não foi possível devolver " + batch.size() + " comando(s) adiados; a reserva vai expirar.");
                        }
                    }
                } catch (RuntimeException failure) {
                    logger.log(Level.WARNING, "Falha ao devolver comandos adiados à MinePag.", failure);
                }
            }

            private void scheduleAckRetry() {
                scheduleAckRetry(PollTiming.backoffSeconds(Math.max(1, ackFailures), ThreadLocalRandom.current()::nextDouble));
            }

            private void scheduleAckRetry(long delaySeconds) {
                if (!running) return;
                TaskHandle next = scheduler.runAsyncLater(this::flushAcks, delaySeconds * 1000);
                ackRetryTask.getAndSet(next).cancel();
            }

            private void sendHeartbeat() {
                if (!running) return;
                try {
                    ServerSnapshot snapshot = snapshots.get();
                    var outcome = api.heartbeat(new HeartbeatRequest(
                            serverInfo.pluginVersion(),
                            serverInfo.platform(),
                            serverInfo.mcVersion(),
                            snapshot.onlineCount(),
                            snapshot.maxPlayers(),
                            snapshot.tps().orElse(null),
                            serverInfo.javaVersion()));
                    switch (outcome) {
                        case ApiOutcome.Ok<AgentProtocol.HeartbeatResponse> ok -> {
                            resumeIfPaused();
                            serverPollSeconds = ok.value().nextPollSeconds();
                            ok.value().messages().forEach(message -> logger.info("Mensagem da MinePag: " + message));
                            warnIfOutdated(ok.value().minPluginVersion());
                        }
                        case ApiOutcome.HttpFailure<AgentProtocol.HeartbeatResponse> failure -> {
                            if (failure.isAuthFailure() || failure.isPluginOutdated() || failure.isServerPaused()) {
                                delayAfterHttpFailure(failure, 1);
                                return;
                            }
                            debugLog("Heartbeat recusado: HTTP " + failure.status() + ".");
                        }
                        case ApiOutcome.NetworkFailure<AgentProtocol.HeartbeatResponse> failure ->
                                debugLog("Heartbeat falhou: " + failure.message());
                    }
                    int pruned = journal.pruneAckedBefore(clock.instant().minus(JOURNAL_RETENTION));
                    if (pruned > 0) debugLog(pruned + " registro(s) antigos removidos do journal.");
                } catch (RuntimeException failure) {
                    logger.log(Level.WARNING, "Falha ao enviar o heartbeat.", failure);
                } finally {
                    scheduleHeartbeat(AgentProtocol.HEARTBEAT_INTERVAL_SECONDS);
                }
            }

            private void warnIfOutdated(String minPluginVersion) {
                if (outdatedWarningLogged || compareVersions(serverInfo.pluginVersion(), minPluginVersion) >= 0) return;
                outdatedWarningLogged = true;
                logger.warning("Esta versão do plugin (" + serverInfo.pluginVersion() + ") é mais antiga que a mínima "
                        + "suportada (" + minPluginVersion + "). Atualize pelo painel da MinePag.");
            }

            private void debugLog(String message) {
                if (debug) logger.info("[debug] " + message);
            }
        };
    }

    /** Compara versões numéricas por segmento ({@code 1.10.0} > {@code 1.9.3}); sufixos não numéricos são ignorados. */
    static int compareVersions(String left, String right) {
        List<Integer> leftParts = versionParts(left);
        List<Integer> rightParts = versionParts(right);
        for (int index = 0; index < Math.max(leftParts.size(), rightParts.size()); index++) {
            int leftValue = index < leftParts.size() ? leftParts.get(index) : 0;
            int rightValue = index < rightParts.size() ? rightParts.get(index) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static List<Integer> versionParts(String version) {
        List<Integer> parts = new ArrayList<>();
        for (String segment : version.split("[.+-]")) {
            if (!segment.chars().allMatch(Character::isDigit) || segment.isEmpty()) break;
            parts.add(Integer.parseInt(segment.length() > 9 ? segment.substring(0, 9) : segment));
        }
        return parts;
    }
}
