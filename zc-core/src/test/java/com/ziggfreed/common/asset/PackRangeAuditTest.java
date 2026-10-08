package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;

/**
 * The pack range check: one line per loaded pack whose declared ziggfreed-common range the running
 * version fails, an optional range checked too and said so, and nothing for a satisfied or wildcard
 * range; and the manifest read that feeds it, both maps and this library's id alone.
 */
class PackRangeAuditTest {

    private static final String SELF = "Ziggfreed:ZiggfreedCommon";

    @Test
    void aRangeTheRunningVersionFailsIsOneWarningNamingThePack() {
        List<String> lines = PackRangeAudit.failures(SELF, Semver.fromString("2.3.0"), List.of(
                new PackRangeAudit.Declared("Ziggfreed:SeasonsOfOrbis", SemverRange.fromString(">=2.4.0"), false),
                new PackRangeAudit.Declared("Ziggfreed:MMOSkillBountyContractsPack", SemverRange.fromString(">=2.2.0"),
                        false)));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("Ziggfreed:SeasonsOfOrbis"), lines.get(0));
        assertTrue(lines.get(0).contains("2.4.0"), "the range it asked for");
        assertTrue(lines.get(0).contains("2.3.0"), "and the version it got");
    }

    @Test
    void anOptionalRangeIsCheckedTooAndSaysSo() {
        List<String> lines = PackRangeAudit.failures(SELF, Semver.fromString("2.3.0"), List.of(
                new PackRangeAudit.Declared("Ziggfreed:SomeCompanion", SemverRange.fromString(">=3.0.0"), true)));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("optional"), "the engine checks an optional range nowhere");
    }

    @Test
    void aSatisfiedOrWildcardRangeSaysNothing() {
        assertTrue(PackRangeAudit.failures(SELF, Semver.fromString("2.3.0"), List.of(
                new PackRangeAudit.Declared("A:Pack", SemverRange.fromString("*"), false),
                new PackRangeAudit.Declared("B:Pack", SemverRange.fromString(">=2.3.0"), true))).isEmpty());
    }

    /** A real {@code PluginManifest} builds a {@code HytaleLogger} at class init, so this one runs under the engine's log manager. */
    @Test
    @Tag("engine-items")
    void eachLoadedPacksRequiredAndOptionalRangeForThisLibraryIsReadAndNothingElse() {
        PluginIdentifier other = new PluginIdentifier("Ziggfreed", "MMOSkillTree");
        AssetPack both = pack("Ziggfreed:SeasonsOfOrbis",
                Map.of(new PluginIdentifier("Ziggfreed", "ZiggfreedCommon"), SemverRange.fromString(">=2.4.0"),
                        other, SemverRange.fromString("*")),
                Map.of(new PluginIdentifier("Ziggfreed", "ZiggfreedCommon"), SemverRange.fromString(">=3.0.0")));
        AssetPack unrelated = pack("A:Pack", Map.of(other, SemverRange.fromString(">=1.0.0")), Map.of());
        AssetPack noManifest = new AssetPack(null, "B:Pack", Path.of("."), null, true, null,
                AssetPack.PackSource.MODS);

        List<PackRangeAudit.Declared> declared = PackRangeAudit.declaredFor(
                new PluginIdentifier("Ziggfreed", "ZiggfreedCommon"), Arrays.asList(both, unrelated, noManifest, null));

        assertEquals(2, declared.size(), declared.toString());
        assertEquals("Ziggfreed:SeasonsOfOrbis", declared.get(0).pack());
        assertFalse(declared.get(0).optional(), "the required range first");
        assertEquals(">=2.4.0", declared.get(0).range().toString());
        assertTrue(declared.get(1).optional(), "then the optional one, which the engine never reads");
        assertEquals(">=3.0.0", declared.get(1).range().toString());
    }

    private static AssetPack pack(String name, Map<PluginIdentifier, SemverRange> required,
            Map<PluginIdentifier, SemverRange> optional) {
        String[] id = name.split(":");
        PluginManifest manifest = new PluginManifest(id[0], id[1], Semver.fromString("1.0.0"), null, List.of(), null,
                null, SemverRange.fromString("*"), required, optional, Map.of(), List.of(), false);
        return new AssetPack(null, name, Path.of("."), null, true, manifest, AssetPack.PackSource.MODS);
    }
}
