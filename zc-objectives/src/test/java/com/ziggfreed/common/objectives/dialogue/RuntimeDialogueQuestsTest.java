package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.dialogue.state.InMemoryDialogueFlagStore;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionCallScope;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestOwnerLayers;
import com.ziggfreed.common.subject.Subject;

/**
 * A conversation on a server where no consumer installed a quest runtime of its own: the library's
 * binding over the shared engine is what its quest lines read and act through, so a giver written
 * against the shared vocabulary works with this jar alone.
 *
 * <p>Driven the way the page drives it: an authored conversation decoded through the shared engine,
 * its {@code Start} resolved and its lines executed against a context, over the shared runtime with
 * an in-memory store and a subject source standing in for the player. Every mutating call is
 * watched through a recording call scope, because a write made outside the registered scope is a
 * write a consumer's listeners never hear.
 */
class RuntimeDialogueQuestsTest {

    private static final String CONSUMER = "dialogue-quests-test";
    private static final String GUIDE = "the_guide";

    @TempDir
    Path ownerFolder;

    private final Subject player = Subject.of(UUID.randomUUID(), "tester");
    private final List<String> scoped = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetEverything();
        QuestOwnerLayers.setDirectory(ownerFolder);
        ProgressionDefaults.register();
        ProgressionCallScope recording = new ProgressionCallScope() {
            @Override
            public <T> T around(@Nonnull Subject subject, @Nonnull Function<Subject, T> body) {
                scoped.add(subject.name());
                return body.apply(subject);
            }
        };
        QuestPossessionProbe holds = (subject, itemId, count) -> true;
        QuestInventoryConsumer takes = (subject, itemId, max) -> max;
        ProgressionRuntime.registrar(CONSUMER)
                .questStore(new InMemoryQuestProgressStore())
                .questPossession(holds)
                .questInventory(takes)
                .questScope(recording)
                .subjects(new ProgressionSubjectSource() {
                    @Override
                    @Nullable
                    public Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
                        return player;
                    }

                    @Override
                    @Nullable
                    public Subject achievementSubject(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref) {
                        return player;
                    }
                })
                .warn(message -> { });
        // The library's own setup half, exactly as its plugin runs it.
        DialogueBootstrap.registerDialogueVocabulary();
    }

    @AfterEach
    void tearDown() {
        QuestOwnerLayers.setDirectory(QuestOwnerLayers.DEFAULT_DIRECTORY);
        resetEverything();
    }

    private static void resetEverything() {
        ProgressionRuntime.resetForTests();
        NpcOfferProviders.clear();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        QuestAssetStore.getInstance().mergeQuests(Map.of());
        NpcPlacementConfig.getInstance().mergePackLayer(Map.of());
        DialogueTypeTable.get().resetForTests();
        DialogueEngine.resetSharedForTests();
    }

    // ==================== the slot ====================

    @Test
    void theLibrarysSetupInstallsItsBindingBeneathTheQuestSlot() {
        DialogueQuests quests = DialogueEngine.shared().quests();

        assertInstanceOf(RuntimeDialogueQuests.class, quests,
                "a server running no quest mod of its own still has quest-aware conversations");
    }

    @Test
    void aConsumersOwnRuntimeStillWins() {
        DialogueQuests consumer = new DialogueQuests() {
            @Override
            @Nonnull
            public QuestStateReader reader() {
                return DialogueQuests.NONE.reader();
            }
        };

        assertTrue(DialogueEngine.installQuests("consumer", consumer),
                "the library's default never holds the slot a consumer installs into");
        assertSame(consumer, DialogueEngine.shared().quests());
    }

    // ==================== reading ====================

    @Test
    void aConversationReadsTheQuestsRealState() {
        Quest quest = gather("q_gather");
        publish(quest);
        NpcDialogue dialogue = decode("{\"Start\":{\"First\":[{\"Node\":\"busy\",\"When\":["
                + "{\"Type\":\"QuestState\",\"Quest\":\"q_gather\",\"State\":\"ACTIVE\"}]}],"
                + "\"Fallback\":\"hello\"},"
                + "\"Nodes\":{\"hello\":{\"TextKey\":\"t.hello\"},\"busy\":{\"TextKey\":\"t.busy\"}}}");

        assertEquals("hello", DialogueEngine.shared().resolveEntry(dialogue, at(GUIDE, dialogue)).nodeId(),
                "not started reads as not started");

        assertTrue(ProgressionRuntime.quests().accept(player, quest, GUIDE));

        assertEquals("busy", DialogueEngine.shared().resolveEntry(dialogue, at(GUIDE, dialogue)).nodeId(),
                "and a quest the engine holds as active reads as active, with no consumer binding");
    }

    // ==================== acting ====================

    @Test
    void anAcceptLineTakesTheQuestOnThroughTheEngineWhereItWasSpoken() {
        publish(gather("q_gather"));
        NpcDialogue dialogue = decode(oneLine("\"Accept\":\"q_gather\""));

        run(dialogue, GUIDE);

        QuestEngine engine = ProgressionRuntime.quests();
        assertEquals(QuestStatus.ACTIVE, engine.status(player, "q_gather"));
        assertEquals(GUIDE, engine.acceptSiteOf(player, "q_gather"),
                "taken on at the character the conversation is with");
        assertFalse(scoped.isEmpty(), "inside the registered call scope, so a consumer's listeners hear it");
    }

    @Test
    void anAcceptTheEngineRefusesStartsNothing() {
        publish(Quest.builder("q_off").available(false)
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build());
        NpcDialogue dialogue = decode(oneLine("\"Accept\":\"q_off\""));

        run(dialogue, GUIDE);

        assertEquals(QuestStatus.NOT_STARTED, ProgressionRuntime.quests().status(player, "q_off"),
                "the line asks the same accept check every other surface asks");
    }

    @Test
    void aTurnInLineHandsInThroughTheEngineAtTheCharacter() {
        Quest quest = reportBack("q_report", GUIDE);
        publish(quest);
        assertTrue(ProgressionRuntime.quests().accept(player, quest, GUIDE));
        NpcDialogue dialogue = decode(oneLine("\"TurnIn\":\"q_report\""));

        DialogueActionExecutor.Outcome outcome = run(dialogue, GUIDE);

        assertEquals(QuestStatus.COMPLETED, ProgressionRuntime.quests().status(player, "q_report"),
                "a finished errand with nothing to collect settles where it was handed in");
        assertEquals("q_report", outcome.completedId(), "and the line reports the quest it finished");
    }

    @Test
    void aHandInThatParksLeavesTheQuestWaitingToBeCollected() {
        Quest quest = Quest.builder("q_sweets")
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(GUIDE).build())
                .reward(RewardSpec.of("NOTE", "text", "sweets"))
                .build();
        publish(quest);
        assertTrue(ProgressionRuntime.quests().accept(player, quest, GUIDE));
        NpcDialogue dialogue = decode(oneLine("\"TurnIn\":\"q_sweets\""));

        run(dialogue, GUIDE);

        assertEquals(QuestStatus.COMPLETED_UNCLAIMED, ProgressionRuntime.quests().status(player, "q_sweets"),
                "a conversation hands in and never collects behind the player's back, so the parked"
                        + " quest is what the character's list then offers to collect");
    }

    @Test
    void aTurnInAtSomebodyElseHandsNothingIn() {
        Quest quest = reportBack("q_report", GUIDE);
        publish(quest);
        assertTrue(ProgressionRuntime.quests().accept(player, quest, GUIDE));
        NpcDialogue dialogue = decode(oneLine("\"TurnIn\":\"q_report\""));

        DialogueActionExecutor.Outcome outcome = run(dialogue, "a_stranger");

        assertEquals(QuestStatus.ACTIVE, ProgressionRuntime.quests().status(player, "q_report"));
        assertNull(outcome.completedId());
    }

    // ==================== who a character is ====================

    @Test
    void aCharacterAnswersToEveryIdItsIdentityGivesIt() {
        Map<String, NpcPlacementAsset> layer = new LinkedHashMap<>();
        layer.put("temple_spot", NpcPlacementAsset.of("temple_spot", null,
                NpcPlacementAsset.Identity.of("Some_Role", "temple_guide", new String[] {GUIDE}),
                null, null, null, null, null, null));
        NpcPlacementConfig.getInstance().mergePackLayer(layer);

        assertEquals(List.of("temple_guide", GUIDE),
                List.copyOf(DialogueEngine.shared().quests().answersTo("temple_guide")),
                "its own id first, then the ids it shares");

        Quest quest = reportBack("q_report", GUIDE);
        publish(quest);
        assertTrue(ProgressionRuntime.quests().accept(player, quest, GUIDE));
        NpcDialogue dialogue = decode(oneLine("\"TurnIn\":\"q_report\""));

        run(dialogue, "temple_guide");

        assertEquals(QuestStatus.COMPLETED, ProgressionRuntime.quests().status(player, "q_report"),
                "so a quest reporting back to the shared id is handed in at either standing");
    }

    // ==================== what follows ====================

    @Test
    void theCompletionConversationIsTheOneTheQuestNames() throws Exception {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, "q_closing", null);
        QuestAsset asset = QuestAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(
                "{ \"CompletionDialogue\": \"Guide_Thanks\", \"Objectives\": { \"collect\":"
                        + " { \"Kind\": \"PICKUP_ITEM\", \"Target\": \"Ore\", \"Amount\": 10 } } }"),
                null, new AssetExtraInfo<>(data));
        QuestAssetStore.getInstance().mergeQuests(Map.of("q_closing", asset));

        DialogueQuests quests = DialogueEngine.shared().quests();

        assertEquals("guide_thanks", quests.completionDialogueOf("q_closing"));
        assertNull(quests.completionDialogueOf("q_nothing_follows"));
    }

    // ==================== helpers ====================

    @Nonnull
    private static Quest gather(@Nonnull String id) {
        return Quest.builder(id)
                .npcViewId(GUIDE)
                .objective(ObjectiveDef.builder("mine", "BREAK_BLOCK").target("Copper_Ore").amount(3).build())
                .build();
    }

    @Nonnull
    private static Quest reportBack(@Nonnull String id, @Nonnull String at) {
        return Quest.builder(id)
                .npcViewId(at)
                .objective(ObjectiveDef.builder("tell", "TURN_IN").target("").amount(1).turnInLockId(at).build())
                .build();
    }

    private static void publish(@Nonnull Quest quest) {
        ProgressionRuntime.publishQuests(CONSUMER, List.of(quest));
        ProgressionRuntime.ensureBuilt();
    }

    /** One screen holding one line whose body is {@code line}. */
    @Nonnull
    private static String oneLine(@Nonnull String line) {
        return "{\"Nodes\":{\"n\":{\"TextKey\":\"t.n\",\"Options\":[{\"LabelKey\":\"t.n.go\"," + line + "}]}}}";
    }

    @Nonnull
    private static NpcDialogue decode(@Nonnull String json) {
        NpcDialogue dialogue = DialogueEngine.shared().decode("runtime_dialogue_quests_test", json);
        assertNotNull(dialogue, json);
        return dialogue;
    }

    @Nonnull
    private DialogueActionExecutor.Outcome run(@Nonnull NpcDialogue dialogue, @Nonnull String npcId) {
        return DialogueEngine.shared().executor()
                .execute(dialogue.getNode("n").getOptions().get(0).getActions(), at(npcId, dialogue));
    }

    /**
     * A click at {@code npcId}: no live world, so the player is whoever the registered subject
     * source answers for this reference, exactly as a real conversation resolves it.
     */
    @Nonnull
    private DialogueExecContext at(@Nonnull String npcId, @Nonnull NpcDialogue dialogue) {
        DialogueFlagStore flags = InMemoryDialogueFlagStore.forPlayer(player.id());
        Ref<EntityStore> ref = new Ref<>((Store<EntityStore>) null, 0);
        return new DialogueExecContext() {
            @Override public Store<EntityStore> store() { return null; }
            @Override public Ref<EntityStore> ref() { return ref; }
            @Override @Nullable public PlayerRef playerRef() { return null; }
            @Override public Player player() { return null; }
            @Override @Nullable public String contextId() { return npcId; }
            @Override @Nonnull public NpcDialogue dialogue() { return dialogue; }
            @Override public DialogueFlagStore flags() { return flags; }
            @Override @Nullable public <T> T payload(@Nonnull Class<T> type) { return null; }
            @Override @Nonnull public String nodeId() { return "n"; }
            @Override public int optionIndex() { return 0; }
        };
    }
}
