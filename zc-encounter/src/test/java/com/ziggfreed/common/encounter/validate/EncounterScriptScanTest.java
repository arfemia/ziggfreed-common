package com.ziggfreed.common.encounter.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.encounter.signal.EncounterSignal;

/**
 * The script scan reads what the SHIPPED scripts actually say: the base, the example and the three
 * macros are the fixtures, so a script drifting away from what the framework listens for fails here
 * before it fails in a boot.
 */
class EncounterScriptScanTest {

    private static final Path SCRIPTS = Path.of("src", "main", "resources", "Server", "EncounterManager");

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Resolves a referenced builder name to its shipped file, base and macros alike. */
    private static Function<String, JsonObject> shipped() {
        return name -> {
            try {
                Path direct = SCRIPTS.resolve(name + ".json");
                Path macro = SCRIPTS.resolve("Macros").resolve(name + ".json");
                if (Files.isRegularFile(direct)) {
                    return read(direct);
                }
                return Files.isRegularFile(macro) ? read(macro) : null;
            } catch (IOException e) {
                return null;
            }
        };
    }

    @Test
    void theBaseAuthorsTheWholeFrameworkContract() throws IOException {
        EncounterScriptScan base = EncounterScriptScan.scan("Zc_Encounter_Base", false,
                read(SCRIPTS.resolve("Zc_Encounter_Base.json")), shipped());
        assertTrue(base.authors(EncounterSignal.Moment.ENGAGED));
        assertTrue(base.authors(EncounterSignal.Moment.DEFEATED));
        assertTrue(base.authors(EncounterSignal.Moment.RESET));
        assertTrue(base.memberCollector(), "the Player sensor carries the EncounterMembers collector");
        assertTrue(base.clearsBossBar());
        assertEquals(0, base.defeatBeatsWithoutBarClear(), "the defeat beat clears the bar beside zc:defeated");
        assertTrue(base.targetSlots().contains("Boss"), "the parameterised slot resolves through its default");
        assertFalse(base.spawnable());
        assertEquals(0, base.onceHeadedBlockingLists(), "the re-arm list keeps a plain sensor, so it runs to its end");
        assertTrue(base.setsBossBar(), "the base puts up the bar, so every script built on it opens a run");
        assertEquals(Set.of("Boss"), base.bossBarSlots(), "the bar's sensor slot resolves through its default");
    }

    @Test
    void aBlockingListUnderAOnceSensorIsCountedAndAPlainOneIsNot() {
        JsonObject once = JsonParser.parseString("{\"Instructions\":[{\"Sensor\":{\"Type\":\"Any\",\"Once\":true},"
                + "\"ActionsBlocking\":true,\"Actions\":[{\"Type\":\"Timeout\",\"Delay\":[2,2]},"
                + "{\"Type\":\"State\",\"State\":\"Next\"}]}]}").getAsJsonObject();
        assertEquals(1, EncounterScriptScan.scan("Stalls", true, once, name -> null).onceHeadedBlockingLists());
        JsonObject plain = JsonParser.parseString("{\"Instructions\":[{\"Sensor\":{\"Type\":\"Any\"},"
                + "\"ActionsBlocking\":true,\"Actions\":[{\"Type\":\"Timeout\",\"Delay\":[2,2]},"
                + "{\"Type\":\"State\",\"State\":\"Next\"}]}]}").getAsJsonObject();
        assertEquals(0, EncounterScriptScan.scan("Runs", true, plain, name -> null).onceHeadedBlockingLists());
        JsonObject single = JsonParser.parseString("{\"Instructions\":[{\"Sensor\":{\"Type\":\"Any\",\"Once\":true},"
                + "\"Actions\":[{\"Type\":\"Log\",\"Message\":\"once\"},{\"Type\":\"SignalWorldEvent\","
                + "\"SignalId\":\"zc:engaged\"}]}]}").getAsJsonObject();
        assertEquals(0, EncounterScriptScan.scan("OneTick", true, single, name -> null).onceHeadedBlockingLists(),
                "a non-blocking list runs whole in its one tick, so Once is fine there");
    }

    @Test
    void theExampleWalksEveryReservedBeatAndClearsTheBarAtTheDefeat() throws IOException {
        EncounterScriptScan example = EncounterScriptScan.scan("Zc_Encounter_Example", true,
                read(SCRIPTS.resolve("Zc_Encounter_Example.json")), shipped());
        assertTrue(example.authors(EncounterSignal.Moment.ENGAGED));
        assertTrue(example.authors(EncounterSignal.Moment.WAVE));
        assertTrue(example.authors(EncounterSignal.Moment.PHASE));
        assertTrue(example.authors(EncounterSignal.Moment.DEFEATED));
        assertTrue(example.authors(EncounterSignal.Moment.RESET));
        assertEquals(0, example.defeatBeatsWithoutBarClear());
        assertTrue(example.firesFrameworkSignals());
        assertTrue(example.memberCollector(), "a player who walks up counts, so presence can accrue");
        assertEquals(0, example.onceHeadedBlockingLists(), "every blocking list keeps a plain sensor and runs to its end");
    }

    @Test
    void aMacroReferenceIsFollowedAndItsModifyResolvesAComputedSignal() throws IOException {
        JsonObject script = JsonParser.parseString("{\"Class\":\"EncounterManager\",\"Type\":\"Generic\","
                + "\"Instructions\":[{\"Sensor\":{\"Type\":\"State\",\"State\":\"Phase_1\"},\"Instructions\":["
                + "{\"Reference\":\"Zc_Phase_At_Health\",\"Modify\":{\"_ExportStates\":[\"Phase_2\"],"
                + "\"Signal\":\"zc:phase:Phase_2\",\"Slot\":\"Champion\"}},"
                + "{\"Reference\":\"Zc_Adds_Wave\",\"Modify\":{\"Signal\":\"zc:wave:second\"}},"
                + "{\"Reference\":\"Zc_Defeat_Beat\",\"Modify\":{\"_ExportStates\":[\"Complete\"]}}]}]}").getAsJsonObject();
        EncounterScriptScan scan = EncounterScriptScan.scan("Custom", true, script, shipped());
        assertTrue(scan.signals().contains("zc:phase:Phase_2"), "the Modify value reached the macro's Compute");
        assertTrue(scan.signals().contains("zc:wave:second"));
        assertTrue(scan.authors(EncounterSignal.Moment.DEFEATED), "the defeat macro's own beat is seen");
        assertTrue(scan.targetSlots().contains("Champion"), "the macro's Slot read the Modify");
        assertTrue(scan.targetSlots().contains("Boss"), "the other macros kept their default slot");
        assertEquals(0, scan.defeatBeatsWithoutBarClear());
    }

    @Test
    void aDefeatBeatWithNoBarClearBesideItIsCounted() {
        JsonObject script = JsonParser.parseString("{\"Instructions\":[{\"Actions\":["
                + "{\"Type\":\"SignalWorldEvent\",\"SignalId\":\"zc:defeated\"},{\"Type\":\"State\",\"State\":\"Done\"}]},"
                + "{\"Actions\":[{\"Type\":\"ClearEncounterBossBar\"}]}]}").getAsJsonObject();
        EncounterScriptScan scan = EncounterScriptScan.scan("Bad", true, script, name -> null);
        assertEquals(1, scan.defeatBeatsWithoutBarClear());
        assertTrue(scan.clearsBossBar(), "a clear elsewhere does not rescue the beat");
    }

    @Test
    void aVariantInheritsItsBasesSignals() throws IOException {
        JsonObject variant = JsonParser.parseString("{\"Class\":\"EncounterManager\",\"Type\":\"Variant\","
                + "\"Reference\":\"Zc_Encounter_Base\",\"Modify\":{\"SubjectSlot\":\"Champion\"}}").getAsJsonObject();
        EncounterScriptScan scan = EncounterScriptScan.scan("Variant", true, variant, shipped());
        assertTrue(scan.authors(EncounterSignal.Moment.ENGAGED));
        assertTrue(scan.targetSlots().contains("Champion"), "the Modify reached the base's parameter");
        assertTrue(scan.memberCollector());
        assertTrue(scan.setsBossBar(), "the base's bar is the variant's");
        assertEquals(Set.of("Champion"), scan.bossBarSlots(), "the bar follows the slot the Modify names");
    }

    // ==================== the boss bar, read off the engine's own script shapes ====================

    /** The engine's Example_Boss, its Intro state's two bar-raising instructions and its spawn leaf as authored. */
    private static final String EXAMPLE_BOSS_INTRO = "{\"Class\":\"EncounterManager\",\"Type\":\"Generic\","
            + "\"Instructions\":[{\"Sensor\":{\"Type\":\"State\",\"State\":\"Intro\"},\"Instructions\":["
            + "{\"Continue\":true,\"Sensor\":{\"Type\":\"Target\",\"Range\":100,\"TargetSlot\":\"%1$s\"},"
            + "\"Actions\":[{\"Type\":\"SetEncounterBossBar\",\"Name\":\"server.npcRoles.Test_Boss_Basic_Intro.name\"}]},"
            + "{\"Sensor\":{\"Type\":\"And\",\"Sensors\":[{\"Type\":\"Beacon\",\"Range\":100,\"Message\":\"Boss_In_Position\"},"
            + "{\"Type\":\"Target\",\"Range\":100,\"TargetSlot\":\"%2$s\"}]},"
            + "\"Actions\":[{\"Type\":\"SetTargetNPCInvulnerable\",\"Invulnerable\":false},"
            + "{\"Type\":\"SetEncounterBossBar\",\"Name\":\"server.npcRoles.Test_Boss_Basic.name\"},"
            + "{\"Type\":\"State\",\"State\":\"Phase_1\"}]},"
            + "{\"Sensor\":{\"Type\":\"Not\",\"Sensor\":{\"Type\":\"Target\",\"Range\":100,\"TargetSlot\":\"%3$s\"}},"
            + "\"ActionsBlocking\":true,\"Actions\":[{\"Type\":\"TriggerSpawners\",\"Count\":1,\"Range\":30,"
            + "\"SpawnMarker\":\"Test_Boss_Marker\",\"TargetSlot\":\"%3$s\",\"Rebind\":true}]}]}]}";

    private static JsonObject exampleBoss(String plainSlot, String andSlot, String notSlot) {
        return JsonParser.parseString(String.format(Locale.ROOT, EXAMPLE_BOSS_INTRO, plainSlot, andSlot, notSlot))
                .getAsJsonObject();
    }

    @Test
    void theEnginesExampleBossPutsUpItsBarOverTheBossSlot() {
        EncounterScriptScan scan = EncounterScriptScan.scan("Example_Boss", true, exampleBoss("Boss", "Boss", "Boss"),
                name -> null);
        assertTrue(scan.setsBossBar());
        assertEquals(Set.of("Boss"), scan.bossBarSlots());
    }

    @Test
    void aBarUnderAnAndSensorTakesTheNestedTargetsSlotAndANotSensorNamesNone() {
        EncounterScriptScan scan = EncounterScriptScan.scan("Nested", true, exampleBoss("Intro_Slot", "Champion", "Spawn"),
                name -> null);
        assertEquals(Set.of("Intro_Slot", "Champion"), scan.bossBarSlots(),
                "the And's Target is the bar's; the Not leaf raises no bar and names nothing to it");
        assertTrue(scan.targetSlots().contains("Spawn"), "the spawn slot is still a slot the script names");
    }

    @Test
    void aBarInsideAMacroTakesTheSlotItsReferenceForwards() {
        Map<String, JsonObject> files = Map.of(
                "Bar_Phase", JsonParser.parseString("{\"Class\":\"Instruction\",\"Type\":\"Component\","
                        + "\"Parameters\":{\"Slot\":{\"Value\":\"Boss\"}},\"Content\":{\"Sensor\":{\"Type\":\"Target\","
                        + "\"Range\":100,\"TargetSlot\":{\"Compute\":\"Slot\"}},\"Actions\":[{\"Type\":\"SetEncounterBossBar\","
                        + "\"Name\":\"bar.name\"}]}}").getAsJsonObject(),
                "Bar_Action", JsonParser.parseString("{\"Class\":\"Action\",\"Type\":\"Component\","
                        + "\"Content\":{\"Type\":\"SetEncounterBossBar\",\"Name\":\"bar.name\"}}").getAsJsonObject());
        JsonObject viaInstruction = JsonParser.parseString("{\"Instructions\":[{\"Reference\":\"Bar_Phase\","
                + "\"Modify\":{\"Slot\":\"Champion\"}}]}").getAsJsonObject();
        EncounterScriptScan instruction = EncounterScriptScan.scan("Via_Instruction", true, viaInstruction, files::get);
        assertTrue(instruction.setsBossBar());
        assertEquals(Set.of("Champion"), instruction.bossBarSlots(), "the Modify reached the macro's sensor");
        JsonObject viaAction = JsonParser.parseString("{\"Instructions\":[{\"Sensor\":{\"Type\":\"Mob\",\"LockOnTarget\":true,"
                + "\"LockedTargetSlot\":\"Captured\"},\"Actions\":[{\"Reference\":\"Bar_Action\"}]}]}").getAsJsonObject();
        EncounterScriptScan action = EncounterScriptScan.scan("Via_Action", true, viaAction, files::get);
        assertTrue(action.setsBossBar());
        assertEquals(Set.of("Captured"), action.bossBarSlots(), "an action macro's bar tracks its referrer's sensor");
    }

    @Test
    void theEnginesSkeletonEliteCapturesItsTargetButPutsUpNoBar() {
        JsonObject elite = JsonParser.parseString("{\"Class\":\"EncounterManager\",\"Type\":\"Generic\","
                + "\"StartState\":\"Phase_1\",\"Instructions\":["
                + "{\"Continue\":true,\"Sensor\":{\"Type\":\"Mob\",\"Range\":30,\"LockOnTarget\":true,"
                + "\"LockedTargetSlot\":\"EncounterTarget\",\"Rebind\":true},\"Actions\":[{\"Type\":\"Nothing\"}]},"
                + "{\"Sensor\":{\"Type\":\"State\",\"State\":\"Phase_1\"},\"Instructions\":["
                + "{\"Sensor\":{\"Type\":\"Target\",\"Range\":100,\"TargetSlot\":\"EncounterTarget\"},"
                + "\"ActionsBlocking\":true,\"Actions\":[{\"Type\":\"SetTargetNPCInvulnerable\",\"Invulnerable\":true},"
                + "{\"Type\":\"ChangeTargetRole\",\"Role\":\"Skeleton_Elite_Phase_2\",\"ChangeAppearance\":true},"
                + "{\"Type\":\"State\",\"State\":\"Phase_2\"}]}]}]}").getAsJsonObject();
        EncounterScriptScan scan = EncounterScriptScan.scan("Encounter_Skeleton_Elite", true, elite, name -> null);
        assertFalse(scan.setsBossBar(), "the elite's phase swap is no boss fight of the library's");
        assertTrue(scan.bossBarSlots().isEmpty());
        assertTrue(scan.targetSlots().contains("EncounterTarget"), "the locked slot is a slot the script names");
    }

    @Test
    void theEnginesVoidAnomalyVariantPutsUpNoBar() {
        Map<String, JsonObject> files = Map.of("Template_Void_Anomaly", JsonParser.parseString(
                "{\"Class\":\"EncounterManager\",\"Type\":\"Abstract\",\"Parameters\":{\"CrystalSpawnMarker\":"
                        + "{\"Value\":\"Void_Anomaly_Crystal_Small\"}},\"Instructions\":[{\"Sensor\":{\"Type\":\"And\","
                        + "\"Sensors\":[{\"Type\":\"Target\",\"Range\":100,\"TargetSlot\":\"Crystal\"},{\"Type\":\"Mob\","
                        + "\"LockOnTarget\":true,\"LockedTargetSlot\":\"Crystal\"}]},\"Actions\":[{\"Type\":\"TriggerSpawners\","
                        + "\"SpawnMarker\":{\"Compute\":\"CrystalSpawnMarker\"},\"TargetSlot\":\"Crystal\",\"Count\":1}]}]}")
                .getAsJsonObject());
        JsonObject small = JsonParser.parseString("{\"Class\":\"EncounterManager\",\"Type\":\"Variant\","
                + "\"Reference\":\"Template_Void_Anomaly\",\"Modify\":{\"CrystalSpawnMarker\":\"Void_Anomaly_Crystal_Small\"}}")
                .getAsJsonObject();
        EncounterScriptScan scan = EncounterScriptScan.scan("Void_Anomaly_Small", true, small, files::get);
        assertFalse(scan.setsBossBar(), "a void crystal is never counted as a fight");
        assertTrue(scan.bossBarSlots().isEmpty());
        assertTrue(scan.targetSlots().contains("Crystal"));
    }

    @Test
    void aReferenceCycleStops() {
        Map<String, JsonObject> files = Map.of("Loop", JsonParser.parseString(
                "{\"Instructions\":[{\"Reference\":\"Loop\"}]}").getAsJsonObject());
        EncounterScriptScan scan = EncounterScriptScan.scan("Loop", true, files.get("Loop"), files::get);
        assertTrue(scan.signals().isEmpty());
    }
}
