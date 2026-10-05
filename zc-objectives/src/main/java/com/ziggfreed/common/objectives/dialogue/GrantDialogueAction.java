package com.ziggfreed.common.objectives.dialogue;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.schema.DialogueSugar;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.type.DialogueActionType;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.interaction.InteractionRewards;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * {@code Grant}: a conversation line that pays the player who chose it, in the entry shape a quest pays
 * in.
 *
 * <pre>{@code
 * { "LabelKey": "yourpack.npc.gift", "Once": true,
 *   "Grant": [ { "Kind": "Lootable", "Params": { "Lootable": "Yourpack_Gifts" } } ] }
 *
 * { "LabelKey": "yourpack.npc.maybe", "Actions": [
 *     { "Type": "Grant", "Chance": 0.5,
 *       "Rewards": [ { "Kind": "Item", "Params": { "Item": "Rock_Stone", "Count": 2 } } ] } ] }
 * }</pre>
 *
 * <p>The entries pay through {@link InteractionRewards}, the one payout core outside a quest (the
 * {@code ZigGrantReward} interaction pays through it too): the registered reward kinds, the runtime's
 * own subject and its retry queue, so a kind any mod registers pays here the day it is installed.
 *
 * <p><b>It pays every time its line runs.</b> The line's own {@code Once} (one time, or with a
 * {@code Period}, once a day or a week) is the guard, so this action keeps no once-key of its own: a key
 * of its own would be filed per conversation, while a line one file puts into many conversations is
 * spent everywhere at once by its {@code Once}.
 *
 * <p>Registered as {@code Grant}, never {@code Reward}: a consumer may own that Type and its shorthand,
 * and this library registers first, so taking it would read that consumer's files into this action.
 */
public final class GrantDialogueAction {

    /** The authored {@code "Type"}. */
    public static final String TYPE_ID = "Grant";

    /** The option-level shorthand: {@code "Grant": [ <reward entries> ]}. */
    public static final String SHORTHAND = "Grant";

    /**
     * Where the shorthand folds among an option's bare keys: after the quest band (20 to 33) and a
     * consumer's own reward (40), before a command (45), an open (50), a jump (60) and a close (70), so a
     * line that takes a quest, pays and moves on runs in the order it reads.
     */
    static final int SHORTHAND_ORDER = 42;

    /** Every payout here is labelled {@code dialogue:<conversation id>}. */
    public static final String SOURCE_PREFIX = "dialogue:";

    private GrantDialogueAction() {
    }

    /** The action itself: {@code {"Type": "Grant", "Rewards": [...], "Chance": 0.5}}. */
    public static final class Grant extends DialogueAction {

        /** The entry list, shared by the full form's {@code Rewards} and the shorthand. */
        static final ArrayCodec<RewardEntryAsset> REWARDS =
                new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new);

        public static final BuilderCodec<Grant> CODEC = BuilderCodec.builder(Grant.class, Grant::new)
                .append(new KeyedCodec<>("Rewards", REWARDS, false),
                        (a, v) -> a.rewards = v, a -> a.rewards)
                .documentation("What the line pays the player who chose it, in the entry shape a quest pays "
                        + "in: a Kind plus its Params. It pays every time the line runs, so give the line a "
                        + "Once to pay it one time, or a Once with a Period to pay it once a day or a week.")
                .add()
                .append(new KeyedCodec<>("Chance", Codec.FLOAT, false),
                        (a, v) -> a.chance = v, a -> a.chance)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("The chance from 0 to 1 that the line pays at all, rolled once each time it "
                        + "runs. Absent, it always pays.")
                .add()
                .build();

        @Nullable protected RewardEntryAsset[] rewards;
        /** Boxed, so an absent leaf keeps the default instead of failing the decode. */
        @Nullable protected Float chance;

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        static Grant of(@Nullable RewardEntryAsset[] rewards, @Nullable Float chance) {
            Grant grant = new Grant();
            grant.rewards = rewards;
            grant.chance = chance;
            return grant;
        }

        /** The authored entries (a copy), or null when there are none. */
        @Nullable
        public RewardEntryAsset[] getRewards() {
            return rewards == null ? null : rewards.clone();
        }

        /** The authored chance, or null when the line always pays. */
        @Nullable
        public Float getChance() {
            return chance;
        }
    }

    /** The registration: the Type, its codec and handler, and the shorthand. */
    @Nonnull
    public static DialogueActionType<Grant> type() {
        return DialogueActionType.of(TYPE_ID, Grant.class, Grant.CODEC,
                        (Grant action, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> handle(action, ctx))
                .withSugar(DialogueSugar.of(SHORTHAND, SHORTHAND_ORDER, Grant.REWARDS,
                        (entries, values) -> Grant.of(entries, null)));
    }

    /** {@code dialogue:<conversation id>}, or {@code dialogue:Grant} when the conversation names none. */
    @Nonnull
    public static String sourceId(@Nullable String dialogueId) {
        return SOURCE_PREFIX + (dialogueId == null || dialogueId.isBlank() ? TYPE_ID : dialogueId.trim());
    }

    /**
     * Pay {@code grant}: its entries, if it has any and its chance lands, to whoever {@code subject}
     * names. The subject is asked for only once there is something to pay; a null answer pays nothing
     * and is not a loss. Never throws.
     */
    @Nonnull
    static RewardGrants.GrantOutcome pay(@Nonnull Grant grant, @Nonnull Supplier<Subject> subject,
            @Nonnull String sourceId, @Nonnull RewardKindRegistry kinds,
            @Nullable BiConsumer<Subject, String> retryQueue, @Nonnull DoubleSupplier random) {
        List<RewardSpec> specs = InteractionRewards.specs(grant.rewards);
        if (specs.isEmpty() || !InteractionRewards.rolls(grant.chance, random)) {
            return RewardGrants.GrantOutcome.EMPTY;
        }
        Subject who = subject.get();
        if (who == null) {
            return RewardGrants.GrantOutcome.EMPTY;
        }
        return InteractionRewards.pay(specs, who, sourceId, kinds, retryQueue);
    }

    /** The line ran: pay through the shared vocabulary, to the player who chose it. World thread. */
    private static void handle(@Nonnull Grant grant, @Nonnull DialogueExecContext ctx) {
        pay(grant, () -> subjectOf(ctx), sourceId(ctx.dialogue().getId()), RewardKinds.shared(),
                ProgressionRuntime.rewardRetryQueue(), ThreadLocalRandom.current()::nextDouble);
    }

    /** The runtime's subject for the player who chose the line, or null when no player is there. */
    @Nullable
    private static Subject subjectOf(@Nonnull DialogueExecContext ctx) {
        PlayerRef playerRef = ctx.playerRef();
        if (playerRef == null) {
            SafeLog.fine("[dialogue] " + TYPE_ID + " in '" + ctx.dialogue().getId()
                    + "': no player chose this line, so nothing is paid");
            return null;
        }
        return InteractionRewards.subjectFor(ctx.store(), ctx.ref(), playerRef);
    }
}
