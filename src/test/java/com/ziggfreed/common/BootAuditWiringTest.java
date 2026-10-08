package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.asset.AlmanacValidator;
import com.ziggfreed.common.calendar.asset.CalendarEventValidator;
import com.ziggfreed.common.commerce.fold.CommerceAudit;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.loot.LootAudit;
import com.ziggfreed.common.npc.NpcIdentityConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.objectives.title.TitleValidator;
import com.ziggfreed.common.stats.gearset.GearSetValidator;

/**
 * The boot audit's wiring: the root's {@code registerBootChecks()}, called from {@code setup()}, keeps
 * the pack range warning as its first registration and hangs one {@code BootEvent} listener that hands
 * zc-core's {@code BootAudit} every pass in order, the calendar, title, Almanac, derived-factor,
 * NPC-identity and NPC-placement audits included, each under its owner's own label. Two passes take a
 * first-join audit's one run ({@code claimLateFindings}), so nothing prints twice; the title pass is the
 * walk that builds the progression runtime ({@code auditForcingBuild}), so a boot that nothing has built
 * yet still has its Title rewards checked. A source scan, because the plugin class builds the engine's
 * logger at class init, which this test JVM cannot stand up, and the passes themselves run only on a
 * booted server; {@code BootAuditTest} drives what the passes' findings become, and each owner's own test
 * drives its entry point.
 */
class BootAuditWiringTest {

    private static final Path PLUGIN = Path.of("src", "main", "java", "com", "ziggfreed", "common",
            "ZiggfreedCommonPlugin.java");

    /** Every pass the boot audit runs, in the order the root hands them over, whitespace aside. */
    private static final List<String> PASSES = List.of(
            "new BootAudit.Pass(CommerceAudit.LOG_LABEL, CommerceAudit::claimLateFindings)",
            "new BootAudit.Pass(LootAudit.LOG_LABEL, LootAudit::auditAll)",
            "new BootAudit.Pass(GearSetValidator.LOG_LABEL, GearSetValidator::audit)",
            "new BootAudit.Pass(CalendarEventValidator.LOG_LABEL, CalendarEventValidator::audit)",
            "new BootAudit.Pass(TitleValidator.LOG_LABEL, TitleValidator::auditForcingBuild)",
            "new BootAudit.Pass(AlmanacValidator.LOG_LABEL, AlmanacValidator::audit)",
            "new BootAudit.Pass(DerivedFactorConfig.LOG_LABEL, () -> DerivedFactorConfig.getInstance().audit())",
            "new BootAudit.Pass(NpcIdentityConfig.LOG_LABEL, () -> NpcIdentityConfig.getInstance().audit())",
            "new BootAudit.Pass(NpcPlacementConfig.LOG_LABEL, () -> NpcPlacementConfig.getInstance().claimLateFindings())");

    private static final String PACK_RANGE =
            "getEventRegistry().register(BootEvent.class, event -> PackRangeAudit.warnOnce(getManifest()));";

    private static final String BOOT_AUDIT =
            "getEventRegistry().register(BootEvent.class, event -> BootAudit.runIfAsked(List.of(";

    @Test
    void setupRegistersTheBootChecks() throws IOException {
        String setup = squash(methodBody(read(PLUGIN), "protected void setup()"));
        assertTrue(setup.contains("registerBootChecks();"), "setup() must call registerBootChecks()");
    }

    @Test
    void thePackRangeWarningStaysTheFirstStatementOfTheTry() throws IOException {
        String body = squash(methodBody(read(PLUGIN), "private void registerBootChecks()"));
        assertTrue(body.startsWith("{try{" + squash(PACK_RANGE) + squash(BOOT_AUDIT)),
                () -> "registerBootChecks() opens its try with the pack range warning, then the boot audit: " + body);
    }

    @Test
    void theBootEventHandsBootAuditEveryPassInOrder() throws IOException {
        String body = squash(methodBody(read(PLUGIN), "private void registerBootChecks()"));
        int at = body.indexOf(squash(BOOT_AUDIT));
        assertTrue(at >= 0, () -> "registerBootChecks() must hand its passes to BootAudit.runIfAsked: " + body);
        for (String pass : PASSES) {
            int next = body.indexOf(squash(pass), at);
            assertTrue(next >= 0, () -> "the boot audit must run, after the passes before it, " + pass + ": " + body);
            at = next;
        }
        assertEquals(PASSES.size(), body.split("newBootAudit\\.Pass\\(", -1).length - 1,
                "every pass the root hands over is listed here, and every one listed is handed over");
    }

    /** What each pass's lines carry, so a reader of the boot log tells the passes apart by pattern. */
    @Test
    void everyPassCarriesItsOwnersLabel() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("CommerceAudit", CommerceAudit.LOG_LABEL);
        labels.put("LootAudit", LootAudit.LOG_LABEL);
        labels.put("GearSetValidator", GearSetValidator.LOG_LABEL);
        labels.put("CalendarEventValidator", CalendarEventValidator.LOG_LABEL);
        labels.put("TitleValidator", TitleValidator.LOG_LABEL);
        labels.put("AlmanacValidator", AlmanacValidator.LOG_LABEL);
        labels.put("DerivedFactorConfig", DerivedFactorConfig.LOG_LABEL);
        labels.put("NpcIdentityConfig", NpcIdentityConfig.LOG_LABEL);
        labels.put("NpcPlacementConfig", NpcPlacementConfig.LOG_LABEL);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("CommerceAudit", "[commerce] content");
        expected.put("LootAudit", "[loot] audit");
        expected.put("GearSetValidator", "[gearset] audit");
        expected.put("CalendarEventValidator", "[calendar] audit");
        expected.put("TitleValidator", "[title] audit");
        expected.put("AlmanacValidator", "[almanac] audit");
        expected.put("DerivedFactorConfig", "[factor] DerivedFactor");
        expected.put("NpcIdentityConfig", "[identity]");
        expected.put("NpcPlacementConfig", "[placement]");
        assertEquals(expected, labels);
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The text with every run of whitespace removed, so a rewrap never fails the scan. */
    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** The braces-matched body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "no " + signature + " in " + PLUGIN);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        return source.substring(open);
    }
}
