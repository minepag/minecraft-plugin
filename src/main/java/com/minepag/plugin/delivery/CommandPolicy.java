package com.minepag.plugin.delivery;

import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.AgentCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Última barreira local antes do dispatch: valida o jogador e bloqueia comandos perigosos. */
@FunctionalInterface
public interface CommandPolicy {
    Optional<String> rejectionReason(AgentCommand command);

    /** Comandos que executam outro comando depois de um token {@code run}: {@code execute ... run} e {@code return run}. */
    Set<String> COMMAND_WRAPPERS = Set.of("execute", "return");

    /**
     * Lista fixa, igual a {@code x-minepag-blocked-commands} em {@code protocol/minecraft-agent.openapi.json}
     * ({@code ProtocolContractTest} confere). {@code delivery.blocked-commands} só acrescenta comandos.
     */
    List<String> ALWAYS_BLOCKED = List.of(
            "op",
            "deop",
            "stop",
            "restart",
            "reload",
            "rl",
            "whitelist",
            "ban-ip",
            "pardon-ip",
            "save-off",
            "transfer",
            "sudo",
            "esudo",
            "plugman",
            "minepag",
            "mpag",
            "lp editor",
            "luckperms editor",
            "lp import",
            "luckperms import");

    static CommandPolicy create(Collection<String> blockedCommands) {
        List<String> blocked = Stream.concat(ALWAYS_BLOCKED.stream(), blockedCommands.stream())
                .map(CommandPolicy::normalize)
                .filter(entry -> !entry.isEmpty())
                .distinct()
                .toList();

        return command -> {
            if (command.playerName() != null && !AgentProtocol.NICKNAME_PATTERN.matcher(command.playerName()).matches()) {
                return Optional.of("Nick de jogador inválido.");
            }
            if (command.playerUuid() != null && !AgentProtocol.isGuid(command.playerUuid())) {
                return Optional.of("UUID de jogador inválido.");
            }
            if (command.command() == null || command.command().isBlank()) {
                return Optional.of("Comando vazio.");
            }
            if (AgentProtocol.hasControlCharacters(command.command())) {
                return Optional.of("Comando com caracteres de controle.");
            }
            for (String invocation : invocations(command.command())) {
                for (String entry : blocked) {
                    if (invocation.equals(entry) || invocation.startsWith(entry + " ")) {
                        return Optional.of("Comando bloqueado pela lista de bloqueio do plugin: " + entry + ".");
                    }
                }
            }
            return Optional.empty();
        };
    }

    /**
     * Comando normalizado e tudo o que vem depois de cada {@code run} de um {@code execute}/{@code return}. Considerar
     * todos os {@code run} (e não só o primeiro) cobre aninhamentos e alvos chamados "run", como em
     * {@code execute as run run op Steve}.
     */
    static List<String> invocations(String command) {
        String normalized = normalize(command);
        if (normalized.isEmpty()) return List.of();
        List<String> invocations = new ArrayList<>(List.of(normalized));
        List<String> tokens = List.of(normalized.split(" "));
        if (!COMMAND_WRAPPERS.contains(tokens.getFirst())) return invocations;
        for (int index = 1; index < tokens.size() - 1; index++) {
            if (tokens.get(index).equals("run")) {
                invocations.add(normalize(String.join(" ", tokens.subList(index + 1, tokens.size()))));
            }
        }
        return invocations;
    }

    /** Minúsculas, espaços colapsados, sem barra inicial e sem namespace no comando raiz ({@code minecraft:op} → {@code op}). */
    static String normalize(String command) {
        String collapsed = command.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).replaceFirst("^/+", "");
        if (collapsed.isEmpty()) return "";
        int rootEnd = collapsed.indexOf(' ');
        String root = rootEnd < 0 ? collapsed : collapsed.substring(0, rootEnd);
        String rest = rootEnd < 0 ? "" : collapsed.substring(rootEnd);
        return root.substring(root.lastIndexOf(':') + 1) + rest;
    }
}
