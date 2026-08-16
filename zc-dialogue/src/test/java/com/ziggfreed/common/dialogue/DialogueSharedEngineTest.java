package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.quest.QuestStateReader;

/**
 * The server has ONE dialogue engine and every mod registers into it, so two mods' vocabularies mix
 * in one conversation instead of each running only its own half.
 *
 * <p>Two mods are simulated the only way a unit test can: two independent registration calls, each
 * with its own {@code Type} id, its own action class and its own handler, exactly as two plugins'
 * {@code setup()} methods would make them. What is asserted is that ONE engine can then read AND run
 * both, which is the property a private engine per consumer could never have.
 *
 * <p>The other half is the one thing stacking cannot absorb: two mods contributing behaviour for the
 * SAME action or condition class, where only one can run. That is asserted through a captured warn
 * sink ({@link DialogueEngine#resetSharedForTests(java.util.function.Consumer)}), because the point
 * is not only which registration survived but that a server owner is TOLD which one did.
 */
class DialogueSharedEngineTest {

    /** Both the schema and the shared engine are process-wide; start every test from a clean pair. */
    @BeforeEach
    void resetSharedVocabulary() {
        DialogueTestSupport.reset();
    }

    // ==================== two mods, one engine ====================

    @Test
    void twoModsActionsBothRunOnTheOneSharedEngine() {
        AtomicInteger modAFired = new AtomicInteger();
        AtomicInteger modBFired = new AtomicInteger();

        DialogueEngine.registerShared("ModA", DialogueActionType.of("ModA_Ping", ModAPing.class, ModAPing.CODEC,
                (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> modAFired.incrementAndGet()));
        DialogueEngine.registerShared("ModB", DialogueActionType.of("ModB_Pong", ModBPong.class, ModBPong.CODEC,
                (ModBPong a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> modBFired.incrementAndGet()));

        DialogueEngine engine = DialogueEngine.shared();
        assertTrue(engine.executor().handles(ModAPing.class), "the first mod's action must be runnable");
        assertTrue(engine.executor().handles(ModBPong.class), "and so must the second mod's");

        engine.executor().execute(List.of(new ModAPing(), new ModBPong()),
                new TestDialogueContext(new NpcDialogue()));

        assertEquals(1, modAFired.get());
        assertEquals(1, modBFired.get(), "both mods' handlers must reach the same executor");
    }

    @Test
    void oneAuthoredOptionMayCarryBothModsActionsAndBothRun() {
        AtomicInteger modAFired = new AtomicInteger();
        AtomicInteger modBFired = new AtomicInteger();

        DialogueEngine.registerShared("ModA", DialogueActionType.of("ModA_Ping", ModAPing.class, ModAPing.CODEC,
                (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> modAFired.incrementAndGet()));
        DialogueEngine.registerShared("ModB", DialogueConditionType.of("ModB_Always", ModBAlways.class,
                ModBAlways.CODEC, (ModBAlways c, DialogueContext ctx) -> true));
        DialogueEngine.registerShared("ModB", DialogueActionType.of("ModB_Pong", ModBPong.class, ModBPong.CODEC,
                (ModBPong a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> modBFired.incrementAndGet()));

        DialogueEngine engine = DialogueEngine.shared();
        NpcDialogue dialogue = engine.decode("mixed", """
                {
                  "Nodes": {
                    "greet": {
                      "TextKey": "greet.text",
                      "Options": [
                        {
                          "LabelKey": "greet.both",
                          "Conditions": [ { "Type": "ModB_Always" } ],
                          "Actions": [ { "Type": "ModA_Ping" }, { "Type": "ModB_Pong" } ]
                        }
                      ]
                    }
                  }
                }
                """);
        assertNotNull(dialogue, "one schema reads both mods' types");

        DialogueOption option = dialogue.getNode("greet").getOptions().get(0);
        TestDialogueContext ctx = new TestDialogueContext(dialogue);
        assertTrue(engine.conditionsPass(option.getConditions(), ctx),
                "the second mod's condition must be answerable by the one engine");

        engine.executor().execute(option.getActions(), ctx);

        assertEquals(1, modAFired.get());
        assertEquals(1, modBFired.get(),
                "one authored option carrying two mods' actions must run both");
    }

    @Test
    void aLateRegistrationLandsInTheEngineACallerAlreadyHolds() {
        DialogueEngine held = DialogueEngine.shared();

        AtomicInteger fired = new AtomicInteger();
        DialogueEngine.registerShared("ModB", DialogueActionType.of("ModB_Pong", ModBPong.class, ModBPong.CODEC,
                (ModBPong a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> fired.incrementAndGet()));

        assertSame(held, DialogueEngine.shared(),
                "there is one engine, so a consumer that captured it early still holds the live one");
        held.executor().execute(List.of(new ModBPong()), new TestDialogueContext(new NpcDialogue()));
        assertEquals(1, fired.get(),
                "a mod registering after another mod captured the engine must still be runnable through it");
    }

    @Test
    void theGenericVocabularyIsSeededOnceRatherThanPerRegistration() {
        List<String> reported = new ArrayList<>();
        DialogueEngine.resetSharedForTests(reported::add);

        DialogueEngine.registerShared("ModA", DialogueActionType.of("ModA_Ping", ModAPing.class, ModAPing.CODEC,
                (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { }));
        DialogueEngine.registerShared("ModB", DialogueActionType.of("ModB_Pong", ModBPong.class, ModBPong.CODEC,
                (ModBPong a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { }));

        DialogueEngine engine = DialogueEngine.shared();
        assertTrue(engine.executor().handles(DialogueAction.Goto.class),
                "the generics are there for everyone");
        assertTrue(engine.evaluates(DialogueCondition.AnyOf.class),
                "including the combinators, which are wired to the engine rather than seeded as types");

        // And seeded ONCE, which silence is the proof of: a seeding pass builds a fresh handler
        // instance for every generic, and a class belongs to its first contributor, so a SECOND pass
        // over this engine would arrive as a rival contributor for Goto and report itself here. The
        // other case - re-seeding by assembling a whole second engine - is what
        // aLateRegistrationLandsInTheEngineACallerAlreadyHolds rules out.
        assertTrue(reported.isEmpty(),
                "the generics must be seeded once for everybody, not once per registration: " + reported);
    }

    // ==================== the one collision stacking cannot absorb ====================

    @Test
    void aSecondContributorForAnActionClassIsRefusedAndReportedOnceNamingBothMods() {
        List<String> reported = new ArrayList<>();
        DialogueEngine.resetSharedForTests(reported::add);

        AtomicInteger holderFired = new AtomicInteger();
        AtomicInteger refusedFired = new AtomicInteger();

        assertTrue(DialogueEngine.registerShared("ModA", DialogueActionType.of("ModA_Ping",
                        ModAPing.class, ModAPing.CODEC,
                        (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                holderFired.incrementAndGet())),
                "the first contributor for an action class claims it");
        assertFalse(DialogueEngine.registerShared("ModB", DialogueActionType.of("ModB_Ping",
                        ModAPing.class, ModAPing.CODEC,
                        (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                refusedFired.incrementAndGet())),
                "only one handler can run for a class, so a second mod's is refused rather than taken");
        assertFalse(DialogueEngine.registerShared("ModC", DialogueActionType.of("ModC_Ping",
                        ModAPing.class, ModAPing.CODEC,
                        (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                refusedFired.incrementAndGet())),
                "and so is a third");

        DialogueEngine.shared().executor().execute(List.of(new ModAPing()),
                new TestDialogueContext(new NpcDialogue()));

        assertEquals(1, holderFired.get(), "the handler that claimed the class is the one that runs");
        assertEquals(0, refusedFired.get(), "and a refused one never runs at all");

        assertEquals(1, reported.size(),
                "a refused claim is worth exactly one line however often it repeats: " + reported);
        assertTrue(reported.get(0).contains("ModA") && reported.get(0).contains("ModB"),
                "and that line names the mod holding the class and the one that asked: " + reported.get(0));
    }

    @Test
    void aSecondContributorForAConditionClassIsRefusedAndTheFirstAnswerStands() {
        List<String> reported = new ArrayList<>();
        DialogueEngine.resetSharedForTests(reported::add);

        assertTrue(DialogueEngine.registerShared("ModA", DialogueConditionType.of("ModA_Always",
                        ModBAlways.class, ModBAlways.CODEC, (ModBAlways c, DialogueContext ctx) -> true)),
                "the first contributor for a condition class claims it");
        assertFalse(DialogueEngine.registerShared("ModB", DialogueConditionType.of("ModB_Always",
                        ModBAlways.class, ModBAlways.CODEC, (ModBAlways c, DialogueContext ctx) -> false)),
                "a second evaluator would be a second answer to one authored line");

        assertTrue(DialogueEngine.shared().conditionsPass(List.of(new ModBAlways()),
                        new TestDialogueContext(new NpcDialogue())),
                "the refused evaluator must not be the one answering");
        assertEquals(1, reported.size(), "and the refusal is reported once: " + reported);
        assertTrue(reported.get(0).contains("ModA") && reported.get(0).contains("ModB"), reported.get(0));
    }

    // ==================== the two singular seams ====================

    @Test
    void theFirstQuestRuntimeHoldsTheSlotAndASecondOneIsRefused() {
        DialogueQuests first = new StubQuests();
        DialogueQuests second = new StubQuests();

        assertTrue(DialogueEngine.installQuests("ModA", first), "the first install claims the slot");
        assertFalse(DialogueEngine.installQuests("ModB", second),
                "a second runtime would be a second version of one player's quest state");

        assertSame(first, DialogueEngine.shared().quests(),
                "and the refusal must not silently replace what the first mod installed");
    }

    @Test
    void reInstallingTheSameQuestRuntimeIsIdempotent() {
        DialogueQuests only = new StubQuests();

        assertTrue(DialogueEngine.installQuests("ModA", only));
        assertFalse(DialogueEngine.installQuests("ModA", only),
                "a mod re-running its own setup offers the SAME instance and simply keeps the slot");

        assertSame(only, DialogueEngine.shared().quests());
    }

    @Test
    void withNoQuestRuntimeInstalledTheQuestVocabularyRefusesRatherThanThrowing() {
        assertSame(DialogueQuests.NONE, DialogueEngine.shared().quests(),
                "a server with no quest system reads NOT_STARTED and refuses every hand-in");
    }

    // ==================== the isolated builder is untouched ====================

    @Test
    void aPrivateBuilderEngineStillHasItsOwnVocabulary() {
        DialogueEngine.registerShared("ModA", DialogueActionType.of("ModA_Ping", ModAPing.class, ModAPing.CODEC,
                (ModAPing a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { }));

        DialogueEngine isolated = DialogueEngine.builder().warn(m -> { }).build();

        assertFalse(isolated.executor().handles(ModAPing.class),
                "a sandbox engine runs only what its own builder was given");
        assertTrue(DialogueEngine.shared().executor().handles(ModAPing.class),
                "while the shared one still runs everything registered into it");
    }

    // ==================== stand-ins ====================

    /** One mod's action. */
    public static final class ModAPing extends DialogueAction {
        public static final BuilderCodec<ModAPing> CODEC =
                BuilderCodec.builder(ModAPing.class, ModAPing::new).build();
    }

    /** Another mod's action, a different class under a different {@code Type}. */
    public static final class ModBPong extends DialogueAction {
        public static final BuilderCodec<ModBPong> CODEC =
                BuilderCodec.builder(ModBPong.class, ModBPong::new).build();
    }

    /** Another mod's condition, so an option can mix a condition and actions across mods. */
    public static final class ModBAlways extends DialogueCondition {
        public static final BuilderCodec<ModBAlways> CODEC =
                BuilderCodec.builder(ModBAlways.class, ModBAlways::new).build();
    }

    /** A distinct quest runtime instance; what it answers is beside the point here. */
    private static final class StubQuests implements DialogueQuests {

        @Override
        @Nonnull
        public QuestStateReader reader() {
            return DialogueQuests.NONE.reader();
        }
    }
}
