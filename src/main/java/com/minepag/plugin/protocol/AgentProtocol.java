package com.minepag.plugin.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tipos do protocolo {@code /api/minecraft/v1}, descrito em {@code protocol/minecraft-agent.openapi.json}. Os nomes de
 * campo seguem o contrato; {@code ProtocolContractTest} confere.
 */
public final class AgentProtocol {
    private AgentProtocol() {}

    public static final String API_BASE = "/api/minecraft/v1";
    public static final String PAIR_PATH = API_BASE + "/pair";
    public static final String PULL_PATH = API_BASE + "/pull";
    public static final String ACK_PATH = API_BASE + "/ack";
    public static final String HEARTBEAT_PATH = API_BASE + "/heartbeat";
    public static final String LINK_PATH = API_BASE + "/link";
    public static final String UNPAIR_PATH = API_BASE + "/unpair";

    public static final String SERVER_TOKEN_PREFIX = "mps_";
    /** Nick Java (3 a 16) ou Bedrock via Floodgate (prefixo {@code .}, até 16 no total). */
    public static final Pattern NICKNAME_PATTERN = Pattern.compile("^(?:[A-Za-z0-9_]{3,16}|\\.[A-Za-z0-9_]{1,15})$");
    public static final Pattern PAIRING_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{8}$");
    public static final Pattern LINK_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");
    public static final Pattern GUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cc}");
    private static final Pattern SERVER_TOKEN_PATTERN = Pattern.compile("^mps_[\\x21-\\x7E]{16,500}$");

    public static final int DEFAULT_POLL_SECONDS = 30;
    public static final int MIN_POLL_SECONDS = 5;
    public static final int MAX_POLL_SECONDS = 300;
    public static final int MAX_ONLINE_PLAYERS = 2000;
    public static final int MAX_COMMANDS_PER_PULL = 100;
    public static final int MAX_ONLY_PLAYER_UUIDS = 100;
    public static final int MAX_ACK_BATCH = 100;
    public static final int HEARTBEAT_INTERVAL_SECONDS = 300;
    public static final int MAX_COMMAND_LENGTH = 1000;
    public static final int MAX_ACK_OUTPUT_LENGTH = 1000;
    public static final int MAX_VERSION_LENGTH = 40;
    public static final int MAX_MOTD_LENGTH = 200;
    public static final int MAX_REQUIRED_SLOTS = 36;

    public enum ResultStatus {
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        UNKNOWN("unknown"),
        /** Não executado (jogador saiu ou inventário cheio): volta para a fila sem contar tentativa. Nunca vai ao journal. */
        DEFERRED("deferred");

        private final String wireName;

        ResultStatus(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ResultStatus fromWireName(String value) {
            for (ResultStatus status : values()) {
                if (status.wireName.equals(value)) return status;
            }
            throw new IllegalArgumentException("Status de resultado desconhecido: " + value);
        }
    }

    public record PairServer(String pluginVersion, String platform, String mcVersion, String motd, boolean onlineMode) {}

    public record PairRequest(String code, PairServer server) {}

    public record NamedRef(String id, String name) {}

    public record PairedProject(String id, String name, String storeUrl) {}

    public record PairResponse(String token, NamedRef server, PairedProject project, int nextPollSeconds) {}

    public record OnlinePlayer(String uuid, String name) {}

    public record PullRequest(
            String pluginVersion,
            String platform,
            String mcVersion,
            List<OnlinePlayer> onlinePlayers,
            int limit,
            List<String> onlyPlayerUuids) {}

    public record AgentCommand(
            String id,
            String command,
            boolean requireOnline,
            int requiredSlots,
            String playerUuid,
            String playerName,
            int attempt,
            String leaseExpiresAt) {}

    public record Announcement(String id, String playerName, String productName, String storeUrl) {}

    public record PullResponse(
            List<AgentCommand> commands, List<Announcement> announcements, int nextPollSeconds, boolean hasMore) {}

    /** Comando com id válido mas que viola o contrato: é confirmado como falha, nunca executado. */
    public record RejectedCommand(String id, int attempt, String reason) {}

    public record PullBatch(
            List<AgentCommand> commands,
            List<RejectedCommand> rejected,
            List<Announcement> announcements,
            int nextPollSeconds,
            boolean hasMore) {
        /**
         * Traz os prazos de reserva para o relógio local. Sem isso, um host com o relógio adiantado veria toda reserva
         * como expirada e nunca entregaria nada.
         */
        public PullBatch withLeasesShiftedBy(Duration localMinusServer) {
            if (localMinusServer.isZero()) return this;
            List<AgentCommand> shifted = commands.stream()
                    .map(command -> new AgentCommand(
                            command.id(),
                            command.command(),
                            command.requireOnline(),
                            command.requiredSlots(),
                            command.playerUuid(),
                            command.playerName(),
                            command.attempt(),
                            OffsetDateTime.parse(command.leaseExpiresAt()).plus(localMinusServer).toString()))
                    .toList();
            return new PullBatch(shifted, rejected, announcements, nextPollSeconds, hasMore);
        }
    }

    public record AckResult(String id, String status, String output, String executedAt) {}

    public record AckRequest(List<AckResult> results) {}

    public record AckResponse(List<String> accepted, List<String> ignored) {}

    public record HeartbeatRequest(
            String pluginVersion,
            String platform,
            String mcVersion,
            int onlinePlayers,
            int maxPlayers,
            Double tps,
            String javaVersion) {}

    public record HeartbeatResponse(int nextPollSeconds, String minPluginVersion, List<String> messages) {}

    public record LinkRequest(String code, String playerUuid, String playerName) {}

    public record LinkResponse(boolean linked, String message) {}

    public record ErrorDetail(String code, String message) {}

    private record ErrorBody(ErrorDetail error) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String toJson(Object body) {
        return GSON.toJson(body);
    }

    public static PairResponse parsePairResponse(String json) {
        PairResponse response = parse(json, PairResponse.class);
        require(isServerToken(response.token()), "token");
        require(response.server() != null && isGuid(response.server().id()) && response.server().name() != null, "server");
        require(
                response.project() != null && isGuid(response.project().id()) && response.project().name() != null,
                "project");
        requirePollSeconds(response.nextPollSeconds());
        return response;
    }

    public static PullBatch parsePullResponse(String json) {
        PullResponse response = parse(json, PullResponse.class);
        require(response.commands() != null, "commands");
        require(response.announcements() != null, "announcements");
        requirePollSeconds(response.nextPollSeconds());

        List<AgentCommand> commands = new ArrayList<>();
        List<RejectedCommand> rejected = new ArrayList<>();
        for (AgentCommand command : response.commands().stream().limit(MAX_COMMANDS_PER_PULL).toList()) {
            if (command == null || !isGuid(command.id())) continue;
            Optional<String> violation = contractViolation(command);
            if (violation.isPresent()) {
                rejected.add(new RejectedCommand(command.id(), Math.max(1, command.attempt()), violation.get()));
            } else {
                commands.add(command);
            }
        }

        List<Announcement> announcements = response.announcements().stream()
                .filter(Objects::nonNull)
                .filter(announcement -> isGuid(announcement.id())
                        && announcement.playerName() != null
                        && NICKNAME_PATTERN.matcher(announcement.playerName()).matches()
                        && announcement.productName() != null
                        && announcement.productName().length() <= 120
                        && (announcement.storeUrl() == null || isHttpUrl(announcement.storeUrl())))
                .toList();

        return new PullBatch(
                List.copyOf(commands),
                List.copyOf(rejected),
                announcements,
                response.nextPollSeconds(),
                response.hasMore());
    }

    public static AckResponse parseAckResponse(String json) {
        AckResponse response = parse(json, AckResponse.class);
        require(response.accepted() != null && response.ignored() != null, "accepted/ignored");
        return response;
    }

    public static HeartbeatResponse parseHeartbeatResponse(String json) {
        HeartbeatResponse response = parse(json, HeartbeatResponse.class);
        requirePollSeconds(response.nextPollSeconds());
        require(response.minPluginVersion() != null && !response.minPluginVersion().isBlank(), "minPluginVersion");
        return new HeartbeatResponse(
                response.nextPollSeconds(),
                response.minPluginVersion(),
                response.messages() == null ? List.of() : response.messages());
    }

    public static LinkResponse parseLinkResponse(String json) {
        LinkResponse response = parse(json, LinkResponse.class);
        require(response.linked() && response.message() != null, "linked");
        return response;
    }

    public static Optional<ErrorDetail> parseError(String json) {
        try {
            ErrorBody body = GSON.fromJson(json, ErrorBody.class);
            return Optional.ofNullable(body).map(ErrorBody::error).filter(error -> error.code() != null);
        } catch (RuntimeException invalidBody) {
            return Optional.empty();
        }
    }

    public static boolean isGuid(String value) {
        return value != null && GUID_PATTERN.matcher(value).matches();
    }

    /** Só ASCII visível: o token vai num cabeçalho HTTP e não pode carregar quebras de linha. */
    public static boolean isServerToken(String value) {
        return value != null && SERVER_TOKEN_PATTERN.matcher(value).matches();
    }

    public static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://")) && value.length() <= 500;
    }

    public static boolean hasControlCharacters(String value) {
        return CONTROL_CHARACTER.matcher(value).find();
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Optional<String> contractViolation(AgentCommand command) {
        String text = command.command();
        if (text == null || text.isBlank() || text.length() > MAX_COMMAND_LENGTH || hasControlCharacters(text)) {
            return Optional.of("Comando vazio, longo demais ou com caracteres de controle.");
        }
        if (command.attempt() < 1) return Optional.of("Tentativa inválida.");
        if (command.requiredSlots() < 0 || command.requiredSlots() > MAX_REQUIRED_SLOTS) {
            return Optional.of("Quantidade de espaços no inventário inválida.");
        }
        if (command.playerUuid() != null && !isGuid(command.playerUuid())) {
            return Optional.of("UUID de jogador inválido.");
        }
        if (command.requireOnline() && command.playerUuid() == null) {
            return Optional.of("Comando exige jogador online, mas não informa o UUID do jogador.");
        }
        try {
            OffsetDateTime.parse(Objects.requireNonNull(command.leaseExpiresAt()));
        } catch (DateTimeParseException | NullPointerException invalidLease) {
            return Optional.of("Prazo de reserva (leaseExpiresAt) inválido.");
        }
        return Optional.empty();
    }

    private static <T> T parse(String json, Class<T> type) {
        T value = GSON.fromJson(json, type);
        if (value == null) throw new JsonParseException("Resposta vazia da API.");
        return value;
    }

    private static void requirePollSeconds(int seconds) {
        require(seconds >= MIN_POLL_SECONDS && seconds <= MAX_POLL_SECONDS, "nextPollSeconds");
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new JsonParseException("Campo inválido na resposta da API: " + field);
    }
}
