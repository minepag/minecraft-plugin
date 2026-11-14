package com.minepag.plugin.command;

import com.minepag.plugin.MinePagPlugin;
import com.minepag.plugin.config.Credentials;
import com.minepag.plugin.config.Messages;
import com.minepag.plugin.polling.Poller;
import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.LinkRequest;
import com.minepag.plugin.protocol.AgentProtocol.PairRequest;
import com.minepag.plugin.protocol.AgentProtocol.PairServer;
import com.minepag.plugin.protocol.ApiOutcome;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /minepag pair|unpair|link|status|forcecheck|reload|help}; a ajuda e o tab-complete seguem as permissões. */
public final class MinePagCommand {
    private MinePagCommand() {}

    private static final Duration LINK_COOLDOWN = Duration.ofSeconds(10);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    public static TabExecutor create(MinePagPlugin plugin) {
        Map<UUID, Instant> lastLinkAttempt = new ConcurrentHashMap<>();

        return new TabExecutor() {
            @Override
            public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                Optional<Subcommand> subcommand = args.length == 0 ? Optional.of(Subcommand.HELP) : Subcommand.find(args[0]);
                if (subcommand.isEmpty()) {
                    reply(sender, "unknown-subcommand", Map.of());
                    return true;
                }
                if (!sender.hasPermission(subcommand.get().permission())) {
                    reply(sender, "no-permission", Map.of());
                    return true;
                }
                switch (subcommand.get().name()) {
                    case "pair" -> pair(sender, args);
                    case "unpair" -> unpair(sender, args);
                    case "link" -> link(sender, args);
                    case "status" -> status(sender);
                    case "forcecheck" -> forceCheck(sender);
                    case "reload" -> {
                        plugin.reload();
                        reply(sender, "reload-done", Map.of());
                    }
                    default -> help(sender);
                }
                return true;
            }

            @Override
            public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
                return Subcommand.suggestions(args, sender::hasPermission, sender instanceof Player);
            }

            private void help(CommandSender sender) {
                List<Subcommand> entries = Subcommand.helpEntries(sender::hasPermission, sender instanceof Player);
                if (entries.isEmpty()) {
                    reply(sender, "help-empty", Map.of());
                    return;
                }
                Messages messages = messages();
                plugin.chat().send(sender, messages.text("help-header"), Map.of());
                entries.forEach(entry -> plugin.chat().send(sender, messages.text(entry.helpMessageKey()), Map.of()));
            }

            private void pair(CommandSender sender, String[] args) {
                if (args.length != 2) {
                    reply(sender, "pair-usage", Map.of());
                    return;
                }
                String code = args[1].trim().toUpperCase(Locale.ROOT);
                if (!AgentProtocol.PAIRING_CODE_PATTERN.matcher(code).matches()) {
                    reply(sender, "pair-invalid-code", Map.of());
                    return;
                }
                var info = plugin.serverInfo();
                PairRequest request = new PairRequest(code, new PairServer(
                        info.pluginVersion(),
                        info.platform(),
                        info.mcVersion(),
                        AgentProtocol.truncate(Bukkit.getMotd().replaceAll("§.", "").strip(), AgentProtocol.MAX_MOTD_LENGTH),
                        Bukkit.getOnlineMode()));
                reply(sender, "pair-started", Map.of());

                plugin.scheduler().runAsync(() -> {
                    switch (plugin.session().api().pair(request)) {
                        case ApiOutcome.Ok<AgentProtocol.PairResponse> ok -> {
                            Credentials paired = Credentials.fromPairing(ok.value(), Instant.now());
                            try {
                                plugin.applyPairing(paired);
                            } catch (IOException failure) {
                                plugin.getLogger().log(Level.SEVERE, "Não foi possível salvar data/credentials.yml.", failure);
                                reply(sender, "pair-failed", Map.of("reason", "não foi possível salvar data/credentials.yml."));
                                return;
                            }
                            plugin.getLogger().info("Servidor pareado: loja \"" + paired.projectName() + "\", servidor \""
                                    + paired.serverName() + "\" (por " + sender.getName() + ").");
                            reply(sender, "pair-succeeded", Map.of("server", paired.serverName(), "project", paired.projectName()));
                        }
                        case ApiOutcome.HttpFailure<AgentProtocol.PairResponse> failure -> {
                            switch (failure.code()) {
                                case "pairing_code_expired" -> reply(sender, "pair-code-expired", Map.of());
                                case "invalid_pairing_code" -> reply(sender, "pair-invalid-code", Map.of());
                                case "rate_limited" -> reply(sender, "pair-failed", Map.of("reason", messages().text("rate-limited")));
                                default -> reply(sender, "pair-failed", Map.of("reason", failure.message()));
                            }
                        }
                        case ApiOutcome.NetworkFailure<AgentProtocol.PairResponse> failure -> {
                            plugin.getLogger().warning("Pareamento falhou: " + failure.message());
                            reply(sender, "pair-failed", Map.of("reason", messages().text("api-unavailable")));
                        }
                    }
                });
            }

            /** Revoga o token na MinePag e apaga as credenciais locais. Exige {@code confirm} para evitar acidentes. */
            private void unpair(CommandSender sender, String[] args) {
                if (plugin.credentials().isEmpty()) {
                    reply(sender, "not-paired", Map.of());
                    return;
                }
                if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
                    reply(sender, "unpair-confirm", Map.of());
                    return;
                }
                reply(sender, "unpair-started", Map.of());
                plugin.scheduler().runAsync(() -> {
                    var outcome = plugin.session().api().unpair();
                    boolean revoked = outcome instanceof ApiOutcome.Ok<Boolean>
                            || (outcome instanceof ApiOutcome.HttpFailure<Boolean> failure && failure.isAuthFailure());
                    if (!revoked) {
                        String reason = outcome instanceof ApiOutcome.HttpFailure<Boolean> failure
                                ? failure.message()
                                : messages().text("api-unavailable");
                        reply(sender, "unpair-failed", Map.of("reason", reason));
                        return;
                    }
                    try {
                        plugin.clearPairing();
                    } catch (IOException failure) {
                        plugin.getLogger().log(Level.SEVERE, "Não foi possível apagar data/credentials.yml.", failure);
                        reply(sender, "unpair-failed", Map.of("reason", "não foi possível apagar data/credentials.yml."));
                        return;
                    }
                    plugin.getLogger().info("Servidor despareado por " + sender.getName() + ".");
                    reply(sender, "unpair-succeeded", Map.of());
                });
            }

            private void link(CommandSender sender, String[] args) {
                if (!(sender instanceof Player player)) {
                    reply(sender, "players-only", Map.of());
                    return;
                }
                if (args.length != 2) {
                    reply(sender, "link-usage", Map.of());
                    return;
                }
                String code = args[1].trim().toUpperCase(Locale.ROOT);
                if (!AgentProtocol.LINK_CODE_PATTERN.matcher(code).matches()) {
                    reply(sender, "link-invalid-code", Map.of());
                    return;
                }
                if (!AgentProtocol.NICKNAME_PATTERN.matcher(player.getName()).matches()) {
                    reply(sender, "link-invalid-nickname", Map.of());
                    return;
                }
                if (plugin.credentials().isEmpty()) {
                    reply(sender, "link-not-available", Map.of());
                    plugin.getLogger().warning(player.getName() + " tentou /minepag link, mas o servidor não está pareado.");
                    return;
                }
                Instant now = Instant.now();
                Instant last = lastLinkAttempt.get(player.getUniqueId());
                if (last != null && last.plus(LINK_COOLDOWN).isAfter(now)) {
                    long seconds = Math.max(1, Duration.between(now, last.plus(LINK_COOLDOWN)).toSeconds());
                    reply(sender, "link-cooldown", Map.of("seconds", String.valueOf(seconds)));
                    return;
                }
                lastLinkAttempt.put(player.getUniqueId(), now);
                LinkRequest request = new LinkRequest(code, player.getUniqueId().toString(), player.getName());
                reply(sender, "link-started", Map.of());

                plugin.scheduler().runAsync(() -> {
                    switch (plugin.session().api().link(request)) {
                        case ApiOutcome.Ok<AgentProtocol.LinkResponse> ok ->
                                reply(sender, "link-succeeded", Map.of("message", ok.value().message()));
                        case ApiOutcome.HttpFailure<AgentProtocol.LinkResponse> failure -> {
                            switch (failure.code()) {
                                case "invalid_link_code" -> reply(sender, "link-invalid-code", Map.of());
                                case "invalid_player_name" -> reply(sender, "link-invalid-nickname", Map.of());
                                case "rate_limited" -> reply(sender, "link-failed", Map.of("reason", messages().text("rate-limited")));
                                default -> {
                                    plugin.getLogger().warning("Vínculo de " + player.getName() + " falhou: HTTP " + failure.status()
                                            + " (" + failure.code() + ").");
                                    reply(sender, "link-failed", Map.of("reason", failure.isAuthFailure()
                                            ? messages().text("api-unavailable")
                                            : failure.message()));
                                }
                            }
                        }
                        case ApiOutcome.NetworkFailure<AgentProtocol.LinkResponse> failure -> {
                            plugin.getLogger().warning("Vínculo de " + player.getName() + " falhou: " + failure.message());
                            reply(sender, "link-failed", Map.of("reason", messages().text("api-unavailable")));
                        }
                    }
                });
            }

            private void status(CommandSender sender) {
                Optional<Credentials> credentials = plugin.credentials();
                Optional<Poller> poller = plugin.session().poller();
                if (credentials.isEmpty() || poller.isEmpty()) {
                    reply(sender, "not-paired", Map.of());
                    return;
                }
                Messages messages = messages();
                Poller.Status status = poller.get().status();
                var info = plugin.serverInfo();
                String state = status.running()
                        ? messages.text(status.pausedInPanel() ? "status-paused" : "status-running")
                        : status.haltReason()
                                .map(reason -> messages.text("status-halted").replace("<reason>", reason))
                                .orElse(messages.text("status-stopped"));
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("version", info.pluginVersion());
                placeholders.put("platform", info.platform());
                placeholders.put("mc_version", info.mcVersion());
                placeholders.put("project", credentials.get().projectName());
                placeholders.put("server", credentials.get().serverName());
                placeholders.put("token", credentials.get().maskedToken());
                placeholders.put("state", state);
                placeholders.put("last_poll", status.lastPollAt().map(TIME_FORMAT::format).orElse(messages.text("status-never")));
                placeholders.put("next_poll", status.nextPollAt().map(TIME_FORMAT::format).orElse(messages.text("status-never")));
                placeholders.put("failures", String.valueOf(status.consecutiveFailures()));
                placeholders.put("last_error", status.lastError().orElse(messages.text("status-none")));
                placeholders.put("pending_acks", String.valueOf(plugin.journal().stats().awaitingAck()));
                messages.lines("status").forEach(line -> plugin.chat().send(sender, line, placeholders));
            }

            private void forceCheck(CommandSender sender) {
                Optional<Poller> poller = plugin.session().poller();
                if (poller.isEmpty()) {
                    reply(sender, "not-paired", Map.of());
                    return;
                }
                long cooldownSeconds = poller.get().forceCheck();
                if (cooldownSeconds == 0) reply(sender, "forcecheck-started", Map.of());
                else reply(sender, "forcecheck-cooldown", Map.of("seconds", String.valueOf(cooldownSeconds)));
            }

            private Messages messages() {
                return plugin.session().settings().messages();
            }

            private void reply(CommandSender sender, String key, Map<String, String> placeholders) {
                plugin.chat().send(sender, messages().text("prefix") + messages().text(key), placeholders);
            }
        };
    }
}
