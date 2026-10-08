package com.ziggfreed.common.objectives.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.render.ClaimToasts;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * How a {@code ZigGrantReward} node pays: its {@code Rewards} list (the entry shape a quest pays in),
 * rolled once against its {@code Chance}, handed to the chain's player through the shared reward
 * vocabulary with the shared retry queue behind it. Everything but {@link #subjectFor}, {@link #chips}
 * and {@link #toPlayer} is pure.
 *
 * <p><b>A payout here tells the player what it handed over</b> ({@link #payAndShow}), the way a quest's
 * Collect does: the same gold claim toast ({@link ClaimToasts#rewardToast}) with one row per thing its
 * receipt lists, read through the same chip source the book reads a quest's rewards with, so a rolled
 * table reads as the currency, items, costume or favor it actually paid and never as its own name. Both
 * payout sites outside a quest pay through here, a conversation's {@code Grant} and a used item's
 * {@code ZigGrantReward}, so neither keeps a toast of its own.
 */
public final class InteractionRewards {

    /** Every payout here is labelled {@code interaction:<item id>}, or the Type name when no item names it. */
    public static final String SOURCE_PREFIX = "interaction:";

    /** The library's lang namespace and the file the receipt toast's words live in. */
    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    /** The receipt toast's headline ("Rewards received."), in {@code ziggfreedcommon.progression.lang}. */
    static final String RECEIVED_KEY = "reward.received";

    private InteractionRewards() {
    }

    /** The rewards {@code entries} pay, in order; an entry naming no Kind pays nothing and is dropped. */
    @Nonnull
    public static List<RewardSpec> specs(@Nullable RewardEntryAsset[] entries) {
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        List<RewardSpec> out = new ArrayList<>(entries.length);
        for (RewardEntryAsset entry : entries) {
            RewardSpec spec = entry == null ? null : entry.toSpec();
            if (spec != null) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Does a roll at {@code chance} land? Absent, or 1 and above, always; 0 and below, or not a
     * number, never; otherwise when {@code random} draws below it.
     */
    public static boolean rolls(@Nullable Float chance, @Nonnull DoubleSupplier random) {
        if (chance == null) {
            return true;
        }
        float value = chance;
        if (Float.isNaN(value) || value <= 0f) {
            return false;
        }
        return value >= 1f || random.getAsDouble() < value;
    }

    /** {@code interaction:<item id>}, or {@code interaction:<type>} when no item names the use. */
    @Nonnull
    public static String sourceId(@Nullable String usedItemId, @Nonnull String typeName) {
        return SOURCE_PREFIX + (usedItemId == null || usedItemId.isBlank() ? typeName : usedItemId.trim());
    }

    /** Pay {@code rewards} to {@code subject}, who is here to receive them; never throws. */
    @Nonnull
    public static RewardGrants.GrantOutcome pay(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
            @Nonnull String sourceId, @Nonnull RewardKindRegistry kinds,
            @Nullable BiConsumer<Subject, String> retryQueue) {
        return RewardGrants.grantAll(rewards, subject, sourceId, kinds, true, retryQueue, SafeLog::warn);
    }

    /**
     * {@link #pay}, then hand {@code show} the toast saying what the payout handed over ({@link #receiptToast},
     * read through {@code chips}); nothing when it handed nothing over, and nothing composed at all with no
     * {@code show}. A toast that fails to show costs the toast, never the payout. Never throws.
     */
    @Nonnull
    public static RewardGrants.GrantOutcome payAndShow(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
            @Nonnull String sourceId, @Nonnull RewardKindRegistry kinds,
            @Nullable BiConsumer<Subject, String> retryQueue, @Nullable RewardChips.Source chips,
            @Nullable Consumer<ToastSpec> show) {
        RewardGrants.GrantOutcome paid = pay(rewards, subject, sourceId, kinds, retryQueue);
        if (show == null) {
            return paid;
        }
        try {
            ToastSpec toast = receiptToast(paid, chips);
            if (toast != null) {
                show.accept(toast);
            }
        } catch (Throwable t) {
            SafeLog.fine("[interaction] the toast for '" + sourceId + "' could not be shown: " + t.getMessage());
        }
        return paid;
    }

    /**
     * The gold toast for what {@code paid} handed over: "Rewards received." over one row per entry of its
     * receipt read through {@code chips}, capped on the book's "+N more" line; null when the receipt is empty
     * (a lost roll, a reward that only queued or failed), since a toast over nothing reads as a payout of
     * nothing. It carries {@link ToastSounds#RECEIPT}, played wherever it shows: no moment fires for a payout
     * outside a quest, so the toast owns the sound.
     */
    @Nullable
    public static ToastSpec receiptToast(@Nonnull RewardGrants.GrantOutcome paid,
            @Nullable RewardChips.Source chips) {
        if (paid.receipt().isEmpty()) {
            return null;
        }
        return ClaimToasts.rewardToast(Msg.tr(PREFIX, DOMAIN + RECEIVED_KEY), paid.receipt(), chips,
                InteractionRewards::more).withSound(ToastSounds.RECEIPT);
    }

    /**
     * The chip source a receipt toast here reads through: the book's ({@link ObjectiveBookPages#resolvedDeps}),
     * the consumer's own reading where one is installed, so a Grant and a quest's Collect name a reward alike.
     */
    @Nonnull
    public static RewardChips.Source chips() {
        return ObjectiveBookPages.resolvedDeps().rewardChips();
    }

    /**
     * Where a receipt toast goes in play: to {@code playerRef}, where they are looking once the line or the use
     * that paid has settled ({@link ToastDelivery#deliverWhenSettled}): into the page they have open, else the
     * corner feed.
     */
    @Nonnull
    public static Consumer<ToastSpec> toPlayer(@Nonnull PlayerRef playerRef) {
        return toast -> ToastDelivery.deliverWhenSettled(playerRef, toast, InteractionRewards::more);
    }

    /** "+N more", the book's own overflow line. */
    @Nonnull
    private static Message more(int dropped) {
        return Msg.tr(PREFIX, DOMAIN + "book.more", dropped);
    }

    /**
     * Who a payout to the player at {@code ref} is for: the runtime's own subject, so a consumer's
     * store and notices see it the way they see a quest payout; the library's reference-backed one
     * when the runtime cannot say. World thread. Public for the other payout site over this core, the
     * dialogue {@code Grant} action ({@code objectives/dialogue/GrantDialogueAction}).
     */
    @Nonnull
    public static Subject subjectFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        try {
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
            if (subject != null) {
                return subject;
            }
        } catch (Throwable t) {
            SafeLog.warn("[interaction] the runtime could not name the player a reward is for, so the"
                    + " library's own identity answers", t);
        }
        String name = playerRef.getUsername();
        return PlayerRefSubjectHandle.subjectFor(playerRef, name == null ? "" : name);
    }
}
