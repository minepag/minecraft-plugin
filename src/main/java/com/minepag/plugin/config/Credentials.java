package com.minepag.plugin.config;

import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.PairResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Token do servidor e dados do pareamento. Fica em {@code data/credentials.yml}, separado do config.yml, com
 * permissão 600 quando o sistema de arquivos suporta.
 */
public record Credentials(
        String token,
        String serverId,
        String serverName,
        String projectId,
        String projectName,
        String storeUrl,
        Instant pairedAt) {

    public static Credentials fromPairing(PairResponse response, Instant now) {
        return new Credentials(
                response.token(),
                response.server().id(),
                response.server().name(),
                response.project().id(),
                response.project().name(),
                response.project().storeUrl(),
                now);
    }

    public static Optional<Credentials> load(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        String token = yaml.getString("token");
        if (!AgentProtocol.isServerToken(token)) return Optional.empty();
        Instant pairedAt;
        try {
            pairedAt = Instant.parse(yaml.getString("paired-at", Instant.EPOCH.toString()));
        } catch (DateTimeParseException invalid) {
            pairedAt = Instant.EPOCH;
        }
        return Optional.of(new Credentials(
                token,
                yaml.getString("server.id", ""),
                yaml.getString("server.name", ""),
                yaml.getString("project.id", ""),
                yaml.getString("project.name", ""),
                yaml.getString("project.store-url"),
                pairedAt));
    }

    public void save(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Credenciais da MinePag. NÃO compartilhe este arquivo: o token dá acesso às entregas da loja.",
                "Para trocar de loja, gere um novo código no painel e use /minepag pair <código>."));
        yaml.set("token", token);
        yaml.set("server.id", serverId);
        yaml.set("server.name", serverName);
        yaml.set("project.id", projectId);
        yaml.set("project.name", projectName);
        yaml.set("project.store-url", storeUrl);
        yaml.set("paired-at", pairedAt.toString());

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String maskedToken() {
        int visible = Math.min(token.length(), AgentProtocol.SERVER_TOKEN_PREFIX.length() + 4);
        return token.substring(0, visible) + "…";
    }

    @Override
    public String toString() {
        return "Credentials[server=" + serverName + ", project=" + projectName + ", token=" + maskedToken() + "]";
    }
}
