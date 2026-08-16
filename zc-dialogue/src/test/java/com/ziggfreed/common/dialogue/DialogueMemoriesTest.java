package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelector;

/**
 * Declared {@code Memories}: the declaration shape and its inheritance through a native
 * {@code Parent}, the bare-name use sites ({@code Remember}/{@code Forget} +
 * {@code Remembered}/{@code NotRemembered}), and the two lifetime/scope axes - a
 * {@code ResetWithQuest} memory filed where a quest reset clears it, and a world-family memory
 * that does not exist outside its family. Pure: no server involved.
 */
class DialogueMemoriesTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private final List<String> warnings = new ArrayList<>();

    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(warnings::add).build();
    }

    /** A guide who remembers one thing, writes it on one option and reads it on another. */
    private static NpcDialogue guide(DialogueEngine engine) {
        return engine.decode("guide", "{"
                + "\"Memories\":{\"helped_refugees\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"camp\"}]},"
                + "\"Nodes\":{\"camp\":{\"Options\":["
                + "{\"LabelKey\":\"opt.help\",\"Actions\":[{\"Type\":\"Remember\","
                + "\"Memory\":\"helped_refugees\"}]},"
                + "{\"LabelKey\":\"opt.thanks\",\"Conditions\":[{\"Type\":\"Remembered\","
                + "\"Memory\":\"helped_refugees\"}]},"
                + "{\"LabelKey\":\"opt.undo\",\"Actions\":[{\"Type\":\"Forget\","
                + "\"Memory\":\"helped_refugees\"}]}]}}}");
    }

    // ==================== Declaration ====================

    @Test
    void declarationsDecodeEveryAxis() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{"
                + "\"helped_refugees\":{\"Where\":{\"Match\":[\"emerald_wilds\"]},"
                + "\"ResetWithQuest\":\"guide_trust\",\"Shared\":true},"
                + "\"knows_my_name\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(d);

        DialogueMemory helped = d.getMemory("helped_refugees");
        assertNotNull(helped);
        assertEquals("emerald_wilds", helped.getWhere().getMatch()[0]);
        assertEquals("guide_trust", helped.getResetWithQuest());
        assertTrue(helped.isShared());

        DialogueMemory plain = d.getMemory("knows_my_name");
        assertNotNull(plain);
        assertNull(plain.getWhere());
        assertFalse(plain.isShared());
        assertNotNull(d.getMemory("KNOWS_MY_NAME"), "a name lookup is case-insensitive");
    }

    @Test
    void aParentChildAddsOrRedeclaresOneMemoryAndInheritsTheRest() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base", "{\"Memories\":{"
                + "\"helped_refugees\":{\"Where\":{\"Match\":[\"emerald_wilds\"]}},"
                + "\"knows_my_name\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(parent);

        // The child re-declares one leaf of one memory and adds another; everything else inherits.
        NpcDialogue child = DialogueTestSupport.decodeWithParent(engine, "kid",
                "{\"Memories\":{\"helped_refugees\":{\"ResetWithQuest\":\"guide_trust\"},"
                        + "\"owes_me\":{}}}", parent);
        assertNotNull(child);

        assertEquals(3, child.getMemories().size());
        DialogueMemory helped = child.getMemory("helped_refugees");
        assertNotNull(helped);
        assertEquals("guide_trust", helped.getResetWithQuest(), "the child's own leaf");
        assertEquals("emerald_wilds", helped.getWhere().getMatch()[0], "the parent's leaf survives");
        assertNotNull(child.getMemory("knows_my_name"), "a parent-only memory is retained");
        assertNotNull(child.getMemory("owes_me"), "the child's new memory is added");
    }

    @Test
    void childOmittingMemoriesInheritsTheWholeMap() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base", "{\"Memories\":{\"a\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(parent);
        NpcDialogue child = DialogueTestSupport.decodeWithParent(engine, "kid", "{\"Start\":{\"First\":[{\"Node\":\"g\"}]}}", parent);
        assertNotNull(child);
        assertNotNull(child.getMemory("a"));
    }

    // ==================== Use sites ====================

    @Test
    void rememberThenForgetRoundTripsThroughTheStore() {
        DialogueEngine engine = engine();
        NpcDialogue d = guide(engine);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        List<DialogueOption> options = d.getNode("camp").getOptions();

        assertFalse(engine.conditionsPass(options.get(1).getConditions(), ctx));

        engine.executor().execute(options.get(0).getActions(), ctx);
        assertEquals(Set.of("mem:d:guide:helped_refugees"), ctx.state().keys);
        assertTrue(engine.conditionsPass(options.get(1).getConditions(), ctx),
                "Remembered passes once the memory is written");

        engine.executor().execute(options.get(2).getActions(), ctx);
        assertTrue(ctx.state().keys.isEmpty());
        assertFalse(engine.conditionsPass(options.get(1).getConditions(), ctx));
    }

    @Test
    void notRememberedIsTheMirror() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"met\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                + "{\"LabelKey\":\"a\",\"Conditions\":[{\"Type\":\"NotRemembered\",\"Memory\":\"met\"}],"
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"met\"}]}]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        DialogueOption first = d.getNode("g").getOptions().get(0);

        assertTrue(engine.conditionsPass(first.getConditions(), ctx));
        engine.executor().execute(first.getActions(), ctx);
        assertFalse(engine.conditionsPass(first.getConditions(), ctx));
    }

    @Test
    void optionSugarWritesTheCanonicalAction() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"met\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                + "{\"LabelKey\":\"a\",\"Remember\":\"met\",\"Close\":true},"
                + "{\"LabelKey\":\"b\",\"Forget\":\"met\"}]}}}");
        assertNotNull(d);

        List<DialogueAction> remember = d.getNode("g").getOptions().get(0).getActions();
        assertTrue(remember.get(0) instanceof DialogueAction.Remember);
        assertEquals("met", ((DialogueAction.Remember) remember.get(0)).getMemory());
        assertTrue(remember.get(1) instanceof DialogueAction.Close, "Remember(32) runs before Close(70)");
        assertTrue(d.getNode("g").getOptions().get(1).getActions().get(0) instanceof DialogueAction.Forget);
    }

    @Test
    void bareMemorySugarExpandsAfterTheBandConsumersRegisterQuestSugarIn() {
        DialogueEngine engine = engine();
        // Authored deliberately out of order: bare keys expand by their fixed order, never by the
        // order they were written in. Remember is 32, Forget 33, Goto 60, Close 70, so a memory
        // write always trails the 10-30 band a consumer's own quest sugar occupies.
        NpcDialogue d = engine.decode("guide", "{\"Memories\":{\"met\":{}},"
                + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                + "{\"LabelKey\":\"a\",\"Close\":true,\"Goto\":\"g\",\"Forget\":\"met\","
                + "\"Remember\":\"met\"}]}}}");
        assertNotNull(d);

        List<DialogueAction> actions = d.getNode("g").getOptions().get(0).getActions();
        assertEquals(4, actions.size());
        assertTrue(actions.get(0) instanceof DialogueAction.Remember, "Remember is 32");
        assertTrue(actions.get(1) instanceof DialogueAction.Forget, "Forget is 33");
        assertTrue(actions.get(2) instanceof DialogueAction.Goto, "Goto is 60");
        assertTrue(actions.get(3) instanceof DialogueAction.Close, "Close is 70");
    }

    @Test
    void anUndeclaredMemoryStillWorksButSaysSoOnce() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide", "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},"
                + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\","
                + "\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"undeclared\"}]}]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        engine.executor().execute(d.getNode("g").getOptions().get(0).getActions(), ctx);

        assertEquals(Set.of("mem:d:guide:undeclared"), ctx.state().keys,
                "an undeclared name falls back to a plain per-dialogue memory rather than breaking");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("undeclared")), warnings.toString());
        int warned = warnings.size();
        engine.executor().execute(d.getNode("g").getOptions().get(0).getActions(), ctx);
        assertEquals(warned, warnings.size(), "the warning must not repeat on every click");
    }

    // ==================== Scope + lifetime ====================

    @Test
    void aSharedMemoryIsOneKeyForEveryDialogueThatDeclaresIt() {
        DialogueMemory shared = DialogueMemory.of(null, null, true);
        DialogueMemory privateToOne = DialogueMemory.of(null, null, null);

        assertEquals("mem:s:helped", shared.resolveKey("guide_a", "helped", "default"));
        assertEquals("mem:s:helped", shared.resolveKey("guide_b", "helped", "default"));
        assertEquals("mem:d:guide_a:helped", privateToOne.resolveKey("guide_a", "helped", "default"));
        assertEquals("mem:d:guide_b:helped", privateToOne.resolveKey("guide_b", "helped", "default"));
    }

    /**
     * A {@code ResetWithQuest} memory has to land INSIDE the quest's own namespace, because a
     * consumer forgets a quest's state with a leading-prefix clear. Anything outside it survives
     * the reset and soft-locks whatever it gated.
     */
    @Test
    void resetWithQuestFilesTheMemoryWhereAQuestResetFindsIt() {
        DialogueMemory memory = DialogueMemory.of(WorldSelector.of(new String[] {"emerald_wilds"}, null, null), "guide_trust", null);

        String key = memory.resolveKey("guide", "helped", "Emerald_Wilds");
        assertEquals("q:guide_trust:mem:d:guide:w:emerald_wilds:helped", key);

        TestDialogueContext.Flags flags = new TestDialogueContext.Flags();
        flags.set(key);
        flags.set("mem:d:guide:other");
        flags.clearPrefix("q:guide_trust:");

        assertFalse(flags.has(key), "the quest reset must forget the memory it owns");
        assertTrue(flags.has("mem:d:guide:other"), "and leave every other memory alone");
    }

    // ==================== The quest-namespace clear ====================

    /**
     * An administrator resetting a player's WHOLE quest slate has no id left to key on, so the
     * clear is the {@code q:} namespace. What it must NOT do is take everything: the memories in
     * shipped content today are greetings and one-shot gifts that no quest owns, and they are not
     * quest data.
     */
    @Test
    void theQuestNamespaceClearTakesEveryQuestScopedMemoryAndLeavesTheRest() {
        DialogueFlagStore session = InMemoryDialogueFlagStore.forPlayer(UUID.randomUUID());
        DialogueFlagStore persistent = InMemoryDialogueFlagStore.forPlayer(UUID.randomUUID());
        DialogueFlagStore routed = new DialogueMemories.Routed(session, persistent);

        String questScoped = DialogueStateKeys.withQuest("guide_trust",
                DialogueStateKeys.memory("guide", "helped_refugees", false));
        String sessionAndQuestScoped = DialogueStateKeys.withSession(true,
                DialogueStateKeys.withQuest("escort_caravan",
                        DialogueStateKeys.memory("guide", "mid_escort_banter", false)));
        // The one memory shipped content actually holds: a greeting, owned by no quest at all.
        String plain = DialogueStateKeys.memory("mmo_hub_intro", "temple_greeted", false);
        String sessionPlain = DialogueStateKeys.withSession(true,
                DialogueStateKeys.memory("guide", "small_talk", false));
        String sharedPlain = DialogueStateKeys.memory("guide", "knows_my_name", true);
        String firstVisitBeat = DialogueStateKeys.entryOnce("mmo_hub_intro", "welcome");
        for (String key : List.of(questScoped, sessionAndQuestScoped, plain, sessionPlain,
                sharedPlain, firstVisitBeat)) {
            routed.set(key);
        }

        routed.clearWithPrefix(DialogueStateKeys.QUEST_NAMESPACE);

        assertFalse(routed.has(questScoped), "a memory a quest owns goes");
        assertFalse(routed.has(sessionAndQuestScoped),
                "and so does one that is BOTH session-scoped and quest-scoped, which is filed"
                        + " ses:q:<questId>: and would escape a clear that only looked for q:");
        assertTrue(routed.has(plain),
                "THE POINT: a memory no quest owns - a greeting a character remembers - is not"
                        + " quest data and must survive a quest reset");
        assertTrue(routed.has(sessionPlain), "the same holds for a session memory no quest owns");
        assertTrue(routed.has(sharedPlain), "and for a Shared one");
        assertTrue(routed.has(firstVisitBeat), "a first-visit beat is not quest state either");
    }

    /**
     * The clear for ONE quest and the clear for every quest at once have to agree about where a
     * quest's state lives, or one of them reaches keys the other cannot see.
     */
    @Test
    void oneQuestsPrefixSitsInsideTheQuestNamespace() {
        assertEquals("q:", DialogueStateKeys.QUEST_NAMESPACE);
        assertTrue(DialogueStateKeys.questPrefix("guide_trust")
                .startsWith(DialogueStateKeys.QUEST_NAMESPACE));
        assertTrue(DialogueStateKeys.withQuest("guide_trust", "mem:d:guide:helped")
                .startsWith(DialogueStateKeys.QUEST_NAMESPACE));
        assertFalse(DialogueStateKeys.memory("guide", "helped", false)
                .startsWith(DialogueStateKeys.QUEST_NAMESPACE),
                "an un-owned memory must not sit in the namespace a quest reset clears");
    }

    @Test
    void aWorldScopedMemoryDoesNotExistInAnotherWorld() {
        DialogueMemory memory = DialogueMemory.of(WorldSelector.of(new String[] {"emerald_wilds"}, null, null), null, null);

        assertEquals("mem:d:guide:w:emerald_wilds:helped",
                memory.resolveKey("guide", "helped", "Emerald_Wilds"));
        assertNull(memory.resolveKey("guide", "helped", "default"));
    }

    @Test
    void offPatternAScopedMemoryReadsAsForgottenAndWritesAreDropped() {
        DialogueEngine engine = engine();
        // The test context cannot read a world, the same shape as standing where the pattern misses.
        NpcDialogue d = engine.decode("guide",
                "{\"Memories\":{\"helped\":{\"Where\":{\"Match\":[\"emerald_wilds\"]}}},"
                        + "\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Actions\":[{\"Type\":\"Remember\",\"Memory\":\"helped\"}]},"
                        + "{\"LabelKey\":\"b\",\"Conditions\":[{\"Type\":\"Remembered\",\"Memory\":\"helped\"}]},"
                        + "{\"LabelKey\":\"c\",\"Conditions\":[{\"Type\":\"NotRemembered\",\"Memory\":\"helped\"}]}]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        List<DialogueOption> options = d.getNode("g").getOptions();

        engine.executor().execute(options.get(0).getActions(), ctx);
        assertTrue(ctx.state().keys.isEmpty(), "the write is a no-op, never a throw");
        assertFalse(engine.conditionsPass(options.get(1).getConditions(), ctx));
        assertTrue(engine.conditionsPass(options.get(2).getConditions(), ctx));
    }
}
