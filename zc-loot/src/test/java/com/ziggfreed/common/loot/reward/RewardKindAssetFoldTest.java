package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * What happens when authored kinds meet a registry that already has some.
 *
 * <p>The shadowing rule is the load-bearing one: a file wins over Java, and it is never silent. Both
 * halves are asserted here, because either one on its own is a bug - a file that loses is an owner
 * override that does nothing, and a file that wins quietly is a payout that changed behaviour with
 * nothing anywhere saying so.
 */
class RewardKindAssetFoldTest {

    static RewardKindAsset kind(String id, String command) {
        return RewardKindAsset.of(id, Map.of("Amount", RewardKindAsset.Param.of(true, null)), command);
    }

    /** A handler that records nothing and does nothing - a stand-in for a mod's Java registration. */
    static final class JavaHandler implements RewardHandler {
        @Override
        public void grant(RewardSpec spec, Subject subject) {
        }
    }

    // ==================== registering ====================

    @Nested
    class Registering {

        @Test
        void anAuthoredKindBecomesAPayableKind() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            CommandRewardKindTest.Recorder recorder = new CommandRewardKindTest.Recorder();

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds,
                    List.of(kind("Mmo_Xp", "mmoawardxp {player} {Amount}")), recorder, null);

            assertEquals(List.of("Mmo_Xp"), result.registered());
            assertTrue(kinds.isRegistered("Mmo_Xp"));
            assertTrue(kinds.isRegistered("mmo_xp"), "a kind is matched however a reward spells it");
            assertInstanceOf(CommandRewardKind.class, kinds.handler("MMO_XP"));
        }

        @Test
        void theRegisteredHandlerRunsTheAuthoredCommand() throws Exception {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            CommandRewardKindTest.Recorder recorder = new CommandRewardKindTest.Recorder();
            RewardKindFold.foldInto(kinds, List.of(kind("Mmo_Xp", "mmoawardxp {player} {Amount}")),
                    recorder, null);

            kinds.handler("Mmo_Xp").grant(RewardSpec.of("Mmo_Xp", "Amount", "500"),
                    CommandRewardKindTest.player(), "quest:test");

            assertEquals("mmoawardxp Bob 500", recorder.only());
        }

        @Test
        void aKindWithNoCommandIsSkippedRatherThanRegisteredAsADud() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            List<String> warnings = new ArrayList<>();

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds,
                    List.of(kind("Mmo_Xp", "  ")), new CommandRewardKindTest.Recorder(), warnings::add);

            assertEquals(List.of("Mmo_Xp"), result.skipped());
            assertTrue(result.registered().isEmpty());
            assertFalse(kinds.isRegistered("Mmo_Xp"));
            assertEquals(1, warnings.size());
        }

        @Test
        void aCommandLessFileOverAJavaKindIsQuietDecoration() {
            // The legitimate command-less shape: the file exists only to give the Java-registered
            // kind an authored Presentation, so the payout must stay Java's and nothing warns.
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register("Mmo_Xp", "mymod", (spec, subject) -> { });
            List<String> warnings = new ArrayList<>();

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds,
                    List.of(kind("Mmo_Xp", "  ")), new CommandRewardKindTest.Recorder(), warnings::add);

            assertEquals(List.of("Mmo_Xp"), result.skipped());
            assertFalse(kinds.handler("Mmo_Xp") instanceof CommandRewardKind,
                    "the Java handler keeps the payout");
            assertTrue(warnings.isEmpty(), "decoration is a working shape, not a dud to warn about");
        }

        @Test
        void nothingAuthoredMeansNothingHappens() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds, List.of(),
                    new CommandRewardKindTest.Recorder(), null);

            assertEquals(RewardKindFold.Result.EMPTY, result);
            assertTrue(kinds.ids().isEmpty());
        }
    }

    // ==================== shadowing ====================

    @Nested
    class Shadowing {

        @Test
        void aFileWinsOverAJavaRegistrationOfTheSameId() throws Exception {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register(LootRewardKinds.KIND_ITEM, "somemod", new JavaHandler());
            CommandRewardKindTest.Recorder recorder = new CommandRewardKindTest.Recorder();

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds,
                    List.of(kind("Item", "give {player} Coin_Gold {Amount}")), recorder, null);

            assertEquals(List.of("Item"), result.shadowed());
            assertTrue(result.anyShadowed());
            assertInstanceOf(CommandRewardKind.class, kinds.handler(LootRewardKinds.KIND_ITEM),
                    "an owner's file overrules the mod that registered the kind");

            kinds.handler("item").grant(RewardSpec.of("item", "Amount", "3"),
                    CommandRewardKindTest.player(), "quest:test");
            assertEquals("give Bob Coin_Gold --quantity=3", recorder.only());
        }

        @Test
        void aShadowWarnsOnceAndSaysWhatWasGivenUp() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register(LootRewardKinds.KIND_ITEM, "somemod", new JavaHandler());
            List<String> warnings = new ArrayList<>();

            RewardKindFold.foldInto(kinds, List.of(kind("Item", "give {player} Coin_Gold {Amount}")),
                    new CommandRewardKindTest.Recorder(), warnings::add);

            assertEquals(1, warnings.size(), "one warning per shadowed kind, not one per fold step");
            String warning = warnings.get(0);
            assertTrue(warning.contains("Item.json"), "the warning names the file to go and look at");
            assertTrue(warning.contains("inventory"), "and says which engine service the owner gave up");
        }

        @Test
        void aKindNothingElseClaimsIsNotReportedAsAShadow() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            kinds.register(LootRewardKinds.KIND_ITEM, "somemod", new JavaHandler());
            List<String> warnings = new ArrayList<>();

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds,
                    List.of(kind("Mmo_Xp", "mmoawardxp {player} {Amount}")),
                    new CommandRewardKindTest.Recorder(), warnings::add);

            assertTrue(result.shadowed().isEmpty());
            assertTrue(warnings.isEmpty());
            assertInstanceOf(JavaHandler.class, kinds.handler(LootRewardKinds.KIND_ITEM));
        }

        @Test
        void aBlankKindCannotShadowAWorkingOne() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            JavaHandler java = new JavaHandler();
            kinds.register(LootRewardKinds.KIND_ITEM, "somemod", java);

            RewardKindFold.Result result = RewardKindFold.foldInto(kinds, List.of(kind("Item", null)),
                    new CommandRewardKindTest.Recorder(), null);

            assertTrue(result.shadowed().isEmpty());
            assertSame(java, kinds.handler(LootRewardKinds.KIND_ITEM),
                    "an empty file is far more often a mistake than an intention to remove a payout");
        }

        /**
         * An asset re-import folds the whole catalogue again. The second pass finds its OWN handlers
         * in the registry, and counting those as shadows would tell an owner that every authored kind
         * had just taken something over - a warning per file, for nothing that happened.
         */
        @Test
        void aSecondFoldOfTheSameFilesShadowsNothing() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            List<String> warnings = new ArrayList<>();
            List<RewardKindAsset> authored = List.of(kind("Mmo_Xp", "mmoawardxp {player} {Amount}"));
            RewardKindFold.foldInto(kinds, authored, new CommandRewardKindTest.Recorder(), warnings::add);

            RewardKindFold.Result again = RewardKindFold.foldInto(kinds, authored,
                    new CommandRewardKindTest.Recorder(), warnings::add);

            assertEquals(List.of("Mmo_Xp"), again.registered(), "the re-import still re-registers");
            assertTrue(again.shadowed().isEmpty());
            assertTrue(warnings.isEmpty());
        }

        @Test
        void everyShadowReachesTheContentAuditAsWell() {
            List<Finding> findings = RewardKindValidator.shadowed(List.of("Item"));

            assertEquals(1, findings.size());
            assertEquals(RewardKindValidator.JAVA_BACKED_KIND_SHADOWED, findings.get(0).code());
            assertEquals(Severity.INFO, findings.get(0).severity(),
                    "shadowing is allowed, so it is never a problem count - only never invisible");
            assertEquals(RewardKindValidator.DOMAIN, findings.get(0).domain());
        }
    }

    // ==================== layering ====================

    /**
     * The kind table layers like every other keyed asset type, so a pack retunes a jar default and a
     * server owner overrules both. Asserted on the shared config rather than a hand-rolled map,
     * because the fold reads whatever that config resolved.
     */
    @Nested
    class Layering {

        @BeforeEach
        @AfterEach
        void resetTheSharedTable() {
            RewardKindConfig.getInstance().loadDefaults(Map.of());
            RewardKindConfig.getInstance().mergePackLayer(Map.of());
            RewardKindConfig.getInstance().mergeOwnerLayer(Map.of());
        }

        @Test
        void packBeatsDefaultAndOwnerBeatsBoth() {
            RewardKindConfig config = RewardKindConfig.getInstance();
            config.loadDefaults(Map.of("mmo_xp", kind("Mmo_Xp", "default {player} {Amount}")));
            assertEquals("default {player} {Amount}", config.resolve("Mmo_Xp").getCommand());

            config.mergePackLayer(Map.of("mmo_xp", kind("Mmo_Xp", "pack {player} {Amount}")));
            assertEquals("pack {player} {Amount}", config.resolve("Mmo_Xp").getCommand());

            config.mergeOwnerLayer(Map.of("mmo_xp", kind("Mmo_Xp", "owner {player} {Amount}")));
            assertEquals("owner {player} {Amount}", config.resolve("Mmo_Xp").getCommand());
        }

        @Test
        void aKindNoLayerCarriesSimplyIsNotThere() {
            assertNull(RewardKindConfig.getInstance().resolve("Nobody_Ships_This"));
        }

        @Test
        void theFoldRegistersWhateverTheLayersResolvedTo() throws Exception {
            RewardKindConfig config = RewardKindConfig.getInstance();
            config.loadDefaults(Map.of("mmo_xp", kind("Mmo_Xp", "default {player} {Amount}")));
            config.mergeOwnerLayer(Map.of("mmo_xp", kind("Mmo_Xp", "owner {player} {Amount}")));

            RewardKindRegistry kinds = new RewardKindRegistry("test");
            CommandRewardKindTest.Recorder recorder = new CommandRewardKindTest.Recorder();
            RewardKindFold.foldInto(kinds, config.all().values(), recorder, null);

            RewardHandler handler = kinds.handler("Mmo_Xp");
            assertNotNull(handler);
            handler.grant(RewardSpec.of("Mmo_Xp", "Amount", "5"), CommandRewardKindTest.player(),
                    "quest:test");
            assertEquals("owner Bob 5", recorder.only());
        }
    }
}
