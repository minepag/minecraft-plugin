package com.minepag.plugin.platform;

import com.minepag.plugin.protocol.AgentProtocol;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;

/** Detecção da plataforma e métricas que só existem em algumas delas, sempre por reflexão. */
public final class ServerPlatform {
    private ServerPlatform() {}

    public static final String FOLIA = "folia";
    public static final String PURPUR = "purpur";
    public static final String PAPER = "paper";
    public static final String SPIGOT = "spigot";

    public static String detect() {
        if (classExists("io.papermc.paper.threadedregions.RegionizedServer")) return FOLIA;
        if (classExists("org.purpurmc.purpur.PurpurConfig")) return PURPUR;
        if (classExists("io.papermc.paper.configuration.Configuration")
                || classExists("com.destroystokyo.paper.PaperConfig")) {
            return PAPER;
        }
        return SPIGOT;
    }

    public static boolean classExists(String className) {
        try {
            Class.forName(className, false, ServerPlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    public static boolean isAssignable(String className, Class<?> type) {
        try {
            return Class.forName(className, false, type.getClassLoader()).isAssignableFrom(type);
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Versão do Minecraft ({@code 1.21.11}, {@code 26.3}). Usa a API do Paper quando existe. */
    public static String minecraftVersion() {
        String version = invokeOnServer("getMinecraftVersion")
                .map(String::valueOf)
                .orElseGet(() -> Bukkit.getBukkitVersion().split("-", 2)[0]);
        return AgentProtocol.truncate(version, AgentProtocol.MAX_VERSION_LENGTH);
    }

    /** TPS do último minuto. Ausente no Spigot e no Folia (que só tem TPS por região). */
    public static Optional<Double> tps(String platform) {
        if (platform.equals(FOLIA) || platform.equals(SPIGOT)) return Optional.empty();
        return invokeOnServer("getTPS")
                .filter(double[].class::isInstance)
                .map(double[].class::cast)
                .filter(values -> values.length > 0 && Double.isFinite(values[0]))
                .map(values -> Math.round(Math.clamp(values[0], 0.0, 100.0) * 100.0) / 100.0);
    }

    public static String javaVersion() {
        return AgentProtocol.truncate(System.getProperty("java.version", "desconhecida"), AgentProtocol.MAX_VERSION_LENGTH);
    }

    private static Optional<Object> invokeOnServer(String methodName) {
        try {
            Method method = Bukkit.getServer().getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(Bukkit.getServer()));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
