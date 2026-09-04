package com.ziggfreed.common.encounter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The enforcement half of this module's dependency rule: the encounter module reaches the primitive
 * floor, the three vocabularies a payout needs (loot, presentation, world) and the pure scaling
 * fold, and NEVER a domain engine. A convenient reach-up (the progression runtime for a subject,
 * the instance layer for a round, the objectives runtime for a producer) is exactly what the seams
 * in {@code EncounterSeams} exist to avoid, because each of those modules is meant to reach THIS one
 * through its native events.
 *
 * <p>The ban is written on PACKAGES, not modules, because the library keeps one package root and
 * the two do not line up: {@code com.ziggfreed.common.instance.reward} is zc-loot and
 * {@code com.ziggfreed.common.instance.effect} is zc-effects, both allowed, while the rest of
 * {@code com.ziggfreed.common.instance} is zc-instance and forbidden. The scan is over import
 * lines, so prose naming a forbidden package in a javadoc never trips it.
 */
class EncounterEdgeTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** A package the encounter module may reach even though its prefix is otherwise forbidden. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "com.ziggfreed.common.instance.reward.",
            "com.ziggfreed.common.instance.effect.");

    /** The forbidden package roots, each the home of a domain engine this module must not call into. */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "com.ziggfreed.common.instance.",
            "com.ziggfreed.common.lobby.",
            "com.ziggfreed.common.party.",
            "com.ziggfreed.common.objectives.",
            "com.ziggfreed.common.progress.",
            "com.ziggfreed.common.quest.",
            "com.ziggfreed.common.achievement.",
            "com.ziggfreed.common.dialogue.",
            "com.ziggfreed.common.npc.",
            "com.ziggfreed.common.commerce.",
            "com.ziggfreed.common.board.",
            "com.ziggfreed.common.shop.",
            "com.ziggfreed.common.currency.",
            "com.ziggfreed.common.cost.",
            "com.ziggfreed.common.rotation.");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)");

    @Test
    void theEncounterModuleImportsNoDomainEngine() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT), "missing scan root: " + SOURCE_ROOT.toAbsolutePath());
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = IMPORT.matcher(lines.get(i));
                    if (m.find() && isForbidden(m.group(1))) {
                        hits.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), () -> "The encounter module reached into a domain engine (" + hits.size()
                + " hit(s)). Declare a seam in EncounterSeams for the wiring root to fill, or listen for the"
                + " module's own native events from the other side:\n" + String.join("\n", hits));
    }

    static boolean isForbidden(String imported) {
        for (String allowed : ALLOWED_PREFIXES) {
            if (imported.startsWith(allowed)) {
                return false;
            }
        }
        for (String forbidden : FORBIDDEN_PREFIXES) {
            if (imported.startsWith(forbidden)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theCarveOutsAreExactlyTheTwoLootAndEffectPackages() {
        assertTrue(isForbidden("com.ziggfreed.common.instance.preset.InstancePresetAsset"));
        assertTrue(isForbidden("com.ziggfreed.common.instance.metadata.InstanceRounds"));
        assertTrue(!isForbidden("com.ziggfreed.common.instance.reward.NativeLootService"));
        assertTrue(!isForbidden("com.ziggfreed.common.instance.effect.EffectBandLadder"));
        assertTrue(isForbidden("com.ziggfreed.common.progress.runtime.ProgressionRuntime"));
        assertTrue(isForbidden("com.ziggfreed.common.objectives.producer.ProgressDispatch"));
        assertTrue(!isForbidden("com.ziggfreed.common.loot.LootEngine"));
        assertTrue(!isForbidden("com.ziggfreed.common.feedback.moment.FeedbackEngine"));
        assertTrue(!isForbidden("com.ziggfreed.common.world.WorldSelector"));
    }
}
