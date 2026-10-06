package com.ziggfreed.common.reputation.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.reputation.ReputationFixtures;

/**
 * The server owner's last word: {@code $Enabled} switches every reputation off (and is read at setup on
 * its own), an entry retunes or switches off one, and any mistake in the file costs its overrides, never
 * the server.
 */
class ReputationOwnerLayersTest {

    @TempDir
    Path dir;

    @BeforeEach
    void pointAtTheTempDirectory() {
        ReputationFixtures.reset();
        ReputationOwnerLayers.setDirectory(dir);
        ReputationFixtures.loadCompanions(Map.of("test_faction", ReputationFixtures.companion("Test_Faction",
                "{ \"Text\": { \"TitleKey\": \"test.faction.name\" }, \"Cap\": 5000 }")));
    }

    @AfterEach
    void clearEverything() {
        ReputationFixtures.reset();
    }

    private void write(String json) throws IOException {
        Files.writeString(dir.resolve(ReputationOwnerLayers.FILE), json, StandardCharsets.UTF_8);
    }

    private static ReputationAsset resolved() {
        return ReputationConfig.getInstance().resolve("Test_Faction");
    }

    @Test
    void withNoFileThePacksStandAndTheModuleIsOn() {
        ReputationOwnerLayers.reload();
        assertTrue(ReputationConfig.getInstance().isGlobalEnabled());
        assertEquals(Integer.valueOf(5000), resolved().cap());
    }

    @Test
    void theSwitchTurnsEveryReputationOffAndIsReadOnItsOwnAtSetup() throws IOException {
        write("{ \"$Enabled\": false }");
        ReputationOwnerLayers.readSwitch();
        assertFalse(ReputationConfig.getInstance().isGlobalEnabled());
        write("{}");
        ReputationOwnerLayers.readSwitch();
        assertTrue(ReputationConfig.getInstance().isGlobalEnabled(), "a re-read puts it back on");
    }

    @Test
    void aSwitchThatIsNotTrueOrFalseLeavesTheModuleOn() throws IOException {
        write("{ \"$Enabled\": \"no\" }");
        ReputationOwnerLayers.readSwitch();
        assertTrue(ReputationConfig.getInstance().isGlobalEnabled());
    }

    @Test
    void anEntryRetunesOneLeafAndKeepsWhatThePackSaid() throws IOException {
        write("{ \"test_faction\": { \"Cap\": 9000 } }");
        ReputationOwnerLayers.reload();
        assertEquals(Integer.valueOf(9000), resolved().cap());
        assertEquals("test.faction.name", resolved().titleKey(), "the Text the pack authored is kept");
    }

    @Test
    void anEntrySwitchesOneReputationOff() throws IOException {
        write("{ \"Test_Faction\": { \"Enabled\": false } }");
        ReputationOwnerLayers.reload();
        assertFalse(resolved().isEnabled());
        assertTrue(ReputationConfig.getInstance().isGlobalEnabled(), "the module itself stays on");
    }

    @Test
    void aFileThatIsNotJsonCostsItsOverridesNeverTheServer() throws IOException {
        write("{ not json");
        ReputationOwnerLayers.reload();
        assertTrue(ReputationConfig.getInstance().isGlobalEnabled());
        assertEquals(Integer.valueOf(5000), resolved().cap());
    }
}
