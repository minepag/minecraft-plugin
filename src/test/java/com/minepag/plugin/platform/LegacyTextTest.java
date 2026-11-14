package com.minepag.plugin.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyTextTest {
    @Test
    void convertsColorsAndInsertsPlaceholdersAsPlainText() {
        String rendered = LegacyText.render(
                "<gold><player></gold> <gray>comprou</gray> <yellow><product>",
                Map.of("player", "Notch", "product", "§kVIP <red>"));

        assertEquals("§6Notch§r §7comprou§r §eVIP <red>", rendered);
    }

    @Test
    void dropsInteractiveTagsAndKeepsUnknownTagsAsText() {
        assertEquals(
                "Use /minepag pair <code> ou <código>",
                LegacyText.render("<click:run_command:'/x'>Use /minepag pair <code></click> ou <código>", Map.of()));
    }
}
