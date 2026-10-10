package com.ziggfreed.common.objectives.title;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The login pass that hands a player every title their achievements already owe them. An achievement
 * earned before its {@code Title} reward existed (a save from before the title shipped) never paid it, and
 * nothing else would: the reward pays only at the moment of earning or collecting. So at every player-ready
 * each achievement with a {@code Title} reward is read where that reward would have paid: a row paid on
 * earning once the achievement is earned, a row paid on collecting once it is collected.
 *
 * <p>Every write goes through {@link TitleUnlocks}, so a title the player already holds is a no-op that
 * announces nothing, and only a title they lacked draws the unlock notice. A title an admin revoked comes
 * back at the next login while its achievement stands; revoking the achievement is what takes it for good.
 *
 * <p>Not behind {@code ProgressionRuntime.usesDefaultStores()}: it reads the runtime's own achievement
 * subject, so it serves whoever owns the stores, as the tracked-quest HUD does. It runs on the player's
 * world thread, at the ready event's LATE beat (registered by {@link TitleBootstrap}), after the
 * runtime's own maintenance pass has queued.
 */
public final class TitleBackfill {

    private TitleBackfill() {
    }

    /** The ready handler {@link TitleBootstrap} registers: hop to the player's world thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> backfillOnWorldThread(player));
        } catch (Throwable t) {
            SafeLog.warn("[title] the login title pass could not be queued", t);
        }
    }

    /** World thread: unlock every title the player's achievements owe them. */
    private static void backfillOnWorldThread(@Nonnull Player player) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null || TitleUnlocks.peek(store, ref) == null) {
                return;
            }
            Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
            if (subject == null || !ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT, subject)) {
                return;
            }
            AchievementEngine engine = ProgressionRuntime.achievements();
            for (String titleId : owed(engine.achievements(),
                    id -> engine.isUnlocked(subject, id), id -> engine.isClaimed(subject, id))) {
                // Each title on its own: one that throws costs itself, never the titles after it.
                try {
                    TitleUnlocks.unlock(store, ref, playerRef, titleId);
                } catch (Throwable t) {
                    SafeLog.warn("[title] the login title pass could not unlock '" + titleId + "'", t);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[title] the login title pass failed", t);
        }
    }

    /**
     * The titles {@code catalogue} owes a player, lower-cased, each once, in catalogue order: an
     * achievement's earn-time {@code Title} rows once {@code unlocked} accepts its id, its collect-time rows
     * once {@code claimed} does too. An achievement with no {@code Title} reward is never asked about.
     */
    @Nonnull
    static List<String> owed(@Nonnull Collection<Achievement> catalogue, @Nonnull Predicate<String> unlocked,
            @Nonnull Predicate<String> claimed) {
        Set<String> owed = new LinkedHashSet<>();
        for (Achievement achievement : catalogue) {
            if (achievement == null) {
                continue;
            }
            List<String> onEarn = TitleRewardKind.titlesIn(achievement.autoRewards());
            List<String> onCollect = TitleRewardKind.titlesIn(achievement.claimRewards());
            if ((onEarn.isEmpty() && onCollect.isEmpty()) || !unlocked.test(achievement.id())) {
                continue;
            }
            owed.addAll(onEarn);
            if (!onCollect.isEmpty() && claimed.test(achievement.id())) {
                owed.addAll(onCollect);
            }
        }
        return List.copyOf(owed);
    }
}
