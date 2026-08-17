package com.ziggfreed.common.achievement;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.runtime.ProgressionFeedbackHook;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one server-first claim table, how a consumer replaces it, and who hears about a lost race.
 *
 * <p>Ships an IN-MEMORY default rather than nothing, so a server with no consumer still arbitrates a
 * server-first correctly for as long as it is up. A seam that degrades to "nobody wins" would hide
 * the whole feature; one that degrades to "everybody wins" would break the only rule it exists to
 * keep. Losing the table on restart is the honest cost of having no storage, and it is the one a
 * consumer removes by {@linkplain #install installing} its own.
 *
 * <p><b>The loss is announced, not handled.</b> The rule ("exactly one, and a loser keeps their
 * criteria met") is settled here; what a player is TOLD about it is presentation, which nothing in
 * this module can write. So a loss is announced as the {@code achievement.server_first_lost}
 * feedback MOMENT, which a server author answers with an authored file and no Java at all, and
 * which any mod wanting to react itself reaches through the progression registrar's feedback
 * contribution - one announcement, additively read by everybody, rather than a second fan-out of
 * its own beside it.
 */
public final class FirstClaims {

    /** The table this library keeps when nobody supplies one: correct, and only as durable as the boot. */
    private static final class InMemory implements FirstClaimStore {

        private final Map<String, UUID> held = new ConcurrentHashMap<>();

        @Override
        public boolean tryClaim(@Nonnull String achievementId, @Nonnull UUID subjectId,
                @Nullable String subjectName) {
            UUID winner = held.putIfAbsent(achievementId.trim().toLowerCase(Locale.ROOT), subjectId);
            return winner == null || winner.equals(subjectId);
        }

        void clear() {
            held.clear();
        }
    }

    private static final InMemory DEFAULT = new InMemory();

    private static volatile FirstClaimStore installed = DEFAULT;

    /** One report per process that the boot-lifetime table is the one arbitrating. */
    private static final AtomicBoolean REPORTED_DEFAULT = new AtomicBoolean();

    private FirstClaims() {
    }

    /**
     * The active table; never null, because the library default stands until one is installed.
     *
     * <p>The FIRST time a server-first is actually arbitrated on the boot-lifetime default, that is
     * said once, out loud. It is reported here rather than at boot because who owns the table is not
     * settled at boot: a consumer installs its own during its own setup, so a line written while the
     * wiring root was still coming up would report a table that is about to be replaced. Asked at
     * the moment a claim is genuinely being decided, the answer is the one that will hold.
     */
    @Nonnull
    public static FirstClaimStore store() {
        if (isDefault() && REPORTED_DEFAULT.compareAndSet(false, true)) {
            SafeLog.info("[achievements] server-first winners are being recorded in the library's"
                    + " boot-lifetime table, so they are forgotten when this server restarts and"
                    + " the next player to finish one wins it again. Install a durable table with"
                    + " FirstClaims.install(FirstClaimStore) to keep them.");
        }
        return installed;
    }

    /** Put a consumer's own durable table in charge. Call once from setup; null restores the default. */
    public static void install(@Nullable FirstClaimStore store) {
        installed = store == null ? DEFAULT : store;
    }

    /**
     * Is a consumer's table in charge, or is this still the boot-lifetime default? Read by
     * {@link #store()} to report the durability of what is arbitrating, and by an admin surface.
     */
    public static boolean isDefault() {
        return installed == DEFAULT;
    }

    /**
     * Announce that {@code subject} met the criteria and was beaten to {@code achievement}.
     *
     * <p>Announced from here rather than from the gate that decides it, because this is the one
     * place that knows a race was lost however the decision was reached. It deliberately carries no
     * picture: a loss is a quiet note, and an icon would make it read like the unlock it is not.
     */
    public static void fireLost(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), SafeLog::warn,
                "achievement.server_first_lost", subject, "achievement", achievement.id(),
                "title", achievement.text().titleOr(achievement.id()));
    }

    /** Drop every claim. For a test resetting between cases. */
    public static void resetForTests() {
        DEFAULT.clear();
        installed = DEFAULT;
        REPORTED_DEFAULT.set(false);
    }
}
