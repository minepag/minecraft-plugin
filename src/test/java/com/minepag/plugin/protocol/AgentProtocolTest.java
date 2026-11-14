package com.minepag.plugin.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import com.minepag.plugin.protocol.AgentProtocol.AckRequest;
import com.minepag.plugin.protocol.AgentProtocol.AckResult;
import com.minepag.plugin.protocol.AgentProtocol.OnlinePlayer;
import com.minepag.plugin.protocol.AgentProtocol.PullBatch;
import com.minepag.plugin.protocol.AgentProtocol.PullRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentProtocolTest {
    private static final String COMMAND_ID = "0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d";
    private static final String PLAYER_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    private static String command(String id, String command, boolean requireOnline, String playerUuid) {
        String player = playerUuid == null ? "" : ",\"playerUuid\":\"%s\",\"playerName\":\"Notch\"".formatted(playerUuid);
        return """
                {"id":"%s","command":"%s","requireOnline":%s,"requiredSlots":2,"attempt":1,
                 "leaseExpiresAt":"2026-10-12T15:00:00.000Z"%s}""".formatted(id, command, requireOnline, player);
    }

    private static String pull(String commands, String announcements, int nextPollSeconds) {
        return """
                {"commands":[%s],"announcements":[%s],"nextPollSeconds":%d,"hasMore":false}"""
                .formatted(commands, announcements, nextPollSeconds);
    }

    @Test
    void parsesValidPullResponse() {
        String json = pull(
                command(COMMAND_ID, "lp user Notch parent addtemp vip 30d", true, PLAYER_UUID),
                """
                {"id":"%s","playerName":"Notch","productName":"VIP 30 dias","storeUrl":"https://loja.minepag.app"}"""
                        .formatted(COMMAND_ID),
                30);

        PullBatch batch = AgentProtocol.parsePullResponse(json);

        assertEquals(1, batch.commands().size());
        assertTrue(batch.rejected().isEmpty());
        assertEquals("Notch", batch.commands().getFirst().playerName());
        assertEquals(2, batch.commands().getFirst().requiredSlots());
        assertEquals(1, batch.announcements().size());
        assertEquals(30, batch.nextPollSeconds());
        assertFalse(batch.hasMore());
    }

    @Test
    void rejectsCommandsThatBreakTheContractButKeepTheirIdsForAck() {
        String json = pull(
                String.join(",",
                        command(COMMAND_ID, "say oi\\nop Notch", false, null),
                        command("nao-e-uuid", "say oi", false, null),
                        command("1b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d", "give Notch diamond", true, null)),
                "",
                30);

        PullBatch batch = AgentProtocol.parsePullResponse(json);

        assertTrue(batch.commands().isEmpty());
        assertEquals(2, batch.rejected().size(), "comando com id inválido é descartado, não confirmado");
        assertEquals(COMMAND_ID, batch.rejected().getFirst().id());
    }

    @Test
    void shiftsLeaseDeadlinesToTheLocalClock() {
        PullBatch batch = AgentProtocol.parsePullResponse(pull(command(COMMAND_ID, "say oi", false, null), "", 30));

        PullBatch shifted = batch.withLeasesShiftedBy(Duration.ofMinutes(10));

        assertEquals(
                OffsetDateTime.parse("2026-10-12T15:10:00Z").toInstant(),
                OffsetDateTime.parse(shifted.commands().getFirst().leaseExpiresAt()).toInstant());
        assertEquals(batch, batch.withLeasesShiftedBy(Duration.ZERO));
    }

    @Test
    void acceptsJavaAndBedrockNicknamesFromTheContract() {
        for (String valid : List.of("Notch", "Steve_01", ".BedrockGamer", ".a")) {
            assertTrue(AgentProtocol.NICKNAME_PATTERN.matcher(valid).matches(), valid);
        }
        for (String invalid : List.of("ab", "..dupla", ".", ".nick-com-hifen", "<red>x", ".abcdefghijklmnop")) {
            assertFalse(AgentProtocol.NICKNAME_PATTERN.matcher(invalid).matches(), invalid);
        }
    }

    @Test
    void serializesDeferredAckWithContractStatus() {
        String json = AgentProtocol.toJson(new AckRequest(List.of(
                new AckResult(COMMAND_ID, AgentProtocol.ResultStatus.DEFERRED.wireName(), "Inventário cheio.", "2026-10-12T15:00:00Z"))));
        assertTrue(json.contains("\"status\":\"deferred\""), json);
        assertEquals(AgentProtocol.ResultStatus.DEFERRED, AgentProtocol.ResultStatus.fromWireName("deferred"));
    }

    @Test
    void dropsAnnouncementsWithUnsafeData() {
        String json = pull(
                "",
                """
                {"id":"%s","playerName":"<red>x","productName":"VIP","storeUrl":null},
                {"id":"%s","playerName":"Notch","productName":"VIP","storeUrl":"javascript:alert(1)"}"""
                        .formatted(COMMAND_ID, COMMAND_ID),
                30);

        assertTrue(AgentProtocol.parsePullResponse(json).announcements().isEmpty());
    }

    @Test
    void rejectsPollIntervalOutsideContract() {
        assertThrows(JsonParseException.class, () -> AgentProtocol.parsePullResponse(pull("", "", 1)));
        assertThrows(JsonParseException.class, () -> AgentProtocol.parsePullResponse(pull("", "", 301)));
        assertThrows(JsonParseException.class, () -> AgentProtocol.parsePullResponse("null"));
        assertThrows(RuntimeException.class, () -> AgentProtocol.parsePullResponse("{nao json"));
    }

    @Test
    void validatesPairResponseToken() {
        String template = """
                {"token":"%s","server":{"id":"%s","name":"Survival"},
                 "project":{"id":"%s","name":"Loja X","storeUrl":null},"nextPollSeconds":10}""";

        var paired = AgentProtocol.parsePairResponse(
                template.formatted("mps_abcdefghijklmnopqrstuvwxyz012345", COMMAND_ID, PLAYER_UUID));
        assertEquals("Survival", paired.server().name());

        assertThrows(JsonParseException.class, () -> AgentProtocol.parsePairResponse(
                template.formatted("abc_abcdefghijklmnopqrstuvwxyz012345", COMMAND_ID, PLAYER_UUID)));
        assertThrows(JsonParseException.class, () -> AgentProtocol.parsePairResponse(
                template.formatted("mps_abc def ghijklmnopqrstuvwxyz", COMMAND_ID, PLAYER_UUID)));
    }

    @Test
    void parsesErrorBodyFromApiErrorsContract() {
        var error = AgentProtocol.parseError("""
                {"error":{"code":"server_token_revoked","message":"O token do servidor foi revogado."}}""");

        assertEquals("server_token_revoked", error.orElseThrow().code());
        assertTrue(AgentProtocol.parseError("<html>502</html>").isEmpty());
    }

    @Test
    void serializesRequestsWithContractFieldNamesAndWithoutNulls() {
        String pull = AgentProtocol.toJson(new PullRequest(
                "1.0.0", "paper", "26.3", List.of(new OnlinePlayer(PLAYER_UUID, "Notch")), 50, null));
        assertEquals(
                "{\"pluginVersion\":\"1.0.0\",\"platform\":\"paper\",\"mcVersion\":\"26.3\",\"onlinePlayers\":"
                        + "[{\"uuid\":\"" + PLAYER_UUID + "\",\"name\":\"Notch\"}],\"limit\":50}",
                pull);

        String ack = AgentProtocol.toJson(new AckRequest(
                List.of(new AckResult(COMMAND_ID, "succeeded", null, "2026-10-12T15:00:00Z"))));
        assertEquals(
                "{\"results\":[{\"id\":\"" + COMMAND_ID + "\",\"status\":\"succeeded\",\"executedAt\":\"2026-10-12T15:00:00Z\"}]}",
                ack);
    }
}
