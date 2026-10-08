package com.ziggfreed.common.objectives.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * How a {@code ZigGrantReward} node pays: its {@code Rewards} list (the entry shape a quest pays in),
 * rolled once against its {@code Chance}, handed to the chain's player through the shared reward
 * vocabulary with the shared retry queue behind it. Everything but {@link #subjectFor} is pure.
 */
public final class InteractionRewards {

    /** Every payout here is labelled {@code interaction:<item id>}, or the Type name when no item names it. */
    public static final String SOURCE_PREFIX = "interaction:";

    private InteractionRewards() {
    }

    /**
     * The rewards {@code entries} pay, in order; an entry naming no Kind, or one whose own {@code Requires}
     * names a mod this server lacks, pays nothing and is dropped. No store folds an inline list, so such a
     * row is never counted in a mod-gate line.
     */
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
