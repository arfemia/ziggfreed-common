package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.reputation.event.ReputationEvents;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.hud.panel.HudBarReading;
import com.ziggfreed.common.ui.hud.panel.HudPanels;
import com.ziggfreed.common.ui.hud.panel.HudRowDisplay;
import com.ziggfreed.common.util.SafeLog;

/**
 * The production {@link ReputationFanOut}: an earned change fires {@code ZigReputationChangedEvent}, moves
 * the reputation's World bar ({@code reputation:<id>}, its reading effective standing's progress through
 * its rank) and pays the Beyond rewards once per crossing; a credit fires {@code ZigReputationRanksHeldEvent}
 * (zc-objectives turns it into {@code REPUTATION_RANK}); a rise during play raises the {@code Reputation_Rank}
 * notice. Each part is guarded on its own. The World panel already honours a player's hidden and HideAll
 * choices, so the bar needs nothing of its own for them.
 */
public final class EngineReputationFanOut implements ReputationFanOut {

    /** The moment a rise is authored under, and the shipped default file's name. */
    public static final String RANK_MOMENT = "Reputation_Rank";

    /** A reputation's bar is the row {@code reputation:<id>} on the World panel. */
    public static final String ROW_PREFIX = "reputation:";

    /** The most Beyond payouts one change pays, so one huge gain never queues a flood of rewards. */
    static final int MAX_BEYOND_PAYOUTS = 20;

    /** The fixed argument the feedback engine draws a toast's picture from (an item id). */
    static final String ICON_ARG = "icon";

    private static final String PAYOUT_SOURCE = "reputation:";

    @Override
    public void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        ReputationEvents.fireChanged(change);
        if (store == null || ref == null) {
            return;
        }
        moveBar(store, ref, change);
        if (change.beyondCrossings() > 0) {
            payBeyond(store, ref, change);
        }
    }

    @Override
    public void credit(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID playerId,
            @Nonnull ReputationDef reputation, @Nonnull List<ReputationLadder.Rank> held) {
        if (!held.isEmpty()) {
            ReputationEvents.fireRanksHeld(playerId, reputation.id(), held);
        }
    }

    @Override
    public void rose(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation, @Nonnull ReputationLadder.Rank rank) {
        if (store == null || ref == null) {
            return;
        }
        try {
            if (!FeedbackEngine.answers(RANK_MOMENT)) {
                return;
            }
            Subject subject = subject(store, ref);
            if (subject != null) {
                FeedbackEngine.fire(RANK_MOMENT, subject, rankArgs(reputation, rank));
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the rank notice failed: " + t.getMessage());
        }
    }

    @Nonnull
    static String rowId(@Nonnull ReputationChange change) {
        return ROW_PREFIX + change.reputation().id();
    }

    @Nonnull
    static HudBarReading reading(@Nonnull ReputationChange change) {
        ReputationLadder.Progress progress = change.progress();
        return new HudBarReading(progress.current(), progress.span());
    }

    @Nonnull
    static HudRowDisplay display(@Nonnull ReputationChange change) {
        ReputationDef def = change.reputation();
        IconSpec icon = def.icon() == null ? null : IconSpec.ofItem(def.icon());
        return HudRowDisplay.of(ReputationText.line("hud.caption", ReputationText.name(def),
                ReputationText.rankName(def, change.rankAfter())), icon, null, def.order());
    }

    @Nonnull
    static Map<String, Object> rankArgs(@Nonnull ReputationDef reputation, @Nonnull ReputationLadder.Rank rank) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(FeedbackEngine.SOURCE_ARG, reputation.id());
        args.put("name", ReputationText.name(reputation));
        args.put("rank", ReputationText.rankName(reputation, rank));
        if (reputation.icon() != null) {
            args.put(ICON_ARG, reputation.icon());
        }
        return args;
    }

    /** The Beyond rewards once per crossing, at most {@link #MAX_BEYOND_PAYOUTS} times. */
    @Nonnull
    static List<RewardSpec> beyondPayout(@Nonnull ReputationChange change) {
        List<RewardSpec> once = change.reputation().beyondRewards();
        int times = Math.min(change.beyondCrossings(), MAX_BEYOND_PAYOUTS);
        List<RewardSpec> out = new ArrayList<>(once.size() * Math.max(0, times));
        for (int i = 0; i < times; i++) {
            out.addAll(once);
        }
        return out;
    }

    private static void moveBar(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            HudPanels.movedInWorld(playerRef, rowId(change), change.delta(), reading(change), display(change));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the bar for '" + change.reputation().id() + "' could not move: "
                    + t.getMessage());
        }
    }

    private static void payBeyond(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        try {
            List<RewardSpec> rewards = beyondPayout(change);
            if (rewards.isEmpty()) {
                return;
            }
            if (change.beyondCrossings() > MAX_BEYOND_PAYOUTS) {
                SafeLog.warn("[reputation] one change to '" + change.reputation().id() + "' crossed "
                        + change.beyondCrossings() + " Beyond payouts; only " + MAX_BEYOND_PAYOUTS + " are paid");
            }
            Subject subject = subject(store, ref);
            if (subject == null) {
                SafeLog.warn("[reputation] nobody to pay the Beyond rewards of '" + change.reputation().id() + "' to");
                return;
            }
            RewardGrants.grantAll(rewards, subject, PAYOUT_SOURCE + change.reputation().id(), RewardKinds.shared(),
                    true, ProgressionRuntime.rewardRetryQueue(), SafeLog::warn);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the Beyond payout for '" + change.reputation().id() + "' failed: "
                    + t.getMessage());
        }
    }

    @Nullable
    private static Subject subject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (subject != null) {
            return subject;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : Subject.of(playerRef, store.getComponent(ref, Player.getComponentType()));
    }
}
