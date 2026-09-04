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
import com.ziggfreed.common.progress.runtime.SharedCredit;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one server-first claim table, how a consumer replaces it, who hears about a lost race, and
 * the one case in which a claim is shared.
 *
 * <p>Ships an IN-MEMORY default rather than nothing, so a server with no consumer still arbitrates a
 * server-first correctly for as long as it is up. A seam that degrades to "nobody wins" would hide
 * the whole feature; one that degrades to "everybody wins" would break the only rule it exists to
 * keep. Losing the table on restart is the honest cost of having no storage, and it is the one a
 * consumer removes by {@linkplain #install installing} its own.
 *
 * <p><b>A claim won under a shared credit is won by everybody carrying it.</b> A boss defeat is
 * dispatched once per credited participant inside one tick, and the table's own rule would hand the
 * win to whichever of them the loop reached first and tell the rest they lost a race against their
 * own party. So a dispatch carrying a {@link SharedCredit} runs inside {@link #withSharedCredit},
 * and {@link #claim} remembers the credit key a claim was WON under: a later claim for the same
 * achievement carrying the same key is a win without asking the table again. The memory is per
 * boot and per achievement, keyed on a RUN identity rather than a window, so a second party
 * finishing the same fight moments later carries another key and loses exactly as before; a re-test
 * with no key in scope (the login sweep, a scripted grant) never co-claims anything. Whatever table
 * is installed still records ONE claimant, the first: the table is a seam somebody else stores, and
 * who else shared the win is the announcement's business, not the record's.
 *
 * <p><b>The loss is announced, not handled.</b> The rule ("exactly one, and a loser keeps their
 * criteria met") is settled here; what a player is TOLD about it is presentation, which nothing in
 * this module can write. So a loss is announced as the {@code Achievement_Server_First_Lost}
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

    /** The credit key of the dispatch running on this thread, or null outside a shared-credit dispatch. */
    private static final ThreadLocal<String> SHARED_CREDIT = new ThreadLocal<>();

    /** The credit key each achievement's claim was WON under this boot, by lower-cased achievement id. */
    private static final Map<String, String> SHARED_WINS = new ConcurrentHashMap<>();

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

    // ==================== claiming ====================

    /**
     * Run {@code body} with {@code creditKey} in scope as the shared credit every claim inside it
     * carries. A null or blank key runs the body bare, so a producer with no shared credit costs
     * nothing here; nesting restores the outer key when the inner body returns.
     */
    public static void withSharedCredit(@Nullable String creditKey, @Nonnull Runnable body) {
        if (creditKey == null || creditKey.isBlank()) {
            body.run();
            return;
        }
        String previous = SHARED_CREDIT.get();
        SHARED_CREDIT.set(creditKey.trim());
        try {
            body.run();
        } finally {
            if (previous == null) {
                SHARED_CREDIT.remove();
            } else {
                SHARED_CREDIT.set(previous);
            }
        }
    }

    /** The shared credit in scope on this thread, or null. */
    @Nullable
    public static String currentSharedCredit() {
        return SHARED_CREDIT.get();
    }

    /**
     * Claim {@code achievementId} for {@code subject}: a co-win when the claim was already won under
     * the shared credit in scope, else whatever the installed table answers, remembered under that
     * credit when it says yes.
     *
     * @return true when this subject holds the claim after the call
     */
    public static boolean claim(@Nonnull String achievementId, @Nonnull Subject subject) {
        String id = achievementId.trim().toLowerCase(Locale.ROOT);
        String credit = SHARED_CREDIT.get();
        if (credit != null && credit.equals(SHARED_WINS.get(id))) {
            return true;
        }
        boolean won = store().tryClaim(achievementId, subject.id(), subject.name());
        if (won && credit != null) {
            SHARED_WINS.put(id, credit);
        }
        return won;
    }

    /**
     * Announce that {@code subject} met the criteria and was beaten to {@code achievement}.
     *
     * <p>Announced from here rather than from the gate that decides it, because this is the one
     * place that knows a race was lost however the decision was reached. It deliberately carries no
     * picture: a loss is a quiet note, and an icon would make it read like the unlock it is not.
     *
     * <p>A race is lost ONCE, at the moment the loser finishes, and that is the only moment this is
     * fired for. The claim is re-tested long afterwards - a login sweep and every achievement screen
     * ask again, since a cleared claim is one the subject can now win - and none of those is a new
     * loss to announce. The caller tells the two apart by {@link UnlockOccasion}.
     */
    public static void fireLost(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        ProgressionFeedbackHook.fire(ProgressionRuntime.feedback(), SafeLog::warn,
                "Achievement_Server_First_Lost", subject, "achievement", achievement.id(),
                "title", achievement.text().titleOr(achievement.id()));
    }

    /** Drop every claim, every shared win and any credit in scope. For a test resetting between cases. */
    public static void resetForTests() {
        DEFAULT.clear();
        installed = DEFAULT;
        REPORTED_DEFAULT.set(false);
        SHARED_WINS.clear();
        SHARED_CREDIT.remove();
    }
}
