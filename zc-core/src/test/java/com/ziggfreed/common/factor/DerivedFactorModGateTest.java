package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.validation.Finding;

/**
 * A {@code Factors} file's top-level {@code Requires} is the mod gate every keyed store reads: a file gated
 * on a missing mod is refused at the fold, and the factor it would have defined is absent on purpose, so
 * nothing reports it as unknown. Its other conditions are never asked and are reported as ignored.
 */
class DerivedFactorModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";

    @AfterEach
    void restore() {
        ModGates.useProbeForTests(null);
        DerivedFactorConfig.getInstance().knownFactors(null);
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
    }

    /** The always-on audit asks the installed vocabularies, so a term naming a factor registered in code is known. */
    @Test
    void theAlwaysOnAuditAsksTheInstalledVocabulariesBeforeCallingATermUnknown() throws IOException {
        DerivedFactorAsset skill = decode("Yourpack_Skill", "{ \"Formula\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:stat\", \"Param\": \"Health\" }, { \"Factor\": \"yourmod:nothing\" } ] } }");
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of("yourpack_skill", skill));
        DerivedFactorConfig.getInstance().knownFactors("hytale:stat"::equals);

        List<Finding> unknown = DerivedFactorConfig.getInstance().audit().stream()
                .filter(f -> "UNKNOWN_FACTOR".equals(f.code())).toList();
        assertEquals(1, unknown.size(), "only the term nothing registers: " + unknown);
        assertTrue(unknown.get(0).message().contains("yourmod:nothing"), unknown.get(0).message());
    }

    private static DerivedFactorAsset decode(String id, String json) throws IOException {
        return DerivedFactorAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, id, null)));
    }

    @Test
    void aFactorsFileGatedOnAMissingModIsRefusedAndAbsentWithNoFinding() throws IOException {
        ModGates.useProbeForTests(param -> MMO.equals(param == null ? null : param.trim()) ? 0.0 : 1.0);
        DerivedFactorAsset skill = decode("Christmas_Tomas_Skill", "{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:mod_installed\", \"Param\": \"" + MMO + "\", \"Min\": 1 } ] },"
                + " \"Formula\": { \"Factors\": [ { \"Factor\": \"hytale:stat\", \"Param\": \"MMO_Level_CRAFTING\","
                + " \"Weight\": 0.05 } ], \"Clamp\": { \"Max\": 1 } } }");
        DerivedFactorAsset key = decode("Christmas_Tomas_Key", "{ \"Formula\": { \"Factors\": ["
                + " { \"Factor\": \"Christmas_Tomas_Skill\" } ], \"Clamp\": { \"Max\": 1 } } }");

        assertEquals(MMO, PresenceRequiresCodec.missingMod(skill.getRequires()));
        DerivedFactorConfig.getInstance().mergePackLayer(new ModGateFold<>(DerivedFactorConfig.MOD_GATE_STORE,
                Map.of("christmas_tomas_key", key), Map.of("christmas_tomas_skill", MMO)));

        assertTrue(DerivedFactorConfig.getInstance().refusedByModGate("Christmas_Tomas_Skill"));
        assertFalse(DerivedFactorConfig.getInstance().refusedByModGate("Christmas_Tomas_Key"));
        assertNull(new FactorRegistry().resolve("Christmas_Tomas_Skill", FactorContext.builder().build()),
                "a condition on it fails closed");
        List<Finding> findings = DerivedFactorConfig.getInstance().audit(id -> false);
        assertTrue(findings.stream().noneMatch(f -> "UNKNOWN_FACTOR".equals(f.code())),
                "a term naming a factor the mod gate left out is absent by design: " + findings);
    }

    @Test
    void anyOtherConditionInAFactorsFilesRequiresIsReportedAsIgnored() throws IOException {
        DerivedFactorAsset odd = decode("Odd", "{ \"Requires\": { \"Factors\": ["
                + " { \"Factor\": \"hytale:stat\", \"Param\": \"Health\", \"Min\": 10 } ] },"
                + " \"Formula\": { \"Base\": 1 } }");

        List<Finding> findings = DerivedFactorValidator.validateAssets(Map.of("odd", odd), null);
        assertTrue(findings.stream().anyMatch(f -> DerivedFactorValidator.REQUIRES_CONDITION_IGNORED.equals(f.code())),
                "only mod presence is read there: " + findings);
    }
}
