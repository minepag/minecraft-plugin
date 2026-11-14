package com.minepag.plugin.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minepag.plugin.protocol.AgentProtocol.AgentCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandPolicyTest {
    private static final CommandPolicy POLICY = CommandPolicy.create(List.of("op", "Stop", " lp   editor ", "ban-ip", ""));

    private static AgentCommand command(String text, String playerName) {
        return new AgentCommand(
                "0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d",
                text,
                false,
                0,
                null,
                playerName,
                1,
                "2026-10-12T15:00:00Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "op Notch",
        "OP Notch",
        "/op Notch",
        "minecraft:op Notch",
        "  stop",
        "lp editor",
        "lp  EDITOR now",
        "execute as Notch run op Notch",
        "minecraft:execute as @a at @s run execute run minecraft:stop",
        "ban-ip 1.2.3.4",
        "execute as run run op Notch",
        "execute if entity @a run return run op Notch",
        "return run minecraft:op Notch",
        "minecraft:return run stop"
    })
    void blocksDangerousCommands(String text) {
        assertTrue(POLICY.rejectionReason(command(text, null)).isPresent(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "operator Notch",
        "lp user Notch parent addtemp vip 30d",
        "give Notch diamond 64",
        "execute as Notch run give Notch diamond",
        "say Obrigado por comprar, op!",
        "give Notch run 1",
        "return run give Notch diamond"
    })
    void allowsRegularCommands(String text) {
        assertTrue(POLICY.rejectionReason(command(text, "Notch")).isEmpty(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"@a", "No", "Nome Com Espaco", "abcdefghijklmnopq", "..Bedrock", ".Bedrock-Player", "Notch;op"})
    void rejectsInvalidNicknames(String playerName) {
        assertEquals("Nick de jogador inválido.", POLICY.rejectionReason(command("give x diamond", playerName)).orElseThrow());
    }

    @Test
    void acceptsBedrockNicknamesFromFloodgate() {
        assertTrue(POLICY.rejectionReason(command("give .BedrockPlayer diamond", ".BedrockPlayer")).isEmpty());
    }

    @Test
    void rejectsControlCharactersAndInvalidUuid() {
        assertTrue(POLICY.rejectionReason(command("say oi\nop Notch", null)).isPresent());
        AgentCommand badUuid = new AgentCommand(
                "0b6f2c1e-3d4a-4f5b-8c9d-0e1f2a3b4c5d", "say oi", true, 0, "123", null, 1, "2026-10-12T15:00:00Z");
        assertTrue(POLICY.rejectionReason(badUuid).isPresent());
    }

    @Test
    void keepsCoreCommandsBlockedEvenWithAnEmptyList() {
        CommandPolicy permissive = CommandPolicy.create(List.of());
        assertTrue(permissive.rejectionReason(command("minecraft:op Notch", null)).isPresent());
        assertTrue(permissive.rejectionReason(command("execute run minepag pair ABCDEFGH", null)).isPresent());
        assertTrue(permissive.rejectionReason(command("transfer play.outro.com", null)).isPresent());
        assertTrue(permissive.rejectionReason(command("sudo Notch op Notch", null)).isPresent());
        assertTrue(permissive.rejectionReason(command("luckperms editor", null)).isPresent());
        assertTrue(permissive.rejectionReason(command("give Notch diamond", null)).isEmpty());
    }

    @Test
    void normalizesRootNamespaceAndWhitespace() {
        assertEquals("op notch", CommandPolicy.normalize("  /Minecraft:OP   Notch "));
        assertEquals(List.of("execute as x run give x 1", "give x 1"), CommandPolicy.invocations("execute as x run give x 1"));
    }
}
