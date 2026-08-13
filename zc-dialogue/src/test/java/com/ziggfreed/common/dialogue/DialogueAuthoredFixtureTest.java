package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.dialogue.asset.ZcDialogueAsset;

/**
 * The conversations that are already out there must keep loading, verbatim.
 *
 * <p>The three fixtures are byte-for-byte copies of shipped files - a released minigame's, the MMO
 * jar's hub conversation, and a content pack's guide - and they are read through the real asset
 * codec, not a simplified stand-in. Between them they cover every authoring shape that exists:
 * shorthand written bare and inside {@code Do}, a shorthand a MOD registered rather than the
 * framework, shared option groups, declared memories, {@code Once} as a bare flag, world gates,
 * quest gates and per-option styling.
 *
 * <p>They also make the ordering rule concrete. A file naming a mod's own {@code Type} can only be
 * read once that mod has registered it, which is why the stand-in vocabulary below is registered
 * before anything is read - exactly what a mod does in its setup, before the server reads assets.
 */
class DialogueAuthoredFixtureTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    /**
     * The vocabulary the shipped files were authored against: the framework's own, plus stand-ins
     * for the four conditions and three shorthands their mods register. Behaviour is irrelevant
     * here - this test is about the SHAPE being readable.
     */
    @Nonnull
    private static DialogueEngine engineForShippedContent() {
        return DialogueEngine.builder()
                .warn(m -> { })
                .condition(paramless("NotInRound", NotInRound.class, NotInRound.CODEC))
                .condition(paramless("Engaged", Engaged.class, Engaged.CODEC))
                .condition(paramless("HasActiveBounties", HasActiveBounties.class, HasActiveBounties.CODEC))
                .condition(paramless("HasOfferableQuests", HasOfferableQuests.class, HasOfferableQuests.CODEC))
                .action(DialogueActionType.of("OpenPlay", OpenPlay.class, OpenPlay.CODEC,
                                (OpenPlay a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { })
                        .withSugar(DialogueSugar.string("Play", 15, preset -> {
                            OpenPlay action = new OpenPlay();
                            action.preset = preset;
                            return action;
                        })))
                .action(DialogueActionType.of("CompleteQuest", CompleteQuest.class, CompleteQuest.CODEC,
                                (CompleteQuest a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { })
                        .withSugar(DialogueSugar.string("Complete", 25, quest -> {
                            CompleteQuest action = new CompleteQuest();
                            action.quest = quest;
                            return action;
                        })))
                .action(DialogueActionType.of("Reward", RewardGrant.class, RewardGrant.CODEC,
                                (RewardGrant a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> { })
                        .withSugar(DialogueSugar.of("Reward", 40, RewardPayload.CODEC,
                                java.util.Map.of("RewardOnce", Codec.BOOLEAN),
                                (RewardPayload payload, DialogueSugarValues values) -> {
                                    RewardGrant grant = new RewardGrant();
                                    grant.reward = payload;
                                    grant.once = values.flag("RewardOnce", true);
                                    return grant;
                                })))
                .build();
    }

    @Test
    void aReleasedMinigamesConversationStillLoads() {
        DialogueEngine engine = engineForShippedContent();
        ZcDialogueAsset asset = read(engine, "Clash_Intro.json", "clash_intro");

        NpcDialogue d = asset.getDialogue();
        assertNotNull(d);
        assertEquals("clash_intro", d.getId());
        assertEquals(4, d.getNodes().size());
        assertEquals("greet", d.getStart().get(0).getNode());
        // "Play": "clash_1v1" is a shorthand the MINIGAME registered, folded into its own action.
        DialogueOption play = d.getNode("mode_pick").getOptions().get(0);
        assertEquals(1, play.getActions().size());
        assertTrue(play.getActions().get(0) instanceof OpenPlay);
        assertEquals("clash_1v1", ((OpenPlay) play.getActions().get(0)).preset);
    }

    @Test
    void theHubConversationLoadsWithEveryAuthoringShapeItUses() {
        DialogueEngine engine = engineForShippedContent();
        NpcDialogue d = read(engine, "Mmo_Hub_Intro.json", "mmo_hub_intro").getDialogue();
        assertNotNull(d);

        // A declared memory, read by a Start candidate and written by an option. It is kept per
        // world by a CONTAINS pattern, because the temple is an instance world whose name carries a
        // fresh uuid every time it is built - an exact name would forget the greeting each visit.
        assertNotNull(d.getMemory("temple_greeted"));
        assertEquals("*Forgotten_Temple*", d.getMemory("temple_greeted").getWorld());

        // The Start ladder keeps its authored order, world gate first.
        assertEquals("temple_greet", d.getStart().get(0).getNode());
        assertTrue(d.getStart().get(0).getConditions().get(0) instanceof DialogueCondition.World);

        // Shared option groups are declared once and spliced into every screen that names them.
        assertFalse(d.getFragments().isEmpty());
        DialogueNode menu = d.getNode("menu");
        assertNotNull(menu);
        assertFalse(menu.getIncludeOptions().isEmpty());
        assertTrue(menu.getOptions().size() > menu.getIncludeOptions().size(),
                "the screen keeps its own options and gains the shared ones");

        // A Do atom pair folds to the two actions it names, in array order.
        DialogueOption skip = optionWithDo(d.getNode("greet"));
        assertNotNull(skip, "the greet screen has the skip option authored with Do");
        assertEquals(3, skip.getActions().size());
        assertTrue(skip.getActions().get(0) instanceof CompleteQuest);
        assertTrue(skip.getActions().get(2) instanceof DialogueAction.Goto);
    }

    @Test
    void aContentPacksGuideLoadsWithItsRewardAndBareOnce() {
        DialogueEngine engine = engineForShippedContent();
        NpcDialogue d = read(engine, "Guide_Wilds_Dialogue.json", "guide_wilds_dialogue").getDialogue();
        assertNotNull(d);

        DialogueOption bread = d.getNode("camp_talk").getOptions().get(0);
        assertNotNull(bread.getOnce(), "\"Once\": true is still the bare flag form");
        // Reward(40) then Close(70), both authored inside Do, with RewardOnce read as its modifier.
        assertEquals(2, bread.getActions().size());
        RewardGrant grant = (RewardGrant) bread.getActions().get(0);
        assertNotNull(grant.reward);
        assertEquals("COMMAND", grant.reward.type);
        assertFalse(grant.once, "the authored RewardOnce:false is carried into the action");
        assertTrue(bread.getActions().get(1) instanceof DialogueAction.Close);
    }

    // ==================== helpers ====================

    @Nullable
    private static DialogueOption optionWithDo(@Nullable DialogueNode node) {
        if (node == null) {
            return null;
        }
        for (DialogueOption option : node.getOptions()) {
            if (option.hasDoAtoms()) {
                return option;
            }
        }
        return null;
    }

    /** Read a shipped file through the real asset codec, exactly as the asset store would. */
    @Nonnull
    private static ZcDialogueAsset read(@Nonnull DialogueEngine engine, @Nonnull String file,
                                        @Nonnull String expectedId) {
        assertNotNull(engine, "the vocabulary must be registered before anything is read");
        String json = load(file);
        try {
            ZcDialogueAsset asset = ZcDialogueAsset.CODEC.decodeJson(
                    RawJsonReader.fromJsonString(json), new AssetExtraInfo<String>((AssetExtraInfo.Data) null));
            assertNotNull(asset, file + " must decode");
            // The store names an asset after its FILE; standing in for that here keeps the fixture
            // decode identical to the real one without inventing a setter nothing else needs.
            if (asset.getDialogue() != null) {
                asset.getDialogue().setId(expectedId);
            }
            return asset;
        } catch (Exception e) {
            throw new AssertionError("shipped dialogue '" + file + "' no longer loads: " + e, e);
        }
    }

    @Nonnull
    private static String load(@Nonnull String file) {
        try (InputStream in = DialogueAuthoredFixtureTest.class
                .getResourceAsStream("/fixtures/dialogue/" + file)) {
            assertNotNull(in, "missing fixture " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Nonnull
    private static <C extends DialogueCondition> DialogueConditionType<C> paramless(
            @Nonnull String typeId, @Nonnull Class<C> type, @Nonnull Codec<C> codec) {
        return DialogueConditionType.of(typeId, type, codec, (c, ctx) -> true);
    }

    // ==================== stand-ins for the mods' own vocabulary ====================

    public static final class NotInRound extends DialogueCondition {
        public static final BuilderCodec<NotInRound> CODEC =
                BuilderCodec.builder(NotInRound.class, NotInRound::new).build();
    }

    public static final class Engaged extends DialogueCondition {
        public static final BuilderCodec<Engaged> CODEC =
                BuilderCodec.builder(Engaged.class, Engaged::new).build();
    }

    public static final class HasActiveBounties extends DialogueCondition {
        public static final BuilderCodec<HasActiveBounties> CODEC =
                BuilderCodec.builder(HasActiveBounties.class, HasActiveBounties::new).build();
    }

    public static final class HasOfferableQuests extends DialogueCondition {
        public static final BuilderCodec<HasOfferableQuests> CODEC =
                BuilderCodec.builder(HasOfferableQuests.class, HasOfferableQuests::new).build();
    }

    public static final class OpenPlay extends DialogueAction {
        public static final BuilderCodec<OpenPlay> CODEC =
                BuilderCodec.builder(OpenPlay.class, OpenPlay::new)
                        .append(new KeyedCodec<>("Preset", Codec.STRING, false),
                                (a, v) -> a.preset = v, a -> a.preset).add()
                        .build();

        @Nullable String preset;
    }

    public static final class CompleteQuest extends DialogueAction {
        public static final BuilderCodec<CompleteQuest> CODEC =
                BuilderCodec.builder(CompleteQuest.class, CompleteQuest::new)
                        .append(new KeyedCodec<>("Quest", Codec.STRING, false),
                                (a, v) -> a.quest = v, a -> a.quest).add()
                        .build();

        @Nullable String quest;
    }

    /** The stand-in for the MMO's unified reward object, enough of it to prove the shape reads. */
    public static final class RewardPayload {
        public static final BuilderCodec<RewardPayload> CODEC =
                BuilderCodec.builder(RewardPayload.class, RewardPayload::new)
                        .append(new KeyedCodec<>("Type", Codec.STRING, false),
                                (r, v) -> r.type = v, r -> r.type).add()
                        .append(new KeyedCodec<>("Command", Codec.STRING, false),
                                (r, v) -> r.command = v, r -> r.command).add()
                        .build();

        @Nullable String type;
        @Nullable String command;
    }

    public static final class RewardGrant extends DialogueAction {
        public static final BuilderCodec<RewardGrant> CODEC =
                BuilderCodec.builder(RewardGrant.class, RewardGrant::new)
                        .append(new KeyedCodec<>("Once", Codec.BOOLEAN, false),
                                (a, v) -> a.once = v, a -> a.once).add()
                        .append(new KeyedCodec<>("Reward", RewardPayload.CODEC, false),
                                (a, v) -> a.reward = v, a -> a.reward).add()
                        .build();

        boolean once = true;
        @Nullable RewardPayload reward;
    }

    /** Kept so an unused-import check cannot drop the codec-graph import the stand-ins rely on. */
    static ExtraInfo extraInfo() {
        return new ExtraInfo();
    }
}
