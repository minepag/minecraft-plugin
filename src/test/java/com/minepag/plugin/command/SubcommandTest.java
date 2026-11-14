package com.minepag.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SubcommandTest {
    private static final Predicate<String> DEFAULT_PLAYER = Set.of("minepag.use", "minepag.link")::contains;
    private static final Predicate<String> EVERYTHING = permission -> true;
    private static final Predicate<String> NOTHING = permission -> false;

    private static List<String> names(List<Subcommand> subcommands) {
        return subcommands.stream().map(Subcommand::name).toList();
    }

    @Test
    void helpForDefaultPlayerListsOnlyLink() {
        assertEquals(List.of("link"), names(Subcommand.helpEntries(DEFAULT_PLAYER, true)));
    }

    @Test
    void helpForConsoleListsAdminCommandsButNotPlayerOnlyLink() {
        assertEquals(
                List.of("pair", "unpair", "status", "forcecheck", "reload"),
                names(Subcommand.helpEntries(EVERYTHING, false)));
    }

    @Test
    void helpForOperatorPlayerListsEverything() {
        assertEquals(
                List.of("link", "pair", "unpair", "status", "forcecheck", "reload"),
                names(Subcommand.helpEntries(EVERYTHING, true)));
    }

    @Test
    void helpFollowsIndividualAdminPermissions() {
        Predicate<String> statusOnly = Set.of("minepag.use", "minepag.admin.status")::contains;
        assertEquals(List.of("status"), names(Subcommand.helpEntries(statusOnly, true)));
    }

    @Test
    void helpIsEmptyWithoutPermissions() {
        assertTrue(Subcommand.helpEntries(NOTHING, true).isEmpty());
    }

    @Test
    void tabCompleteHidesAdminCommandsFromDefaultPlayer() {
        assertEquals(List.of("help", "link"), Subcommand.suggestions(new String[] {""}, DEFAULT_PLAYER, true));
        assertTrue(Subcommand.suggestions(new String[] {"st"}, DEFAULT_PLAYER, true).isEmpty());
        assertTrue(Subcommand.suggestions(new String[] {"unpair", ""}, DEFAULT_PLAYER, true).isEmpty());
    }

    @Test
    void tabCompleteForConsoleSkipsLinkAndCompletesUnpairConfirmation() {
        assertEquals(
                List.of("help", "pair", "unpair", "status", "forcecheck", "reload"),
                Subcommand.suggestions(new String[] {""}, EVERYTHING, false));
        assertEquals(List.of("status"), Subcommand.suggestions(new String[] {"ST"}, EVERYTHING, false));
        assertEquals(List.of("confirm"), Subcommand.suggestions(new String[] {"unpair", "co"}, EVERYTHING, false));
        assertTrue(Subcommand.suggestions(new String[] {"unpair", "x"}, EVERYTHING, false).isEmpty());
        assertTrue(Subcommand.suggestions(new String[] {"status", ""}, EVERYTHING, false).isEmpty());
    }

    @Test
    void findIgnoresCase() {
        assertEquals("reload", Subcommand.find("ReLoAd").orElseThrow().name());
        assertTrue(Subcommand.find("op").isEmpty());
    }

    @Test
    void bundledConfigHasHelpLineForEverySubcommandInBothLanguages() throws IOException {
        String config;
        try (InputStream input = SubcommandTest.class.getResourceAsStream("/config.yml")) {
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> keys = Stream.concat(
                        Stream.of("help-header", "help-empty"),
                        Subcommand.ALL.stream()
                                .filter(subcommand -> subcommand != Subcommand.HELP)
                                .map(Subcommand::helpMessageKey))
                .toList();
        for (String key : keys) {
            assertEquals(2, config.split("\n    " + key + ": ", -1).length - 1, key);
        }
    }
}
