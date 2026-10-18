package com.ziggfreed.common.world;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The seam a {@code Where}'s {@code Placement} axis reads: which worlds an NPC placement may stand in.
 *
 * <p>NPC placements live in a module above this one, so the world layer declares the question and the
 * placement store answers it: zc-dialogue's {@code NpcBootstrap} installs the answer at library setup,
 * so a server running this library alone has it, with no consumer wiring. Until something installs one
 * (a unit JVM, a setup that failed) every {@code Placement} axis matches nothing, and this says so once.
 *
 * <p>An answer is the placement's own effective {@code Where} as its store resolves it, the server owner's
 * {@code npc-placements.json} included: the worlds it may stand in, never whether its NPC is standing
 * anywhere right now. {@link WorldSelector} drops any {@code Placement} axis an answer carries before
 * scoring it, so a chain can never form whatever an installed answer returns.
 */
public final class PlacementWheres {

    /** What the placement store answers. Every call may come from any thread. */
    public interface Lookup {

        /**
         * The {@code Where} the placement {@code placementId} stands under (the id arrives trimmed and
         * lower-cased), or null when no placement on this server has that id.
         */
        @Nullable
        WorldSelector whereOf(@Nonnull String placementId);

        /**
         * True once the placement pool has folded, so an id {@link #whereOf} has no answer for is unknown
         * rather than not loaded yet. An audit asks this before it names an id; true unless the store can say.
         */
        default boolean folded() {
            return true;
        }

        /**
         * True when the mod gate keeps {@code placementId} (trimmed, lower-cased) off this server on purpose,
         * so no audit line names it. False unless the store can say.
         */
        default boolean refused(@Nonnull String placementId) {
            return false;
        }
    }

    /** Whether the missing answer has been reported, so it is said once per process. */
    private static final AtomicBoolean WARNED_UNFILLED = new AtomicBoolean();

    @Nullable
    private static volatile Lookup lookup;

    private PlacementWheres() {
    }

    /**
     * Install the placement store's answer. Called once at library setup by the module that owns NPC
     * placements; last write wins.
     */
    public static void install(@Nonnull Lookup answer) {
        lookup = answer;
    }

    /** True once an answer is installed. */
    public static boolean isInstalled() {
        return lookup != null;
    }

    /**
     * The {@code Where} the placement {@code placementId} stands under, the id matched without regard to
     * case, or null: a blank id, no placement under it, an answer that throws, or no answer installed
     * (reported once).
     */
    @Nullable
    public static WorldSelector whereOf(@Nullable String placementId) {
        String id = normalize(placementId);
        if (id == null) {
            return null;
        }
        Lookup answer = installedOrWarn();
        if (answer == null) {
            return null;
        }
        try {
            return answer.whereOf(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether some placement on this server has the id {@code placementId}: true or false once the pool has
     * folded; null when this cannot tell (a blank id, no answer installed, the pool not folded yet, an answer
     * that throws) and for an id the mod gate refused, which no audit line names.
     */
    @Nullable
    public static Boolean known(@Nullable String placementId) {
        String id = normalize(placementId);
        if (id == null) {
            return null;
        }
        Lookup answer = installedOrWarn();
        if (answer == null) {
            return null;
        }
        try {
            if (!answer.folded() || answer.refused(id)) {
                return null;
            }
            return answer.whereOf(id) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Forget the installed answer and the one report, so a test can install its own stub. */
    static void clearForTests() {
        lookup = null;
        WARNED_UNFILLED.set(false);
    }

    @Nullable
    private static Lookup installedOrWarn() {
        Lookup answer = lookup;
        if (answer == null) {
            warnUnfilledOnce();
        }
        return answer;
    }

    /**
     * Say once that a {@code Placement} axis is being read with nothing to answer it. The file naming the
     * placement is correct and so is the placement; only this layer can see that the content bound to it
     * will appear nowhere.
     */
    private static void warnUnfilledOnce() {
        if (!WARNED_UNFILLED.compareAndSet(false, true)) {
            return;
        }
        SafeLog.warn("[world] a Where names NPC placements, but no placement lookup is installed, so its"
                + " Placement axis matches no world and whatever it gates appears nowhere. The fill is"
                + " PlacementWheres.install at library setup (zc-dialogue's NpcBootstrap); a unit JVM with no"
                + " server anywhere near it is the one place this is expected.");
    }

    @Nullable
    private static String normalize(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return null;
        }
        return placementId.trim().toLowerCase(Locale.ROOT);
    }
}
