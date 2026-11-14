package com.minepag.plugin.platform;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversão de templates MiniMessage para o formato legado (§), usada no Spigot. */
public final class LegacyText {
    private LegacyText() {}

    private static final char SECTION = '§';
    private static final Pattern TAG = Pattern.compile("<(/?)([^<>/][^<>]*)>");
    private static final Pattern LEGACY_CODE_SEQUENCE = Pattern.compile(SECTION + ".?");
    private static final Map<String, Character> LEGACY_CODES = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'),
            Map.entry("dark_aqua", '3'), Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'),
            Map.entry("gold", '6'), Map.entry("gray", '7'), Map.entry("grey", '7'), Map.entry("dark_gray", '8'),
            Map.entry("dark_grey", '8'), Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'),
            Map.entry("red", 'c'), Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'),
            Map.entry("obfuscated", 'k'), Map.entry("obf", 'k'), Map.entry("bold", 'l'), Map.entry("b", 'l'),
            Map.entry("strikethrough", 'm'), Map.entry("st", 'm'), Map.entry("underlined", 'n'), Map.entry("u", 'n'),
            Map.entry("italic", 'o'), Map.entry("i", 'o'), Map.entry("em", 'o'), Map.entry("reset", 'r'));
    private static final Set<String> DROPPED_TAGS =
            Set.of("click", "hover", "gradient", "rainbow", "font", "insertion", "transition", "color", "colour", "c");

    /**
     * Cores e decorações viram códigos §, placeholders recebem o valor (sem códigos §), tags interativas somem e tags
     * desconhecidas ficam como texto, como no MiniMessage.
     */
    public static String render(String template, Map<String, String> placeholders) {
        Matcher matcher = TAG.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            String name = tag.split(":", 2)[0];
            String replacement;
            if (!closing && placeholders.containsKey(tag)) {
                replacement = LEGACY_CODE_SEQUENCE.matcher(placeholders.get(tag)).replaceAll("");
            } else if (LEGACY_CODES.containsKey(name)) {
                replacement = closing ? SECTION + "r" : String.valueOf(SECTION) + LEGACY_CODES.get(name);
            } else if (name.startsWith("#") || DROPPED_TAGS.contains(name)) {
                replacement = "";
            } else {
                replacement = matcher.group();
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
