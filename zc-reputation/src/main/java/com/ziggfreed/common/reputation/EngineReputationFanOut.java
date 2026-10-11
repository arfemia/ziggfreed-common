package com.ziggfreed.common.reputation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.reputation.event.ReputationEvents;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;
import com.ziggfreed.common.ui.hud.panel.HudBarReading;
import com.ziggfreed.common.ui.hud.panel.HudPanels;
import com.ziggfreed.common.ui.hud.panel.HudRowDisplay;
import com.ziggfreed.common.util.SafeLog;

/**
 * The production {@link ReputationFanOut}: an earned change fires {@code ZigReputationChangedEvent}, shows on
 * ONE bar panel and, for a reputation with a Beyond, parks each newly crossed payout on the player's record
 * ({@link ReputationBeyond}) and raises the {@code Reputation_Beyond} notice, or pays it on the spot when the
 * reputation is authored {@code Collect: false}; a credit fires {@code ZigReputationRanksHeldEvent}
 * (zc-objectives turns it into {@code REPUTATION_RANK}); a rise during play raises the {@code Reputation_Rank}
 * notice. Each part is guarded on its own.
 *
 * <p>Which panel a change shows on is where the player was when it landed. Out in the world it moves the
 * reputation's World bar ({@code reputation:<id>}, its reading effective standing's progress through its
 * rank). Made while they had a custom page open (a board, a shop, the book, a conversation), where the
 * client draws no HUD, it goes to the centred panel instead ({@code HudPanels.movedInCenter}), which holds
 * it until no page is open and then shows it: the same row id, so changes made in one visit add up on the
 * reputation's one row ("+N", or "-N" for a net loss), and the World bar's own reading, full only at the
 * reputation's Cap. Both panels already honour a player's hidden and HideAll choices, so the bars need
 * nothing of their own for them.
 */
public final class EngineReputationFanOut implements ReputationFanOut {

    /** The moment a rise is authored under, and the shipped default file's name. */
    public static final String RANK_MOMENT = "Reputation_Rank";

    /** The moment a parked Beyond payout is authored under, and the shipped default file's name. */
    public static final String BEYOND_MOMENT = "Reputation_Beyond";

    /** A reputation's bar is the row {@code reputation:<id>}, on the World panel or the centred one. */
    public static final String ROW_PREFIX = "reputation:";

    /** The fixed argument the feedback engine draws a toast's picture from (an item id). */
    static final String ICON_ARG = "icon";

    /** The parked notice's argument: how many of the reputation's payouts wait in all. */
    static final String WAITING_ARG = "waiting";

    @Override
    public void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        ReputationEvents.fireChanged(change);
        if (store == null || ref == null) {
            return;
        }
        if (centred(change, underPage(store, ref))) {
            toast(store, ref, change);
        } else {
            moveBar(store, ref, change);
        }
        beyond(store, ref, change);
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
        notice(store, ref, RANK_MOMENT, () -> rankArgs(reputation, rank));
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

    /**
     * Whether {@code change} goes to the centred panel: only when the player had a custom page open as it
     * landed ({@code underPage}), a gain or a loss alike. Every other change is the World bar's alone.
     */
    static boolean centred(@Nonnull ReputationChange change, boolean underPage) {
        return underPage && change.delta() != 0;
    }

    /**
     * The centred row's bar: the World bar's own {@link #reading} (through the rank held, and on the open
     * top toward the next Beyond payout), and a full bar only where the reputation stops earning, at its
     * Cap.
     */
    @Nonnull
    static HudBarReading toastReading(@Nonnull ReputationChange change) {
        Integer cap = change.reputation().cap();
        if (cap != null && change.earnedAfter() >= cap) {
            return HudBarReading.FULL;
        }
        return reading(change);
    }

    /**
     * The row's look: the reputation's name alone, its icon and its order. The rank stays off the bar: a
     * column of the World bars leaves the name about 120 px, which "name: rank" overran in every language,
     * and a bar's end captions hold a number or a word of three or four letters; a new rank has its own
     * notice, and the Reputation tab shows the rank held.
     */
    @Nonnull
    static HudRowDisplay display(@Nonnull ReputationChange change) {
        ReputationDef def = change.reputation();
        IconSpec icon = def.icon() == null ? null : IconSpec.ofItem(def.icon());
        return HudRowDisplay.of(ReputationText.name(def), icon, null, def.order());
    }

    @Nonnull
    static Map<String, Object> rankArgs(@Nonnull ReputationDef reputation, @Nonnull ReputationLadder.Rank rank) {
        Map<String, Object> args = noticeArgs(reputation);
        args.put("rank", ReputationText.rankName(reputation, rank));
        return args;
    }

    /** The parked notice's values: the rank notice's own, with how many payouts wait in all. */
    @Nonnull
    static Map<String, Object> beyondArgs(@Nonnull ReputationDef reputation, long waiting) {
        Map<String, Object> args = noticeArgs(reputation);
        args.put(WAITING_ARG, waiting);
        return args;
    }

    /** What both notices carry: the reputation id as the source, its name, and its picture when it has one. */
    @Nonnull
    private static Map<String, Object> noticeArgs(@Nonnull ReputationDef reputation) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(FeedbackEngine.SOURCE_ARG, reputation.id());
        args.put("name", ReputationText.name(reputation));
        if (reputation.icon() != null) {
            args.put(ICON_ARG, reputation.icon());
        }
        return args;
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

    /**
     * Whether the player at {@code ref} has a custom page open right now (world thread); false when that
     * cannot be read, so the change falls back to the World bar rather than going nowhere.
     */
    private static boolean underPage(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            return playerRef != null && KeyedCustomHud.coveredByPage(playerRef);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not tell whether a page was open: " + t.getMessage());
            return false;
        }
    }

    private static void toast(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            HudPanels.movedInCenter(playerRef, rowId(change), change.delta(), toastReading(change), display(change));
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the change to '" + change.reputation().id() + "' made in a page could not "
                    + "be held for the centre: " + t.getMessage());
        }
    }

    /**
     * Past the top: park what the change newly crossed and say so once ({@link #BEYOND_MOMENT}), or, for a
     * reputation authored {@code Collect: false}, pay each crossing now. A reputation with no Beyond leaves the
     * record alone. A crossing with nowhere to park (no record) is paid now rather than lost.
     */
    private static void beyond(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        ReputationDef reputation = change.reputation();
        try {
            if (!reputation.hasBeyond()) {
                return;
            }
            long parked = reputation.beyondCollects() ? ReputationBeyond.park(store, ref, change) : -1L;
            if (parked < 0) {
                if (change.beyondCrossings() > 0) {
                    ReputationBeyond.payNow(store, ref, reputation, change.beyondCrossings());
                }
                return;
            }
            if (parked > 0) {
                long waiting = ReputationBeyond.owed(store, ref, reputation);
                notice(store, ref, BEYOND_MOMENT, () -> beyondArgs(reputation, waiting));
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the Beyond payout for '" + reputation.id() + "' failed: " + t.getMessage());
        }
    }

    /** Raise {@code moment} for the player at {@code ref} when a file answers it; a failure costs only the notice. */
    private static void notice(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String moment, @Nonnull Supplier<Map<String, Object>> args) {
        try {
            if (!FeedbackEngine.answers(moment)) {
                return;
            }
            Subject subject = ReputationBeyond.subject(store, ref);
            if (subject != null) {
                FeedbackEngine.fire(moment, subject, args.get());
            }
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the " + moment + " notice failed: " + t.getMessage());
        }
    }
}
