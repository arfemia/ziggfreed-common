package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.dialogue.state.InMemoryDialogueFlagStore;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.type.DialogueActionType;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.dialogue.GrantDialogueAction.Grant;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.subject.Subject;

/**
 * A conversation line that pays: how {@code Grant} reads in both its forms, what it pays and to whom,
 * labelled how, what it does with a broken entry or nobody to pay, that its schema is one the Asset
 * Editor can take, and that the library leaves the {@code Reward} Type to a consumer.
 */
class GrantDialogueActionTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    /** Option 0 pays through the shorthand, option 1 through the full form with a chance. */
    private static final String TWO_LINES = "{\"Nodes\":{\"n\":{\"Options\":["
            + "{\"LabelKey\":\"t.n.short\",\"Grant\":[{\"Kind\":\"Item\",\"Params\":{\"Item\":\"Rock_Stone\",\"Count\":2}}]},"
            + "{\"LabelKey\":\"t.n.full\",\"Actions\":[{\"Type\":\"Grant\",\"Rewards\":"
            + "[{\"Kind\":\"Lootable\",\"Params\":{\"Lootable\":\"Yourpack_Gifts\"}}],\"Chance\":0.5}]}"
            + "]}}}";

    @BeforeEach
    void freshVocabulary() {
        DialogueTypeTable.get().resetForTests();
        DialogueEngine.resetSharedForTests();
    }

    @AfterEach
    void clearVocabulary() {
        DialogueTypeTable.get().resetForTests();
        DialogueEngine.resetSharedForTests();
    }

    // ==================== reading ====================

    @Test
    void theShorthandAndTheFullFormEachReadAsOneGrant() {
        DialogueBootstrap.registerDialogueVocabulary();
        NpcDialogue dialogue = DialogueEngine.shared().decode("grant_test", TWO_LINES);
        assertNotNull(dialogue);

        Grant shorthand = onlyGrant(dialogue, 0);
        RewardEntryAsset[] entries = shorthand.getRewards();
        assertNotNull(entries);
        assertEquals("Item", entries[0].getKind());
        assertEquals("Rock_Stone", entries[0].getParams().get("Item"));
        assertNull(shorthand.getChance(), "the shorthand always pays");

        Grant full = onlyGrant(dialogue, 1);
        assertEquals("Lootable", full.getRewards()[0].getKind());
        assertEquals(Float.valueOf(0.5f), full.getChance());
    }

    @Test
    void theLibraryLeavesTheRewardTypeToAConsumer() {
        DialogueBootstrap.registerDialogueVocabulary();
        assertTrue(DialogueEngine.registerShared("consumer", DialogueActionType.of("Reward",
                ConsumerReward.class, ConsumerReward.CODEC, (a, ctx, out) -> { })));

        NpcDialogue dialogue = DialogueEngine.shared().decode("reward_test",
                "{\"Nodes\":{\"n\":{\"Options\":[{\"LabelKey\":\"t.n.r\",\"Actions\":[{\"Type\":\"Reward\"}]}]}}}");

        assertNotNull(dialogue);
        assertInstanceOf(ConsumerReward.class, dialogue.getNode("n").getOptions().get(0).getActions().get(0),
                "a consumer's Reward lines still read as that consumer's action");
    }

    @Test
    void theShorthandIsAListThatSaysWhatItHolds() {
        DialogueBootstrap.registerDialogueVocabulary();
        SchemaContext context = new SchemaContext();
        DialogueTypeTable.get().optionsArray().toSchema(context);
        ObjectSchema option = definition(context, "DialogueOption");

        Schema grant = option.getProperties().get(GrantDialogueAction.SHORTHAND);
        assertNotNull(grant, "the shorthand is a real field on the option");
        assertNotNull(arrayOf(grant).getItems(), "an array declares what it holds, or the editor cannot load it");
        assertNotNull(Schema.CODEC.encode(option, new ExtraInfo()), "the option encodes as the editor receives it");
    }

    @Test
    void theFullFormsLeavesSayWhatTheyMean() {
        ObjectSchema grant = Grant.CODEC.toSchema(new SchemaContext());

        assertNotNull(arrayOf(grant.getProperties().get("Rewards")).getItems());
        assertNotNull(grant.getProperties().get("Rewards").getMarkdownDescription());
        assertNotNull(grant.getProperties().get("Chance").getMarkdownDescription());
        assertNotNull(Schema.CODEC.encode(grant, new ExtraInfo()));
    }

    // ==================== paying ====================

    @Test
    void aGrantPaysItsEntriesInOrderLabelledByItsConversation() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<RewardSpec> paid = new ArrayList<>();
        kinds.register("Test_Coin", "test", (spec, subject) -> paid.add(spec));
        kinds.register("Test_Gem", "test", (spec, subject) -> paid.add(spec));
        Grant grant = Grant.of(new RewardEntryAsset[] {
                RewardEntryAsset.of("Test_Coin", Map.of("Amount", "5")),
                RewardEntryAsset.of("Test_Gem", Map.of())}, null);

        RewardGrants.GrantOutcome outcome = GrantDialogueAction.pay(grant, () -> PLAYER,
                GrantDialogueAction.sourceId("yourpack_merchant"), kinds, null, () -> 0.99);

        assertEquals(2, outcome.granted());
        assertEquals(List.of("Test_Coin", "Test_Gem"), paid.stream().map(RewardSpec::kind).toList());
        assertEquals("dialogue:yourpack_merchant", paid.get(0).param(RewardGrants.P_SOURCE));
        assertEquals("dialogue:Grant", GrantDialogueAction.sourceId("  "), "a conversation naming nothing");
    }

    @Test
    void aLostRollAnEmptyListOrOnlyBlankEntriesPayNothingAndAskForNobody() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<RewardSpec> paid = new ArrayList<>();
        kinds.register("Test_Coin", "test", (spec, subject) -> paid.add(spec));
        RewardEntryAsset[] coin = {RewardEntryAsset.of("Test_Coin", Map.of())};
        int[] asked = {0};
        Supplier<Subject> who = () -> {
            asked[0]++;
            return PLAYER;
        };

        assertEquals(0, GrantDialogueAction.pay(Grant.of(coin, 0.25f), who, "dialogue:t", kinds, null,
                () -> 0.5).granted(), "the draw must land below the chance");
        assertEquals(0, GrantDialogueAction.pay(Grant.of(coin, Float.NaN), who, "dialogue:t", kinds, null,
                () -> 0.0).granted(), "a malformed chance pays nothing rather than everything");
        assertEquals(0, GrantDialogueAction.pay(Grant.of(coin, -1f), who, "dialogue:t", kinds, null,
                () -> 0.0).granted());
        assertEquals(0, GrantDialogueAction.pay(Grant.of(null, null), who, "dialogue:t", kinds, null,
                () -> 0.0).granted());
        assertEquals(0, GrantDialogueAction.pay(Grant.of(new RewardEntryAsset[] {RewardEntryAsset.of("  ", Map.of())},
                null), who, "dialogue:t", kinds, null, () -> 0.0).granted(), "an entry naming no kind is dropped");

        assertTrue(paid.isEmpty());
        assertEquals(0, asked[0], "nobody is looked up for a line that pays nothing");
    }

    @Test
    void withNobodyToPayNothingIsPaid() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<RewardSpec> paid = new ArrayList<>();
        kinds.register("Test_Coin", "test", (spec, subject) -> paid.add(spec));

        RewardGrants.GrantOutcome outcome = GrantDialogueAction.pay(
                Grant.of(new RewardEntryAsset[] {RewardEntryAsset.of("Test_Coin", Map.of())}, null),
                () -> null, "dialogue:t", kinds, null, () -> 0.0);

        assertEquals(0, outcome.granted());
        assertEquals(0, outcome.failed(), "nobody to pay is not a lost reward");
        assertTrue(paid.isEmpty());
    }

    @Test
    void anUnknownKindIsCountedLostAndTheRestStillPay() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<RewardSpec> paid = new ArrayList<>();
        kinds.register("Test_Coin", "test", (spec, subject) -> paid.add(spec));
        Grant grant = Grant.of(new RewardEntryAsset[] {
                RewardEntryAsset.of("No_Such_Kind", Map.of()),
                RewardEntryAsset.of("Test_Coin", Map.of())}, null);

        RewardGrants.GrantOutcome outcome = GrantDialogueAction.pay(grant, () -> PLAYER, "dialogue:t", kinds,
                null, () -> 0.0);

        assertEquals(1, outcome.failed());
        assertEquals(1, outcome.granted());
        assertEquals(List.of("Test_Coin"), paid.stream().map(RewardSpec::kind).toList());
    }

    @Test
    void aLineChosenWithNobodyBehindItPaysNothingAndReportsNoFailure() {
        List<String> warnings = new ArrayList<>();
        DialogueEngine.resetSharedForTests(warnings::add);
        DialogueBootstrap.registerDialogueVocabulary();
        NpcDialogue dialogue = DialogueEngine.shared().decode("grant_nobody", TWO_LINES);
        assertNotNull(dialogue);

        DialogueActionExecutor.Outcome outcome = DialogueEngine.shared().executor()
                .execute(dialogue.getNode("n").getOptions().get(0).getActions(), nobody(dialogue));

        assertTrue(warnings.isEmpty(), "the line returned before touching any engine handle: " + warnings);
        assertNull(outcome.gotoNode());
        assertFalse(outcome.close());
    }

    // ==================== helpers ====================

    /** A consumer's own {@code Reward}, the shape a mod above the library registers. */
    public static final class ConsumerReward extends DialogueAction {
        public static final BuilderCodec<ConsumerReward> CODEC =
                BuilderCodec.builder(ConsumerReward.class, ConsumerReward::new).build();
    }

    @Nonnull
    private static Grant onlyGrant(@Nonnull NpcDialogue dialogue, int option) {
        List<DialogueAction> actions = dialogue.getNode("n").getOptions().get(option).getActions();
        assertEquals(1, actions.size(), actions.toString());
        return assertInstanceOf(Grant.class, actions.get(0));
    }

    /** A click whose context names no player, as a reference that went stale reads. */
    @Nonnull
    private static DialogueExecContext nobody(@Nonnull NpcDialogue dialogue) {
        DialogueFlagStore flags = InMemoryDialogueFlagStore.forPlayer(UUID.randomUUID());
        return new DialogueExecContext() {
            @Override public Store<EntityStore> store() { return null; }
            @Override public Ref<EntityStore> ref() { return null; }
            @Override @Nullable public PlayerRef playerRef() { return null; }
            @Override public Player player() { return null; }
            @Override @Nullable public String contextId() { return null; }
            @Override @Nonnull public NpcDialogue dialogue() { return dialogue; }
            @Override public DialogueFlagStore flags() { return flags; }
            @Override @Nullable public <T> T payload(@Nonnull Class<T> type) { return null; }
            @Override @Nonnull public String nodeId() { return "n"; }
            @Override public int optionIndex() { return 0; }
        };
    }

    /** An array leaf, read through the nullable wrapper the schema may put around it. */
    @Nonnull
    private static ArraySchema arrayOf(@Nonnull Schema schema) {
        if (schema instanceof ArraySchema array) {
            return array;
        }
        if (schema.getAnyOf() != null) {
            for (Schema arm : schema.getAnyOf()) {
                if (arm instanceof ArraySchema array) {
                    return array;
                }
            }
        }
        throw new AssertionError("not an array leaf: " + schema);
    }

    /** The raw definition the context filed under a codec whose class has that simple name. */
    @Nonnull
    private static ObjectSchema definition(@Nonnull SchemaContext context, @Nonnull String simpleName) {
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (entry.getKey().endsWith(simpleName) && entry.getValue() instanceof ObjectSchema object) {
                return object;
            }
        }
        throw new AssertionError("no definition named like " + simpleName + " in "
                + context.getDefinitions().keySet());
    }
}
