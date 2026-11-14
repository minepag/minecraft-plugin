package com.minepag.plugin.platform;

import com.minepag.plugin.protocol.AgentProtocol;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** Implementação Adventure/MiniMessage (Paper, Purpur e Folia). Isolada para nunca ser carregada no Spigot. */
final class MiniMessageChat {
    private MiniMessageChat() {}

    private static final String STORE_URL_PLACEHOLDER = "store_url";

    static Chat create() {
        MiniMessage miniMessage = MiniMessage.miniMessage();

        return new Chat() {
            @Override
            public void send(CommandSender recipient, String template, Map<String, String> placeholders) {
                recipient.sendMessage(render(template, placeholders));
            }

            @Override
            public void broadcast(String template, Map<String, String> placeholders) {
                Bukkit.getServer().sendMessage(render(template, placeholders));
            }

            private Component render(String template, Map<String, String> placeholders) {
                TagResolver resolver = TagResolver.resolver(placeholders.entrySet().stream()
                        .map(entry -> entry.getKey().equals(STORE_URL_PLACEHOLDER) && AgentProtocol.isHttpUrl(entry.getValue())
                                ? Placeholder.component(
                                        entry.getKey(),
                                        Component.text(entry.getValue()).clickEvent(ClickEvent.openUrl(entry.getValue())))
                                : Placeholder.unparsed(entry.getKey(), entry.getValue()))
                        .toList());
                return miniMessage.deserialize(template, resolver);
            }
        };
    }
}
