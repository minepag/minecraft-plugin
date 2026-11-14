package com.minepag.plugin.platform;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Envio de mensagens com templates MiniMessage. No Paper usa Adventure; no Spigot converte as cores básicas para o
 * formato legado. Valores de placeholders nunca são interpretados como formatação.
 */
public interface Chat {
    void send(CommandSender recipient, String template, Map<String, String> placeholders);

    void broadcast(String template, Map<String, String> placeholders);

    String MINI_MESSAGE_CLASS = "net.kyori.adventure.text.minimessage.MiniMessage";

    /**
     * Exige Adventure nativo do servidor: um plugin que embute MiniMessage sem relocar faria a classe existir no
     * Spigot, mas {@code CommandSender} não seria uma {@code Audience} e o envio quebraria.
     */
    static Chat create() {
        boolean nativeAdventure = ServerPlatform.classExists(MINI_MESSAGE_CLASS)
                && ServerPlatform.isAssignable("net.kyori.adventure.audience.Audience", CommandSender.class);
        return nativeAdventure ? MiniMessageChat.create() : legacy();
    }

    private static Chat legacy() {
        return new Chat() {
            @Override
            public void send(CommandSender recipient, String template, Map<String, String> placeholders) {
                recipient.sendMessage(LegacyText.render(template, placeholders));
            }

            @Override
            public void broadcast(String template, Map<String, String> placeholders) {
                String message = LegacyText.render(template, placeholders);
                Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
                Bukkit.getConsoleSender().sendMessage(message);
            }
        };
    }
}
