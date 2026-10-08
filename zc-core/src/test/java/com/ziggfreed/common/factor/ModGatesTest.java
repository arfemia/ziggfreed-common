package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fold-time mod gate: only a PLAIN top-level {@code hytale:mod_installed} condition with
 * {@code Min >= 1} on a mod the probe answers a definite 0 for drops a file. A bounds-less condition is
 * not a gate (it passes on that 0); an upper bound or a lower Min is a requirement, not a gate; another
 * factor, a malformed Param or a "cannot tell" keeps the file for the hide axis to decide.
 */
class ModGatesTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    @BeforeEach
    void theMmoIsAbsentAndEveryOtherModPresent() {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? 0.0 : 1.0);
    }

    @AfterEach
    void restoreTheEngineProbe() {
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static FactorCondition mod(Double min, Double max) {
        return FactorCondition.of("hytale:mod_installed", MMO, min, max);
    }

    @Test
    void aPlainMinOneConditionOnAnAbsentModDropsTheFile() {
        assertFalse(ModGates.keep(new FactorCondition[] {mod(1.0, null)}));
        assertFalse(ModGates.keep(List.of(mod(1.0, null))));
        assertFalse(ModGates.keep(new FactorCondition[] {FactorCondition.of("HYTALE:MOD_INSTALLED", " " + MMO + " ", 2.0, null)}),
                "the factor id matches without regard to case, the Param is trimmed, and any Min of 1 or more gates");
    }

    @Test
    void theSameFileLoadsWhereTheModIsInstalled() {
        ModGates.useProbeForTests(param -> 1.0);

        assertTrue(ModGates.keep(new FactorCondition[] {mod(1.0, null)}));
    }

    @Test
    void aBoundsLessConditionIsNotAGate() {
        assertFalse(ModGates.isPresenceGate(mod(null, null)),
                "it passes on the 0 an absent mod reads, so it gates nothing and must not drop anything");
        assertTrue(ModGates.keep(new FactorCondition[] {mod(null, null)}));
    }

    @Test
    void anUpperBoundOrAMinBelowOneIsARequirementNotAGate() {
        assertTrue(ModGates.keep(new FactorCondition[] {mod(null, 0.0)}), "only where NOT installed");
        assertTrue(ModGates.keep(new FactorCondition[] {mod(1.0, 1.0)}), "a Max takes it out of the plain form");
        assertTrue(ModGates.keep(new FactorCondition[] {mod(0.5, null)}));
    }

    @Test
    void anotherFactorACannotTellOrAThrowingProbeKeepsTheFile() {
        assertTrue(ModGates.keep(new FactorCondition[] {FactorCondition.of("ziggfreedcommon:feature", MMO, 1.0, null)}));
        ModGates.useProbeForTests(param -> null);
        assertTrue(ModGates.keep(new FactorCondition[] {mod(1.0, null)}), "cannot tell: the hide axis decides live");
        ModGates.useProbeForTests(param -> {
            throw new IllegalStateException("no plugin table yet");
        });
        assertTrue(ModGates.keep(new FactorCondition[] {mod(1.0, null)}));
    }

    @Test
    void oneAbsentGateAmongSeveralConditionsDropsTheFile() {
        assertFalse(ModGates.keep(new FactorCondition[] {
                FactorCondition.of("yourmod:rank", null, 5.0, null), mod(1.0, null)}));
    }

    @Test
    void nothingAuthoredKeepsTheFile() {
        assertTrue(ModGates.keep((List<FactorCondition>) null));
        assertTrue(ModGates.keep((FactorCondition[]) null));
        assertTrue(ModGates.keep(new FactorCondition[0]));
        assertTrue(ModGates.keep(new FactorCondition[] {null}));
    }

    @Test
    void theEngineProbeCannotTellInAUnitJvmSoTheFileStays() {
        ModGates.useProbeForTests(null);

        assertTrue(ModGates.keep(new FactorCondition[] {mod(1.0, null)}),
                "no plugin table here reads null, never a definite 0");
    }

    // ==================== which mod, and the drop line ====================

    @Test
    void missingModNamesTheModThatDropsTheFileAsAuthored() {
        assertEquals(MMO, ModGates.missingMod(new FactorCondition[] {mod(1.0, null)}));
        assertEquals(MMO, ModGates.missingMod(List.of(FactorCondition.of("HYTALE:MOD_INSTALLED", " " + MMO + " ", 2.0, null))),
                "the Group:Name the condition wrote, trimmed");
        assertEquals(MMO, ModGates.missingMod(new FactorCondition[] {
                FactorCondition.of("yourmod:rank", null, 5.0, null), mod(1.0, null)}), "found among other conditions");
    }

    @Test
    void missingModIsNullExactlyWhereTheFileIsKept() {
        assertNull(ModGates.missingMod(new FactorCondition[] {mod(null, null)}), "a bounds-less condition is not a gate");
        assertNull(ModGates.missingMod(new FactorCondition[] {mod(null, 0.0)}), "a requirement, not a gate");
        assertNull(ModGates.missingMod(new FactorCondition[] {mod(0.5, null)}));
        assertNull(ModGates.missingMod((List<FactorCondition>) null));
        assertNull(ModGates.missingMod((FactorCondition[]) null));
        assertNull(ModGates.missingMod(new FactorCondition[] {null}));
        ModGates.useProbeForTests(param -> null);
        assertNull(ModGates.missingMod(new FactorCondition[] {mod(1.0, null)}), "cannot tell keeps the file");
        ModGates.useProbeForTests(param -> 1.0);
        assertNull(ModGates.missingMod(new FactorCondition[] {mod(1.0, null)}), "installed here");
    }

    @Test
    void aDropLogsOneLinePerStorePerMissingModAndNeverNamesAFile() {
        List<String> lines = new ArrayList<>();
        ModGates.reportIntoForTests(lines::add);

        ModGates.reportPackFiles("Quests", List.of(MMO, "Other:Mod", MMO));
        ModGates.reportOwnerOverrides("GearSets", List.of(MMO));
        ModGates.reportPackFiles("Boards", List.of());
        ModGates.reportOwnerOverrides("Currencies", List.of());

        assertEquals(List.of(
                "[zc] mod gate: Quests dropped 1 pack file(s) gated on a missing mod (Other:Mod)",
                "[zc] mod gate: Quests dropped 2 pack file(s) gated on a missing mod (Ziggfreed:MMOSkillTree)",
                "[zc] mod gate: GearSets dropped 1 owner override(s) gated on a missing mod (Ziggfreed:MMOSkillTree)"),
                lines, "nothing dropped says nothing");
    }

    /** The third kind of drop line: a store's fold counts the reward rows it left out, never naming one. */
    @Test
    void aRewardRowDropIsTheThirdLineCountingTheRowsPerMissingMod() {
        List<String> lines = new ArrayList<>();
        ModGates.reportIntoForTests(lines::add);

        ModGates.reportRewardRows("Quests", List.of(MMO, MMO));
        ModGates.reportRewardRows("Bounties", List.of());

        assertEquals(List.of(
                "[zc] mod gate: Quests dropped 2 reward row(s) gated on a missing mod (Ziggfreed:MMOSkillTree)"),
                lines, "nothing dropped says nothing");
    }
}
