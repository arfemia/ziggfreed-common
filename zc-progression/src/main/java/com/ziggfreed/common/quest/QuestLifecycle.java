package com.ziggfreed.common.quest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.Quest.Repeat;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;
import com.ziggfreed.common.subject.Subject;

/**
 * The single authority for what a quest's status EFFECTIVELY is right now, whether a repeatable may
 * be taken again, and when it next can be.
 *
 * <p>The rule it exists to keep in one place: a repeatable quest that has been finished stays
 * {@link QuestStatus#COMPLETED} in storage forever, but that is not what anybody should see. While
 * something still holds it back it reads {@link QuestStatus#ON_COOLDOWN}, and once nothing does it
 * reads {@link QuestStatus#NOT_STARTED} so the player can take it again. Any surface that paints the
 * STORED status instead shows a finished daily as permanently done - which is exactly the bug this
 * class prevents, and it only prevents it for the callers that route through it.
 *
 * <p>Every method has a pure overload taking the raw values, so every boundary can be exercised
 * without a store or a clock.
 */
public final class QuestLifecycle {

    private QuestLifecycle() {
    }

    /**
     * Why a repeatable is not offerable right now, and when it will be.
     *
     * @param available     nothing is holding it back
     * @param reason        the {@link QuestGates} token for what is, or null when nothing is
     * @param offerableAtMs epoch milliseconds it comes back, {@code 0} for now and
     *                      {@link Long#MAX_VALUE} for never
     */
    public record RepeatCheck(boolean available, @Nullable String reason, long offerableAtMs) {

        /** Nothing is holding it back. */
        public static final RepeatCheck AVAILABLE = new RepeatCheck(true, null, 0L);

        /** True when nothing will ever make it offerable again - a spent lifetime cap. */
        public boolean permanentlySpent() {
            return !available && offerableAtMs == Long.MAX_VALUE;
        }

        /** How long until it comes back, from {@code nowMs}. Never negative. */
        public long waitMs(long nowMs) {
            if (available) {
                return 0L;
            }
            if (offerableAtMs == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return Math.max(0L, offerableAtMs - nowMs);
        }
    }

    // ==================== the evaluator ====================

    /** The store-backed form: read this player's stamp and record, then apply the pure rule. */
    @Nonnull
    public static RepeatCheck repeatCheck(@Nonnull Quest quest, @Nonnull Subject subject,
                                          @Nonnull QuestProgressStore store, long nowMs) {
        return repeatCheck(quest.repeat(), store.cooldownStamp(subject, quest.id()),
                store.completions(subject, quest.id()), nowMs);
    }

    /**
     * The pure rule. Three INDEPENDENT constraints, ANDed; the first refusal wins, and the order is
     * chosen so a player is told the most actionable thing:
     * <ol>
     *   <li>the LIFETIME cap, first because "back in three hours" is a worse message than the truth
     *   for a quest somebody can never take again;
     *   <li>the CALENDAR allowance, read against the window the last completion fell in, so a tally
     *   left over from an earlier window costs nothing and nothing has to sweep it;
     *   <li>the ROLLING cooldown. Which instant its stamp holds was already decided by
     *   {@link Repeat.CooldownFrom} when it was written, so this stays a pure read. A clock that has
     *   moved BACKWARDS answers the full remaining window rather than a negative one.
     * </ol>
     *
     * <p>A one-shot ({@code repeat == null}) and an EMPTY group both answer
     * {@link RepeatCheck#AVAILABLE}: the first is never asked (only a repeatable is re-offered at
     * all), and the second is the externally governed quest, which holds nothing back on purpose.
     */
    @Nonnull
    public static RepeatCheck repeatCheck(@Nullable Repeat repeat, long cooldownStampMs,
                                          @Nonnull CompletionRecord completions, long nowMs) {
        if (repeat == null) {
            return RepeatCheck.AVAILABLE;
        }
        if (repeat.maxCompletions() > 0 && completions.totalCount() >= repeat.maxCompletions()) {
            return new RepeatCheck(false, QuestGates.REASON_MAX_COMPLETIONS, Long.MAX_VALUE);
        }
        Repeat.Reset reset = repeat.reset();
        if (reset != null) {
            int spent = RepeatPeriod.samePeriod(reset, completions.lastCompletionMs(), nowMs)
                    ? completions.periodCount() : 0;
            if (spent >= reset.times()) {
                return new RepeatCheck(false, QuestGates.REASON_PERIOD_SPENT,
                        RepeatPeriod.nextBoundaryMs(reset, nowMs));
            }
        }
        if (repeat.cooldownMs() > 0L && cooldownStampMs > 0L
                && nowMs - cooldownStampMs < repeat.cooldownMs()) {
            return new RepeatCheck(false, QuestGates.REASON_ON_COOLDOWN,
                    cooldownStampMs + repeat.cooldownMs());
        }
        return RepeatCheck.AVAILABLE;
    }

    // ==================== status ====================

    /** The status a player-facing surface should render and gate "accept" on. */
    @Nonnull
    public static QuestStatus effectiveStatus(@Nonnull Quest quest, @Nonnull Subject subject,
                                              @Nonnull QuestProgressStore store, long nowMs) {
        return effectiveStatus(quest.repeat(), store.status(subject, quest.id()),
                store.cooldownStamp(subject, quest.id()),
                store.completions(subject, quest.id()), nowMs);
    }

    /**
     * The pure rule. Only a stored {@link QuestStatus#COMPLETED} on a REPEATABLE quest is
     * reinterpreted; every other stored status is returned untouched.
     *
     * <p>A quest whose lifetime cap is spent reads {@link QuestStatus#COMPLETED} - terminal, exactly
     * like a one-shot, because that is what it has become. Anything else still holding it back reads
     * {@link QuestStatus#ON_COOLDOWN}, which is a COMPUTED display state covering a running cooldown
     * and a spent calendar window alike; the specific truth rides on {@link RepeatCheck#reason()} for
     * a caller that wants to say which.
     */
    @Nonnull
    public static QuestStatus effectiveStatus(@Nullable Repeat repeat, @Nonnull QuestStatus stored,
                                              long cooldownStampMs,
                                              @Nonnull CompletionRecord completions, long nowMs) {
        if (stored != QuestStatus.COMPLETED || repeat == null) {
            return stored;
        }
        RepeatCheck check = repeatCheck(repeat, cooldownStampMs, completions, nowMs);
        if (check.available()) {
            return QuestStatus.NOT_STARTED;
        }
        return check.permanentlySpent() ? QuestStatus.COMPLETED : QuestStatus.ON_COOLDOWN;
    }

    // ==================== waiting ====================

    /**
     * When this quest comes back for this player: {@code 0} now, {@code >0} the wait in
     * milliseconds, {@link Long#MAX_VALUE} never. The whole truth, for a surface that wants it -
     * unlike {@link #cooldownRemainingMs}, which deliberately answers only for the rolling clock.
     */
    public static long offerableInMs(@Nonnull Quest quest, @Nonnull Subject subject,
                                     @Nonnull QuestProgressStore store, long nowMs) {
        return repeatCheck(quest, subject, store, nowMs).waitMs(nowMs);
    }

    /** The pure form of {@link #offerableInMs(Quest, Subject, QuestProgressStore, long)}. */
    public static long offerableInMs(@Nullable Repeat repeat, long cooldownStampMs,
                                     @Nonnull CompletionRecord completions, long nowMs) {
        return repeatCheck(repeat, cooldownStampMs, completions, nowMs).waitMs(nowMs);
    }

    /** Milliseconds left on this quest's ROLLING cooldown for this player, or {@code 0}. */
    public static long cooldownRemainingMs(@Nonnull Quest quest, @Nonnull Subject subject,
                                           @Nonnull QuestProgressStore store, long nowMs) {
        return cooldownRemainingMs(quest.repeat(), store.cooldownStamp(subject, quest.id()), nowMs);
    }

    /**
     * The pure rule: how much of {@code repeat}'s ROLLING cooldown is left, given when it was stamped
     * and what time it is. A one-shot, an unstamped quest, and an elapsed cooldown all answer 0, as
     * does a quest held back only by a calendar window or a lifetime cap - ask
     * {@link #offerableInMs} for that. A clock that has moved backwards (a corrected system time)
     * answers the full remaining window rather than a negative one.
     */
    public static long cooldownRemainingMs(@Nullable Repeat repeat, long cooldownStampMs, long nowMs) {
        if (repeat == null || cooldownStampMs <= 0L) {
            return 0L;
        }
        long elapsed = nowMs - cooldownStampMs;
        return Math.max(0L, repeat.cooldownMs() - elapsed);
    }

    /** True when a finished repeatable is still waiting out its ROLLING cooldown. */
    public static boolean onCooldown(@Nullable Repeat repeat, long cooldownStampMs, long nowMs) {
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
