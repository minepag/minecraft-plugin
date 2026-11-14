package com.minepag.plugin.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minepag.plugin.delivery.CommandPolicy;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Confere o plugin contra {@code protocol/minecraft-agent.openapi.json}, o contrato publicado pela MinePag. Quando a
 * MinePag muda o protocolo, rode {@code scripts/sync-protocol.sh}: estes testes mostram o que precisa mudar aqui.
 */
class ProtocolContractTest {
    private static final JsonObject SPEC = loadSpec();

    private static JsonObject loadSpec() {
        try (Reader reader = Files.newBufferedReader(Path.of("protocol/minecraft-agent.openapi.json"))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static JsonObject operation(String route) {
        return SPEC.getAsJsonObject("paths").getAsJsonObject("/" + route).getAsJsonObject("post");
    }

    private static JsonObject jsonSchema(JsonObject content) {
        return content.getAsJsonObject("content").getAsJsonObject("application/json").getAsJsonObject("schema");
    }

    private static JsonObject request(String route) {
        return jsonSchema(operation(route).getAsJsonObject("requestBody"));
    }

    private static JsonObject response(String route) {
        return jsonSchema(operation(route).getAsJsonObject("responses").getAsJsonObject("200"));
    }

    /** Desce pelas propriedades indicadas; em listas, segue para o schema dos itens. */
    private static JsonObject property(JsonObject schema, String... path) {
        JsonObject current = schema;
        for (String name : path) {
            current = current.getAsJsonObject("properties").getAsJsonObject(name);
            if (current.has("items")) current = current.getAsJsonObject("items");
        }
        return current;
    }

    private static Set<String> names(JsonElement element) {
        if (element == null) return Set.of();
        if (element.isJsonObject()) return element.getAsJsonObject().keySet();
        return element.getAsJsonArray().asList().stream()
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void requestsOnlySendFieldsTheServerAcceptsAndAlwaysSendTheRequiredOnes() {
        Map<Class<? extends Record>, JsonObject> requests = Map.of(
                AgentProtocol.PairRequest.class, request("pair"),
                AgentProtocol.PairServer.class, property(request("pair"), "server"),
                AgentProtocol.PullRequest.class, request("pull"),
                AgentProtocol.OnlinePlayer.class, property(request("pull"), "onlinePlayers"),
                AgentProtocol.AckRequest.class, request("ack"),
                AgentProtocol.AckResult.class, property(request("ack"), "results"),
                AgentProtocol.HeartbeatRequest.class, request("heartbeat"),
                AgentProtocol.LinkRequest.class, request("link"));

        requests.forEach((type, schema) -> {
            Set<String> accepted = names(schema.get("properties"));
            assertTrue(accepted.containsAll(fields(type)), type.getSimpleName() + " envia campos fora do contrato");
            assertTrue(fields(type).containsAll(names(schema.get("required"))), type.getSimpleName() + " não envia um campo obrigatório");
        });
    }

    @Test
    void responsesOnlyReadFieldsTheServerSends() {
        Map<Class<? extends Record>, JsonObject> responses = Map.of(
                AgentProtocol.PairResponse.class, response("pair"),
                AgentProtocol.NamedRef.class, property(response("pair"), "server"),
                AgentProtocol.PairedProject.class, property(response("pair"), "project"),
                AgentProtocol.PullResponse.class, response("pull"),
                AgentProtocol.AgentCommand.class, property(response("pull"), "commands"),
                AgentProtocol.Announcement.class, property(response("pull"), "announcements"),
                AgentProtocol.AckResponse.class, response("ack"),
                AgentProtocol.HeartbeatResponse.class, response("heartbeat"),
                AgentProtocol.LinkResponse.class, response("link"));

        responses.forEach((type, schema) -> assertTrue(
                names(schema.get("properties")).containsAll(fields(type)),
                type.getSimpleName() + " lê campos que a MinePag não envia"));
    }

    @Test
    void ackStatusesMatchTheServer() {
        JsonArray statuses = property(request("ack"), "results", "status").getAsJsonArray("enum");
        List<String> pluginStatuses = Arrays.stream(AgentProtocol.ResultStatus.values())
                .map(AgentProtocol.ResultStatus::wireName)
                .toList();
        assertEquals(names(statuses), new LinkedHashSet<>(pluginStatuses));
    }

    @Test
    void blockedCommandsMatchTheServer() {
        assertEquals(names(SPEC.get("x-minepag-blocked-commands")), new LinkedHashSet<>(CommandPolicy.ALWAYS_BLOCKED));
    }

    @Test
    void routesAndLimitsMatchTheServer() {
        assertEquals(Set.of("/pair", "/pull", "/ack", "/heartbeat", "/link", "/unpair"), SPEC.getAsJsonObject("paths").keySet());
        String serverUrl = SPEC.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString();
        assertTrue(serverUrl.endsWith(AgentProtocol.API_BASE), "base do protocolo diferente da MinePag");

        JsonObject limits = SPEC.getAsJsonObject("x-minepag-limits");
        assertEquals(limits.get("defaultPollSeconds").getAsInt(), AgentProtocol.DEFAULT_POLL_SECONDS);
        assertEquals(limits.get("minPollSeconds").getAsInt(), AgentProtocol.MIN_POLL_SECONDS);
        assertEquals(limits.get("maxPollSeconds").getAsInt(), AgentProtocol.MAX_POLL_SECONDS);
        assertEquals(limits.get("maxOnlinePlayers").getAsInt(), AgentProtocol.MAX_ONLINE_PLAYERS);
        assertEquals(limits.get("maxCommandsPerPull").getAsInt(), AgentProtocol.MAX_COMMANDS_PER_PULL);
        assertEquals(limits.get("maxAckBatch").getAsInt(), AgentProtocol.MAX_ACK_BATCH);
        assertEquals(limits.get("heartbeatIntervalSeconds").getAsInt(), AgentProtocol.HEARTBEAT_INTERVAL_SECONDS);
    }
}
