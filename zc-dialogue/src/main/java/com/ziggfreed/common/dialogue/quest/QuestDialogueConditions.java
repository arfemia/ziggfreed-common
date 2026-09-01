package com.ziggfreed.common.dialogue.quest;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.type.DialogueConditionType;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * The quest-aware conditions every dialogue vocabulary ships with: is this quest at that point yet,
 * is it ready to hand in HERE, and is anything at all ready to hand in here.
 *
 * <p>They are part of the generic vocabulary rather than a mod's own because a conversation about a
 * quest is what quests are FOR - a giver with nothing to say about the state of what it gave out is
 * not a giver. What makes them safe to ship generically is that they only ever ask
 * {@link QuestStateReader} questions, through the {@link DialogueQuests} seam the consumer wired: no
 * catalogue, no gates, no mutation, and a refusal when nothing is wired.
 *
 * <p>"Does this character have anything to OFFER" is the fourth, and it is here as a CLASS but not in
 * {@link #types}. See {@link #offerableType} for what to do with it and why it is not seeded.
 */
public final class QuestDialogueConditions {

    /** Registered under this {@code Type}: {@code {"Type":"QuestState","Quest":"x","State":"ACTIVE"}}. */
    public static final String QUEST_STATE = "QuestState";

    /** Registered under this {@code Type}: {@code {"Type":"ReadyToTurnIn","Quest":"x"}}. */
    public static final String READY_TO_TURN_IN = "ReadyToTurnIn";

    /** Registered under this {@code Type}: {@code {"Type":"HasReadyToTurnIn"}}. */
    public static final String HAS_READY_TO_TURN_IN = "HasReadyToTurnIn";

    /** The {@code Type} {@link #offerableType} registers under: {@code {"Type":"HasOfferableQuests"}}. */
    public static final String HAS_OFFERABLE_QUESTS = "HasOfferableQuests";

    private QuestDialogueConditions() {
    }

    /** Every quest-aware condition type, reading through {@code quests} at evaluation time. */
    @Nonnull
    public static List<DialogueConditionType<?>> types(@Nonnull Supplier<DialogueQuests> quests) {
        return List.of(
                DialogueConditionType.of(QUEST_STATE, QuestState.class, QuestState.CODEC,
                        (QuestState c, DialogueContext ctx) -> c.passes(quests.get(), ctx)),
                DialogueConditionType.of(READY_TO_TURN_IN, ReadyToTurnIn.class, ReadyToTurnIn.CODEC,
                        (ReadyToTurnIn c, DialogueContext ctx) -> c.passes(quests.get(), ctx)),
                DialogueConditionType.of(HAS_READY_TO_TURN_IN, HasReadyToTurnIn.class,
                        HasReadyToTurnIn.CODEC,
                        (HasReadyToTurnIn c, DialogueContext ctx) -> anywhereHere(quests.get(), ctx)));
    }

    /**
     * The "have you anything for me" condition, ready to register but NOT seeded with the rest.
     *
     * <p>It is generic now because the missing half finally exists: {@link NpcOfferProviders} is where
     * a mod's catalogue and its gates answer, so the condition asks rather than guesses, and a
     * conversation written against it works in ANY mod that registered a provider.
     *
     * <p>It is not seeded because a dialogue {@code Type} id resolves to ONE class in a process-wide
     * table. A consumer that already ships its own {@code HasOfferableQuests} would find every
     * installed mod's files decoding into whichever class registered last, so the switch has to be a
     * deliberate act by that consumer: register THIS type, in place of your own, and drop yours in the
     * same change. A consumer with no condition of its own can register it straight away.
     */
    @Nonnull
    public static DialogueConditionType<?> offerableType(@Nonnull Supplier<DialogueQuests> quests) {
        return DialogueConditionType.of(HAS_OFFERABLE_QUESTS, HasOfferableQuests.class,
                HasOfferableQuests.CODEC,
                (HasOfferableQuests c, DialogueContext ctx) -> anythingOffered(quests.get(), ctx));
    }

    /** True when any registered provider has something available at this character. */
    private static boolean anythingOffered(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
        if (!NpcOfferProviders.hasAny()) {
            return false;
        }
        return NpcOfferProviders.hasOffersAt(quests.subject(ctx), quests.answersTo(ctx.contextId()));
    }

    /** True when the player can hand SOMETHING in at any id the character in front of them answers to. */
    private static boolean anywhereHere(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
        Subject subject = quests.subject(ctx);
        for (String id : quests.answersTo(ctx.contextId())) {
            if (quests.reader().hasSettleableTurnInAt(subject, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shared shape of the two per-quest conditions: which quest the line is about.
     */
    public abstract static class QuestRef extends DialogueCondition {

        @Nullable protected String quest;

        /** The quest id this condition is about, or null when unauthored. */
        @Nullable
        public String getQuest() {
            return quest;
        }

        @Nullable
        final String normalizedQuest() {
            return quest == null || quest.isBlank() ? null : quest.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Show this line only while the quest is at a given point:
     * {@code {"Type":"QuestState","Quest":"getting_started","State":"ACTIVE"}}, or
     * {@code "States":["NOT_STARTED","ACTIVE"]} to accept any of several.
     *
     * <p>The state read is the EFFECTIVE one, so a finished daily whose cooldown has elapsed reads as
     * offerable again and its accept line comes back on its own. A condition with no quest id, or one
     * naming a state that does not exist, shows nothing - a malformed line should not decide what a
     * player sees, and the content audit names it.
     */
    public static final class QuestState extends QuestRef {

        public static final BuilderCodec<QuestState> CODEC =
                BuilderCodec.builder(QuestState.class, QuestState::new)
                        .append(new KeyedCodec<>("Quest", Codec.STRING, false),
                                (c, v) -> c.quest = v, c -> c.quest)
                        .documentation("Which quest this line is about.").add()
                        .append(new KeyedCodec<>("State", Codec.STRING, false),
                                (c, v) -> c.state = v, c -> c.state)
                        .documentation("The one state the quest must be in: NOT_STARTED, ACTIVE, "
                                + "COMPLETED, COMPLETED_UNCLAIMED or ON_COOLDOWN.").add()
                        .append(new KeyedCodec<>("States", Codec.STRING_ARRAY, false),
                                (c, v) -> c.states = v, c -> c.states)
                        .documentation("Several acceptable states instead of one; the line shows while the "
                                + "quest is in ANY of them. Takes precedence over State.").add()
                        .build();

        @Nullable protected String state;
        @Nullable protected String[] states;

        @Nullable public String getState() { return state; }

        @Nullable public String[] getStates() { return states == null ? null : states.clone(); }

        boolean passes(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
            String questId = normalizedQuest();
            if (questId == null) {
                return false;
            }
            QuestStatus actual = quests.reader().status(quests.subject(ctx), questId);
            if (states != null && states.length > 0) {
                for (String wanted : states) {
                    if (actual == parse(wanted)) {
                        return true;
                    }
                }
                return false;
            }
            return actual == parse(state);
        }

        /**
         * A state name, or null when it is blank or names nothing - which can never equal a real
         * status, so a typo hides the line instead of quietly matching NOT_STARTED.
         */
        @Nullable
        public static QuestStatus parse(@Nullable String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return QuestStatus.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Show this line only when handing THIS quest in right here would FINISH it:
     * {@code {"Type":"ReadyToTurnIn","Quest":"craft_starter_tools"}}.
     *
     * <p>It checks what the player is carrying, not just that the quest is this character's, so a
     * line written to greet a completed errand is not spoken to somebody who has done a third of it.
     * A player part way through can still hand over what they have from the quest list; this is the
     * line that says "you are done", and it waits until they are. Fails closed on an unknown id.
     */
    public static final class ReadyToTurnIn extends QuestRef {

        public static final BuilderCodec<ReadyToTurnIn> CODEC =
                BuilderCodec.builder(ReadyToTurnIn.class, ReadyToTurnIn::new)
                        .append(new KeyedCodec<>("Quest", Codec.STRING, false),
                                (c, v) -> c.quest = v, c -> c.quest)
                        .documentation("Which quest can be handed in here.").add()
                        .build();

        boolean passes(@Nonnull DialogueQuests quests, @Nonnull DialogueContext ctx) {
            String questId = normalizedQuest();
            if (questId == null) {
                return false;
            }
            Subject subject = quests.subject(ctx);
            for (String id : quests.answersTo(ctx.contextId())) {
                if (quests.reader().settlesTurnInAt(subject, questId, id)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Show this line when the player has ANY quest that would FINISH here:
     * {@code {"Type":"HasReadyToTurnIn"}}.
     *
     * <p>One "I have returned" line that keeps working as quests are added, with no list to maintain.
     * It waits for an errand that is actually done: a player still out gathering has nothing to have
     * returned WITH, and greeting them as though they had reads as the character not paying
     * attention. A part-load is still accepted, from the quest list this conversation can open.
     */
    public static final class HasReadyToTurnIn extends DialogueCondition {

        public static final BuilderCodec<HasReadyToTurnIn> CODEC =
                BuilderCodec.builder(HasReadyToTurnIn.class, HasReadyToTurnIn::new).build();
    }

    /**
     * Show this line when the character has any quest the player could take on right now:
     * {@code {"Type":"HasOfferableQuests"}}.
     *
     * <p>The one "I might have work for you" line that keeps working as content is added, across every
     * mod that registered an offer provider - so a server running two quest mods gets one greeting
     * that knows about both. Only AVAILABLE offers count: a quest the player can see but not yet take
     * is not something to hail them about.
     */
    public static final class HasOfferableQuests extends DialogueCondition {

        public static final BuilderCodec<HasOfferableQuests> CODEC =
                BuilderCodec.builder(HasOfferableQuests.class, HasOfferableQuests::new).build();
    }
}
