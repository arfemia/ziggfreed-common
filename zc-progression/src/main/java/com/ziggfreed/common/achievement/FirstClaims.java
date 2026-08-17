package com.ziggfreed.common.achievement;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * this module can write. So a loss is fanned out to every registered listener, each guarded, in
 * registration order - additive, so a second mod listening never displaces the first.
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

    private static final List<BiConsumer<Subject, Achievement>> LOST = new CopyOnWriteArrayList<>();

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
     * Hear about a subject who met the criteria and lost the race. Additive: every listener is told,
     * each inside its own guard, so one mod's broken notice costs nobody else's.
     *
     * <p><b>Registering once is the CALLER's business.</b> Nothing here can dedupe for them: the
     * usual listener is a method reference, and a fresh one of those is a different object every
     * time it is written, so a guard comparing listeners would look like protection while catching
     * nothing. Register from setup, guarded the way the rest of a mod's registration is.
     */
    public static void onLost(@Nullable BiConsumer<Subject, Achievement> listener) {
        if (listener != null) {
            LOST.add(listener);
        }
    }

    /** Tell every listener that {@code subject} was beaten to {@code achievement}. */
    public static void fireLost(@Nonnull Subject subject, @Nonnull Achievement achievement) {
        for (BiConsumer<Subject, Achievement> listener : LOST) {
            try {
                listener.accept(subject, achievement);
            } catch (Throwable t) {
                SafeLog.warn("[achievements] a server-first loss listener failed: " + t.getMessage());
            }
        }
    }

    /** Drop every claim and every listener. For a test resetting between cases. */
    public static void resetForTests() {
        DEFAULT.clear();
        installed = DEFAULT;
        LOST.clear();
        REPORTED_DEFAULT.set(false);
    }
}
