package com.ziggfreed.common.progress.asset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * A reward row's own {@code Requires}: the file-level presence block's JSON on one row, read through the
 * same mod gate, so a row naming a mod this server lacks yields no spec while its ungated siblings pay. It
 * inherits like {@code Kind} and {@code Params}, an unauthored row carries no gate, "cannot tell" keeps the
 * row, and the Asset Editor lists the leaf with its sentence.
 */
class RewardEntryRequiresTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";
    private static final String PIE = "{ \"Kind\": \"Item\", \"Params\": { \"Item\": \"Harvest_Feast_Pie\", \"Count\": 1 } }";
    private static final String XP = "{ \"Kind\": \"Mmo_Xp\", \"Params\": { \"Skill\": \"Cooking\", \"Amount\": 250 }, "
            + GATE + " }";

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    static RewardEntryAsset row(String json, @Nullable RewardEntryAsset parent) throws IOException {
        return RewardEntryAsset.CODEC.decodeAndInheritJson(RawJsonReader.fromJsonString(json), parent, new ExtraInfo());
    }

    static ContentRewardsAsset rewards(String json) throws IOException {
        return ContentRewardsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    static List<String> kinds(List<RewardSpec> specs) {
        List<String> out = new ArrayList<>();
        for (RewardSpec spec : specs) {
            out.add(spec.kind());
        }
        return out;
    }

    @Test
    void requiresDecodesAsTheFileBlockAndAnUnauthoredRowCarriesNoGate() throws IOException {
        RewardEntryAsset xp = row(XP, null);
        RewardEntryAsset pie = row(PIE, null);

        assertNotNull(xp.getRequires());
        FactorCondition gate = xp.getRequires().factorsOrEmpty()[0];
        assertEquals("hytale:mod_installed", gate.getFactor());
        assertEquals(MMO, gate.getParam());
        assertEquals(1.0, gate.getMin());
        assertNull(pie.getRequires(), "unauthored means the row is everywhere");

        mmoInstalled(false);
        assertEquals(MMO, xp.missingMod());
        assertFalse(xp.passesModGate());
        assertNull(pie.missingMod());
        assertTrue(pie.passesModGate());
        assertNotNull(pie.toSpec(), "an ungated row pays exactly as before");
    }

    @Test
    void aRowInheritsItsParentsRequiresLikeKindAndParams() throws IOException {
        RewardEntryAsset base = row(XP, null);
        RewardEntryAsset child = row("{ \"Params\": { \"Skill\": \"Farming\" } }", base);

        assertEquals("Mmo_Xp", child.getKind());
        assertNotNull(child.getRequires(), "the parent's block carries down");
        assertEquals(MMO, child.getRequires().factorsOrEmpty()[0].getParam());
        RewardEntryAsset ungated = row("{ \"Requires\": { \"Factors\": [] } }", base);
        assertNotNull(ungated.getRequires());
        assertEquals(0, ungated.getRequires().factorsOrEmpty().length, "a child's own block replaces the parent's");

        mmoInstalled(false);
        assertNull(child.toSpec());
        assertNotNull(ungated.toSpec());
    }

    @Test
    void withTheModAbsentAGatedRowYieldsNoSpecWhileItsUngatedSiblingPays() throws IOException {
        ContentRewardsAsset pay = rewards("{ \"Claim\": [ " + PIE + ", " + XP + " ], \"Auto\": [ " + XP + " ] }");

        mmoInstalled(false);

        assertEquals(List.of("Item"), kinds(pay.claim()));
        assertEquals(List.of(), kinds(pay.auto()));
        assertEquals(2, pay.claimEntries().length, "the entries as authored still hold the row");
        List<String> dropped = new ArrayList<>();
        ContentRewardsAsset.collectMissingMods(pay, dropped);
        assertEquals(List.of(MMO, MMO), dropped, "one entry per row left out, naming its mod");
    }

    @Test
    void withTheModPresentTheRowPaysAsWritten() throws IOException {
        ContentRewardsAsset pay = rewards("{ \"Claim\": [ " + PIE + ", " + XP + " ] }");

        mmoInstalled(true);

        assertEquals(List.of("Item", "Mmo_Xp"), kinds(pay.claim()));
        assertEquals("Cooking", pay.claim().get(1).param("Skill"));
        List<String> dropped = new ArrayList<>();
        ContentRewardsAsset.collectMissingMods(pay, dropped);
        assertEquals(List.of(), dropped);
    }

    @Test
    void cannotTellKeepsTheRow() throws IOException {
        ModGates.useProbeForTests(param -> null);

        assertNotNull(row(XP, null).toSpec(), "only a definite absence leaves a row out");
    }

    @Test
    void presentLeavesOutOnlyTheRowsGatedOnAMissingMod() throws IOException {
        RewardEntryAsset pie = row(PIE, null);
        RewardEntryAsset xp = row(XP, null);
        RewardEntryAsset blank = RewardEntryAsset.of(" ", Map.of());

        mmoInstalled(false);

        assertArrayEquals(new RewardEntryAsset[] {pie, null, blank},
                RewardEntryAsset.present(new RewardEntryAsset[] {pie, xp, null, blank}),
                "a null or blank row stays for the validator's own check");
        assertArrayEquals(new RewardEntryAsset[0], RewardEntryAsset.present(null));
        List<String> dropped = new ArrayList<>();
        RewardEntryAsset.collectMissingMods(new RewardEntryAsset[] {pie, xp, null, blank}, dropped);
        assertEquals(List.of(MMO), dropped);
    }

    @Test
    void theEditorListsTheLeafWithARowSentence() {
        String sentence = RewardEntryAsset.CODEC.toSchema(new SchemaContext()).getProperties().get("Requires")
                .getMarkdownDescription();

        assertNotNull(sentence);
        assertTrue(sentence.toLowerCase(Locale.ROOT).contains("row"), sentence);
        assertTrue(sentence.indexOf(0x2014) < 0, "no em-dash in authoring text");
    }
}
