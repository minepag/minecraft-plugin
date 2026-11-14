package com.minepag.plugin.config;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** Mensagens do idioma escolhido, com as mensagens padrão em pt-BR do jar como reserva. */
public record Messages(ConfigurationSection selected, ConfigurationSection fallback) {
    public String text(String key) {
        if (selected != null && selected.isString(key)) return selected.getString(key);
        if (fallback != null && fallback.isString(key)) return fallback.getString(key);
        return key;
    }

    public List<String> lines(String key) {
        if (selected != null && selected.isList(key)) return selected.getStringList(key);
        if (fallback != null && fallback.isList(key)) return fallback.getStringList(key);
        return List.of(key);
    }
}
