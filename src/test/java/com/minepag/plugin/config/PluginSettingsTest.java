package com.minepag.plugin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
    private static final Logger LOGGER = Logger.getLogger(PluginSettingsTest.class.getName());

    @Test
    void defaultApiUrlIsTheApiHost() {
        assertEquals(URI.create("https://api.minepag.com.br"), PluginSettings.DEFAULT_API_URL);
    }

    @Test
    void keepsACustomHttpsUrl() {
        assertEquals(URI.create("https://homologacao.exemplo.com"), PluginSettings.parseApiUrl("https://homologacao.exemplo.com", LOGGER));
    }

    @Test
    void acceptsLocalHttpForDevelopment() {
        assertEquals(URI.create("http://localhost:3100"), PluginSettings.parseApiUrl("http://localhost:3100", LOGGER));
    }

    @Test
    void rejectsPlainHttpOnPublicHosts() {
        assertEquals(PluginSettings.DEFAULT_API_URL, PluginSettings.parseApiUrl("http://exemplo.com", LOGGER));
    }
}
