package com.ziggfreed.common.encounter.seam;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The four questions this module cannot answer alone, and what it does until somebody answers them.
 *
 * <p>Each is a SEAM filled at setup: who a non-player attacker acts for ({@link EncounterAttribution}),
 * how much power a party has ({@link EncounterPowerSource}), who a player is to the engines that pay
 * and notify them ({@link EncounterSubjectSource}), and which zc run a fight belongs to
 * ({@link EncounterRunTag}, filled by the instance module from its dungeon runs). Each has a posture for a
 * server nothing filled it on, and each REPORTS ON ITSELF, once, the first time it has to fall back: the
 * fall-back is invisible from every other side (a turret's hits simply credit nobody, a boss simply does
 * not harden, a notice simply reads no preference, a fight in a dungeon is filed under no run), so this
 * layer says so rather than leaving the omission to be discovered as a bug report. An offline player
 * needs no seam: every payout is a Claim, which waits for them.
 *
 * <p>The report fires only when the seam is actually CONSULTED unfilled: a server that never scales
 * on power never hears about the power seam.
 */
public final class EncounterSeams {

    private static final AtomicReference<EncounterAttribution> ATTRIBUTION = new AtomicReference<>();
    private static final AtomicReference<EncounterPowerSource> POWER = new AtomicReference<>();
    private static final AtomicReference<EncounterSubjectSource> SUBJECTS = new AtomicReference<>();
    private static final AtomicReference<EncounterRunTag> RUN_TAG = new AtomicReference<>();

    private static final AtomicBoolean WARNED_ATTRIBUTION = new AtomicBoolean();
    private static final AtomicBoolean WARNED_POWER = new AtomicBoolean();
    private static final AtomicBoolean WARNED_SUBJECTS = new AtomicBoolean();
    private static final AtomicBoolean WARNED_RUN_TAG = new AtomicBoolean();

    private EncounterSeams() {
    }

    // ==================== filling ====================

    public static void fillAttribution(@Nullable EncounterAttribution attribution) {
        ATTRIBUTION.set(attribution);
    }

    public static void fillPowerSource(@Nullable EncounterPowerSource source) {
        POWER.set(source);
    }

    public static void fillSubjectSource(@Nullable EncounterSubjectSource source) {
        SUBJECTS.set(source);
    }

    public static void fillRunTag(@Nullable EncounterRunTag runTag) {
        RUN_TAG.set(runTag);
    }

    /** Drop every fill and every once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        ATTRIBUTION.set(null);
        POWER.set(null);
        SUBJECTS.set(null);
        RUN_TAG.set(null);
        WARNED_ATTRIBUTION.set(false);
        WARNED_POWER.set(false);
        WARNED_SUBJECTS.set(false);
        WARNED_RUN_TAG.set(false);
    }

    public static boolean isAttributionFilled() {
        return ATTRIBUTION.get() != null;
    }

    public static boolean isPowerSourceFilled() {
        return POWER.get() != null;
    }

    public static boolean isSubjectSourceFilled() {
        return SUBJECTS.get() != null;
    }

    public static boolean isRunTagFilled() {
        return RUN_TAG.get() != null;
    }

    // ==================== asking ====================

    /** The player {@code attackerRef} acts for, or null; consulting the unfilled seam reports once. */
    @Nullable
    public static Ref<EntityStore> actsFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attackerRef) {
        EncounterAttribution filled = attributionOrWarn();
        if (filled == null) {
            return null;
        }
        try {
            return filled.actsFor(store, attackerRef);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the attribution fill failed, so this hit credits nobody: "
                    + t.getMessage());
            return null;
        }
    }

    /** The party's aggregated power, or 0 when nothing can say; consulting the unfilled seam reports once. */
    public static double aggregatedPower(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> subjectRef,
            @Nonnull List<Ref<EntityStore>> members) {
        EncounterPowerSource filled = powerSourceOrWarn();
        if (filled == null) {
            return 0.0;
        }
        try {
            Double power = filled.aggregatedPower(store, subjectRef, members);
            return power != null && Double.isFinite(power) && power > 0.0 ? power : 0.0;
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the power fill failed, so this fight reads zero power: "
                    + t.getMessage());
            return 0.0;
        }
    }

    /**
     * The subject for the player at {@code playerRef}: the consumer's own when the seam is filled,
     * else the library's reference-backed one (a payout still lands and a notice still shows, but
     * no consumer preference is consulted). Null when the ref is not a live player.
     */
    @Nullable
    public static Subject subjectFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        EncounterSubjectSource filled = subjectSourceOrWarn();
        if (filled != null) {
            try {
                Subject subject = filled.subjectFor(store, playerRef);
                if (subject != null) {
                    return subject;
                }
            } catch (Throwable t) {
                SafeLog.warn(Encounters.LOG_PREFIX + " the subject fill failed, so the library's own identity "
                        + "answers: " + t.getMessage());
            }
        }
        return fallbackSubject(store, playerRef);
    }

    /**
     * The zc run the fight in {@code worldUuid} belongs to, read off {@code players}, or null when it is in none
     * (or the fill fails, which costs only the tag); consulting the unfilled seam reports once.
     */
    @Nullable
    public static RunTag runTagFor(@Nullable UUID worldUuid, @Nonnull List<UUID> players) {
        EncounterRunTag filled = runTagOrWarn();
        if (filled == null || players.isEmpty()) {
            return null;
        }
        try {
            return filled.runOf(worldUuid, players);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the run-tag fill failed, so this fight is filed under no run: "
                    + t.getMessage());
            return null;
        }
    }

    // ==================== the seam's own reports ====================

    /** The attribution fill, or null having REPORTED once that there is none. */
    @Nullable
    static EncounterAttribution attributionOrWarn() {
        EncounterAttribution filled = ATTRIBUTION.get();
        if (filled == null) {
            warnOnce(WARNED_ATTRIBUTION, "nothing attributes a non-player attacker to its owner, so a hit by a "
                    + "turret, a summon or a pet credits nobody in a fight");
        }
        return filled;
    }

    /** The power fill, or null having REPORTED once that there is none. */
    @Nullable
    static EncounterPowerSource powerSourceOrWarn() {
        EncounterPowerSource filled = POWER.get();
        if (filled == null) {
            warnOnce(WARNED_POWER, "nothing answers a party's power, so a binding scaling on "
                    + "HealthPerPowerPoint reads zero power");
        }
        return filled;
    }

    /** The subject fill, or null having REPORTED once that there is none. */
    @Nullable
    static EncounterSubjectSource subjectSourceOrWarn() {
        EncounterSubjectSource filled = SUBJECTS.get();
        if (filled == null) {
            warnOnce(WARNED_SUBJECTS, "no consumer identity is installed, so encounter payouts and notices use "
                    + "the library's own player identity and consult no per-player preference");
        }
        return filled;
    }

    /** The run-tag fill, or null having REPORTED once that there is none. */
    @Nullable
    static EncounterRunTag runTagOrWarn() {
        EncounterRunTag filled = RUN_TAG.get();
        if (filled == null) {
            warnOnce(WARNED_RUN_TAG, "nothing says which run a fight belongs to, so a fight inside a dungeon is "
                    + "filed under no run");
        }
        return filled;
    }

    /** The once-only latch behind {@code seam}, for the test that pins each report fires once. */
    @Nonnull
    static AtomicBoolean latchForTests(@Nonnull String seam) {
        return switch (seam) {
            case "attribution" -> WARNED_ATTRIBUTION;
            case "power" -> WARNED_POWER;
            case "subjects" -> WARNED_SUBJECTS;
            case "runtag" -> WARNED_RUN_TAG;
            default -> throw new IllegalArgumentException("no seam called " + seam);
        };
    }

    /**
     * Say {@code message} once, out loud.
     *
     * @return true when this call is the one that reported it, false when it was already said
     */
    static boolean warnOnce(@Nonnull AtomicBoolean latch, @Nonnull String message) {
        if (!latch.compareAndSet(false, true)) {
            return false;
        }
        SafeLog.warn(Encounters.LOG_PREFIX + " " + message);
        return true;
    }

    @Nullable
    private static Subject fallbackSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        if (!playerRef.isValid()) {
            return null;
        }
        PlayerRef player = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (player == null || player.getUuid() == null) {
            return null;
        }
        String name = player.getUsername();
        return PlayerRefSubjectHandle.subjectFor(player, name == null ? "" : name);
    }
}
