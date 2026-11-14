package com.minepag.plugin.config;

import com.minepag.plugin.polling.PollTiming;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        URI apiUrl,
        long pollIntervalSeconds,
        boolean checkOnJoin,
        List<String> blockedCommands,
        boolean announcementsEnabled,
        long announcementMinIntervalSeconds,
        Messages messages,
        boolean debug) {

    public static final URI DEFAULT_API_URL = URI.create("https://minepag.com.br");
    public static final String DEFAULT_LANGUAGE = "pt-BR";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "host.docker.internal");

    public static PluginSettings from(FileConfiguration config, Logger logger) {
        ConfigurationSection messages = config.getConfigurationSection("messages");
        ConfigurationSection fallbackMessages = config.getDefaults() == null
                ? null
                : config.getDefaults().getConfigurationSection("messages." + DEFAULT_LANGUAGE);
        String language = config.getString("language", DEFAULT_LANGUAGE);
        ConfigurationSection selectedMessages = messages == null ? null : messages.getConfigurationSection(language);
        if (selectedMessages == null) {
            logger.warning("Idioma \"" + language + "\" não encontrado em messages; usando " + DEFAULT_LANGUAGE + ".");
            selectedMessages = messages == null ? fallbackMessages : messages.getConfigurationSection(DEFAULT_LANGUAGE);
        }

        return new PluginSettings(
                parseApiUrl(config.getString("api-url"), logger),
                PollTiming.clampPollSeconds(config.getLong("poll-interval-seconds", 30)),
                config.getBoolean("check-on-join", true),
                List.copyOf(config.getStringList("delivery.blocked-commands")),
                config.getBoolean("announcements.enabled", true),
                Math.max(0, config.getLong("announcements.min-interval-seconds", 10)),
                new Messages(selectedMessages, fallbackMessages),
                config.getBoolean("debug", false));
    }

    /** Exige HTTPS para não vazar o token; HTTP só para localhost em desenvolvimento (inclui host.docker.internal, de um servidor em Docker). */
    static URI parseApiUrl(String value, Logger logger) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean secure = scheme.equals("https") || (scheme.equals("http") && LOCAL_HOSTS.contains(host));
            if (secure && !host.isEmpty() && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) {
                return uri;
            }
        } catch (IllegalArgumentException invalid) {
            // Cai no aviso abaixo.
        }
        logger.warning("api-url inválida (use https://...). Usando " + DEFAULT_API_URL + ".");
        return DEFAULT_API_URL;
    }
}
