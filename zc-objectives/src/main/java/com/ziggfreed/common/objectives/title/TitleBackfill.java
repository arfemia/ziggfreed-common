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
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The login pass that hands a player every title their achievements and quests already owe them. An
 * achievement earned, or a quest collected, before its {@code Title} reward existed (a save from before the
 * title shipped) never paid it, and nothing else would: the reward pays only at the moment of earning or
 * collecting. So at every player-ready each achievement and quest with a {@code Title} reward is read where
 * that reward would have paid: an achievement's row paid on earning once it is earned, its row paid on
 * collecting once it is collected, and a quest's rows (every one pays when it settles or is collected, a
 * parked quest holding them all) once it has been collected at least once.
 *
 * <p>Every write goes through {@link TitleUnlocks}, so a title the player already holds is a no-op that
 * announces nothing, and only a title they lacked draws the unlock notice. A title an admin revoked comes
 * back at the next login while its achievement or collected quest stands; revoking the achievement, or
 * wiping the quest, is what takes it for good.
 *
 * <p>Not behind {@code ProgressionRuntime.usesDefaultStores()}: it reads the runtime's own achievement and
 * quest subjects, so it serves whoever owns the stores, as the tracked-quest HUD does. It runs on the
 * player's world thread, at the ready event's LATE beat (registered by {@link TitleBootstrap}), after the
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

    /** World thread: unlock every title the player's achievements and collected quests owe them. */
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
            Set<String> owed = new LinkedHashSet<>(owedByAchievements(store, ref));
            owed.addAll(owedByQuests(store, ref));
            for (String titleId : owed) {
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
     * The titles the player's achievements owe them; none while the achievement switch is off for them. A
     * read that throws costs only this half, never the quests' titles.
     */
    @Nonnull
    private static List<String> owedByAchievements(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
            if (subject == null || !ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT, subject)) {
                return List.of();
            }
            AchievementEngine engine = ProgressionRuntime.achievements();
            return owed(engine.achievements(), id -> engine.isUnlocked(subject, id),
                    id -> engine.isClaimed(subject, id));
        } catch (Throwable t) {
            SafeLog.warn("[title] the login title pass could not read the achievements' titles", t);
            return List.of();
        }
    }

    /**
     * The titles the player's collected quests owe them; none while the quest switch is off for them. A read
     * that throws costs only this half, never the achievements' titles.
     */
    @Nonnull
    private static List<String> owedByQuests(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
            if (subject == null || !ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, subject)) {
                return List.of();
            }
            QuestEngine engine = ProgressionRuntime.quests();
            QuestProgressStore progress = engine.store();
            return owedByQuests(engine.quests(), id -> collected(progress, subject, id));
        } catch (Throwable t) {
            SafeLog.warn("[title] the login title pass could not read the quests' titles", t);
            return List.of();
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

    /**
     * The titles quest {@code catalogue} owes a player, lower-cased, each once, in catalogue order: every
     * {@code Title} row of a quest once {@code collected} accepts its id. A quest with no {@code Title} reward
     * is never asked about.
     */
    @Nonnull
    static List<String> owedByQuests(@Nonnull Collection<Quest> catalogue, @Nonnull Predicate<String> collected) {
        Set<String> owed = new LinkedHashSet<>();
        for (Quest quest : catalogue) {
            if (quest == null) {
                continue;
            }
            List<String> titles = TitleRewardKind.titlesIn(quest.rewards());
            if (!titles.isEmpty() && collected.test(quest.id())) {
                owed.addAll(titles);
            }
        }
        return List.copyOf(owed);
    }

    /**
     * Has the player collected {@code questId}'s rewards at least once: it is stored finished and paid
     * ({@code COMPLETED}, never a parked {@code COMPLETED_UNCLAIMED}), or its completion record counts a
     * collection (a repeatable taken up again since). The stored status, not the effective one, which turns a
     * finished repeatable back into an offer.
     */
    private static boolean collected(@Nonnull QuestProgressStore progress, @Nonnull Subject subject,
            @Nonnull String questId) {
        return progress.status(subject, questId) == QuestStatus.COMPLETED
                || progress.completions(subject, questId).claimedCount() > 0;
    }
}
