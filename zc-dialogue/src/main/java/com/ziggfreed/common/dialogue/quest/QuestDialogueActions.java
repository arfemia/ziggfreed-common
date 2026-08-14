package com.ziggfreed.common.dialogue.quest;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.DialogueAction;
import com.ziggfreed.common.dialogue.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.DialogueActionType;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueOptionStyle;
import com.ziggfreed.common.dialogue.DialogueSugar;
import com.ziggfreed.common.subject.Subject;

/**
 * The two things a conversation genuinely does to a quest: take it on, and hand it in. Authored as
 * the shorthand {@code {"Accept": "<quest>"}} and {@code {"TurnIn": "<quest>"}}, or as the canonical
 * {@code {"Type":"AcceptQuest","Quest":"<quest>"}} / {@code {"Type":"TurnInQuest","Quest":"<quest>"}}.
 *
 * <p>Both go through the {@link DialogueQuests} seam, which refuses by default - so a mod that wired
 * only the read side gets quest-aware LINES with no way for a conversation to change anything, and
 * has to opt in before a dialogue can start or finish a quest.
 *
 * <p>A hand-in reports the quest id back to the page as just-completed when it went through, which
 * is what floats the completion toast over the conversation instead of interrupting it. A consumer
 * that needs richer behaviour - its own feedback, its own gate messaging - re-registers the same
 * {@code Type} id with its own handler and keeps the authored files exactly as they are.
 */
public final class QuestDialogueActions {

    /** Registered under this {@code Type}, written as the shorthand {@code "Accept"}. */
    public static final String ACCEPT_QUEST = "AcceptQuest";

    /** Registered under this {@code Type}, written as the shorthand {@code "TurnIn"}. */
    public static final String TURN_IN_QUEST = "TurnInQuest";

    /**
     * Where the two shorthands sit in the bare-key fold order. Accepting comes before handing in,
     * and both come after {@code Talk} (10) and before the memory writes (32/33), so a line that
     * greets, takes a quest on and records that it did runs in the order it reads.
     */
    private static final int ACCEPT_ORDER = 20;
    private static final int TURN_IN_ORDER = 30;

    private QuestDialogueActions() {
    }

    /** Every quest-aware action type, acting through {@code quests} at execution time. */
    @Nonnull
    public static List<DialogueActionType<?>> types(@Nonnull Supplier<DialogueQuests> quests) {
        return List.of(
                DialogueActionType.of(ACCEPT_QUEST, AcceptQuest.class, AcceptQuest.CODEC,
                                (AcceptQuest a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                        accept(a, quests.get(), ctx))
                        .withStyle(DialogueOptionStyle.ACCEPT)
                        .withSugar(DialogueSugar.string("Accept", ACCEPT_ORDER, questId -> {
                            AcceptQuest action = new AcceptQuest();
                            action.quest = questId;
                            return action;
                        })),
                DialogueActionType.of(TURN_IN_QUEST, TurnInQuest.class, TurnInQuest.CODEC,
                                (TurnInQuest a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                        turnIn(a, quests.get(), ctx, out))
                        .withStyle(DialogueOptionStyle.TURN_IN)
                        .withSugar(DialogueSugar.string("TurnIn", TURN_IN_ORDER, questId -> {
                            TurnInQuest action = new TurnInQuest();
                            action.quest = questId;
                            return action;
                        })));
    }

    private static void accept(@Nonnull AcceptQuest action, @Nonnull DialogueQuests quests,
                               @Nonnull DialogueExecContext ctx) {
        String questId = action.normalizedQuest();
        if (questId != null) {
            // The character the conversation is WITH is where the quest was taken on, which is what
            // lets "report back to whoever gave me this" resolve later. The context id, not an
            // answered alias: the story means the character the player is looking at.
            quests.accept(quests.subject(ctx), questId, ctx.contextId());
        }
    }

    /**
     * Hand the quest in at the first id the character answers to that will take it. Trying the whole
     * answer set is what lets one quest report back at any of the places its giver stands.
     */
    private static void turnIn(@Nonnull TurnInQuest action, @Nonnull DialogueQuests quests,
                               @Nonnull DialogueExecContext ctx,
                               @Nonnull DialogueActionExecutor.Mut out) {
        String questId = action.normalizedQuest();
        if (questId == null) {
            return;
        }
        Subject subject = quests.subject(ctx);
        for (String at : quests.answersTo(ctx.contextId())) {
            if (quests.turnIn(subject, questId, at)) {
                out.reportCompleted(questId);
                return;
            }
        }
    }

    /** The shared shape: which quest the line acts on. */
    public abstract static class QuestRef extends DialogueAction {

        @Nullable protected String quest;

        /** The quest id, or null when unauthored. */
        @Nullable
        public String getQuest() {
            return quest;
        }

        @Nullable
        final String normalizedQuest() {
            return quest == null || quest.isBlank() ? null : quest.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Take a quest on: {@code {"Accept": "getting_started"}}. */
    public static final class AcceptQuest extends QuestRef {

        public static final BuilderCodec<AcceptQuest> CODEC =
                BuilderCodec.builder(AcceptQuest.class, AcceptQuest::new)
                        .append(new KeyedCodec<>("Quest", Codec.STRING, false),
                                (a, v) -> a.quest = v, a -> a.quest)
                        .documentation("The quest this line starts.").add()
                        .build();
    }

    /** Hand a quest in here: {@code {"TurnIn": "craft_starter_tools"}}. */
    public static final class TurnInQuest extends QuestRef {

        public static final BuilderCodec<TurnInQuest> CODEC =
                BuilderCodec.builder(TurnInQuest.class, TurnInQuest::new)
                        .append(new KeyedCodec<>("Quest", Codec.STRING, false),
                                (a, v) -> a.quest = v, a -> a.quest)
                        .documentation("The quest this line hands in. Pair it with a ReadyToTurnIn "
                                + "condition so the line only shows when it will actually go through.").add()
                        .build();
    }
}
