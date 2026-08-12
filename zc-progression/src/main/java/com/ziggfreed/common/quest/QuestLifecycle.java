package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * The single authority for what a quest's status EFFECTIVELY is right now, and how long a repeatable
 * still has to wait.
 *
 * <p>The rule it exists to keep in one place: a repeatable quest that has been finished stays
 * {@link QuestStatus#COMPLETED} in storage forever, but that is not what anybody should see. While
 * its cooldown runs it reads {@link QuestStatus#ON_COOLDOWN}, and once the cooldown elapses it reads
 * {@link QuestStatus#NOT_STARTED} so the player can take it again. Any surface that paints the
 * STORED status instead shows a finished daily as permanently done - which is exactly the bug this
 * class prevents, and it only prevents it for the callers that route through it.
 *
 * <p>Every method has a pure overload taking the raw values, so cooldown boundaries can be exercised
 * without a store or a clock.
 */
public final class QuestLifecycle {

    private QuestLifecycle() {
    }

    /** The status a player-facing surface should render and gate "accept" on. */
    @Nonnull
    public static QuestStatus effectiveStatus(@Nonnull Quest quest, @Nonnull Subject subject,
                                              @Nonnull QuestProgressStore store, long nowMs) {
        return effectiveStatus(quest.repeat(), store.status(subject, quest.id()),
                store.cooldownStamp(subject, quest.id()), nowMs);
    }

    /**
     * The pure rule. Only a stored {@link QuestStatus#COMPLETED} on a REPEATABLE quest is
     * reinterpreted; every other stored status is returned untouched. A stamp of {@code 0} (never
     * stamped) means the cooldown is not running, so the quest reads offerable again.
     */
    @Nonnull
    public static QuestStatus effectiveStatus(@Nonnull Quest.Repeat repeat, @Nonnull QuestStatus stored,
                                              long cooldownStampMs, long nowMs) {
        if (stored != QuestStatus.COMPLETED || !repeat.repeatable()) {
            return stored;
        }
        return cooldownRemainingMs(repeat, cooldownStampMs, nowMs) > 0
                ? QuestStatus.ON_COOLDOWN
                : QuestStatus.NOT_STARTED;
    }

    /** Milliseconds left on this quest's cooldown for this player, or {@code 0} when it is not running. */
    public static long cooldownRemainingMs(@Nonnull Quest quest, @Nonnull Subject subject,
                                           @Nonnull QuestProgressStore store, long nowMs) {
        return cooldownRemainingMs(quest.repeat(), store.cooldownStamp(subject, quest.id()), nowMs);
    }

    /**
     * The pure rule: how much of {@code repeat}'s cooldown is left, given when it was stamped and
     * what time it is. A one-shot quest, an unstamped quest, and an elapsed cooldown all answer 0.
     * A clock that has moved backwards (a corrected system time) answers the full remaining window
     * rather than a negative one.
     */
    public static long cooldownRemainingMs(@Nonnull Quest.Repeat repeat, long cooldownStampMs, long nowMs) {
        if (!repeat.repeatable() || cooldownStampMs <= 0L) {
            return 0L;
        }
        long elapsed = nowMs - cooldownStampMs;
        return Math.max(0L, repeat.cooldownMs() - elapsed);
    }

    /** True when a finished repeatable is still waiting out its cooldown. */
    public static boolean onCooldown(@Nonnull Quest.Repeat repeat, long cooldownStampMs, long nowMs) {
        return cooldownRemainingMs(repeat, cooldownStampMs, nowMs) > 0L;
    }

    /**
     * A compact hours-and-minutes rendering of a remaining cooldown ({@code "2h 5m"}, {@code "5m"},
     * {@code "0m"}), for a consumer that wants one without writing its own. Deliberately
     * language-free: it is digits and two letters, so it needs no translation to be readable, and a
     * consumer wanting localized units formats the raw milliseconds itself.
     */
    @Nonnull
    public static String formatCooldown(long remainingMs) {
        if (remainingMs <= 0L) {
            return "0m";
        }
        long totalMinutes = remainingMs / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
    }

    /** True when {@code status} means the player is currently carrying the quest. */
    public static boolean isInProgress(@Nullable QuestStatus status) {
        return status == QuestStatus.ACTIVE;
    }

    /** True when the objectives are done, whether or not the reward has been taken. */
    public static boolean isFinished(@Nullable QuestStatus status) {
        return status == QuestStatus.COMPLETED || status == QuestStatus.COMPLETED_UNCLAIMED;
    }
}
