package com.minepag.plugin.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Subcomando do {@code /minepag}: a permissão que o libera e se só um jogador pode usá-lo.
 * A ajuda e o tab-complete mostram só o que o remetente consegue usar de fato.
 */
public record Subcommand(String name, String permission, boolean playersOnly) {
    public static final Subcommand HELP = new Subcommand("help", "minepag.use", false);

    public static final List<Subcommand> ALL = List.of(
            HELP,
            new Subcommand("link", "minepag.link", true),
            new Subcommand("pair", "minepag.admin.pair", false),
            new Subcommand("unpair", "minepag.admin.pair", false),
            new Subcommand("status", "minepag.admin.status", false),
            new Subcommand("forcecheck", "minepag.admin.forcecheck", false),
            new Subcommand("reload", "minepag.admin.reload", false));

    public static Optional<Subcommand> find(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(subcommand -> subcommand.name.equals(normalized)).findFirst();
    }

    /** Subcomandos que o remetente pode usar, na ordem da ajuda. */
    public static List<Subcommand> usableBy(Predicate<String> hasPermission, boolean isPlayer) {
        return ALL.stream().filter(subcommand -> subcommand.allows(hasPermission, isPlayer)).toList();
    }

    /** Linhas da ajuda: os subcomandos utilizáveis, sem o próprio {@code help}. */
    public static List<Subcommand> helpEntries(Predicate<String> hasPermission, boolean isPlayer) {
        return usableBy(hasPermission, isPlayer).stream().filter(subcommand -> subcommand != HELP).toList();
    }

    /** Sugestões do tab-complete para os argumentos já digitados. */
    public static List<String> suggestions(String[] args, Predicate<String> hasPermission, boolean isPlayer) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return usableBy(hasPermission, isPlayer).stream()
                    .map(Subcommand::name)
                    .filter(name -> name.startsWith(typed))
                    .toList();
        }
        boolean completingUnpairConfirmation = args.length == 2
                && args[0].equalsIgnoreCase("unpair")
                && "confirm".startsWith(args[1].toLowerCase(Locale.ROOT));
        return completingUnpairConfirmation && find("unpair").orElseThrow().allows(hasPermission, isPlayer)
                ? List.of("confirm")
                : List.of();
    }

    public boolean allows(Predicate<String> hasPermission, boolean isPlayer) {
        return hasPermission.test(permission) && (isPlayer || !playersOnly);
    }

    public String helpMessageKey() {
        return "help-" + name;
    }
}
