package com.ziggfreed.common.proc;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The moments a proc ({@link ProcAsset}) answers, a closed vocabulary: each is produced in one place, for players
 * only ({@link ProcSystems}, {@link ProcMoments}, and {@link Procs#crit} for the one a consumer decides).
 */
public enum ProcTrigger {

    HIT("Hit", "A direct hit the holder lands on a creature (zc's DEAL_DAMAGE moment)", true, false),
    CRIT("Crit", "A hit the holder lands that a mod decided was critical (never on a server with no crit roll)",
            true, false),
    KILL("Kill", "A creature the holder is credited with killing", false, false),
    DODGE("Dodge", "The holder's own dodge (a Dodge chain from their input)", false, false),
    GUARD_START("GuardStart", "The holder raises a guard (a Wielding chain from their input)", false, false),
    BLOCKED("Blocked", "A hit on the holder that their guard blocked", true, true),
    HIT_TAKEN("HitTaken", "A hit a foe lands on the holder", true, true),
    SPRINT_HIT("SprintHit", "A direct hit the holder lands while sprinting", true, false),
    LANDING("Landing", "The holder lands after a fall (the moment's amount is the fall in blocks)", false, false),
    CROUCH_STILL("CrouchStill", "The holder crouches and stands still (asked a few times a second while they do; "
            + "the moment's amount is the seconds held)", false, false),
    CAST("Cast", "The holder starts an ability (an Ability1 to Ability4 chain from their input)", false, false),
    BLOCK_BREAK("BlockBreak", "A block the holder broke (zc's BREAK_BLOCK moment)", false, false),
    HARVEST("Harvest", "Something the holder harvested (zc's PICKUP_ITEM moment)", false, false);

    @Nonnull private final String id;
    @Nonnull private final String meaning;
    private final boolean struck;
    private final boolean taken;

    ProcTrigger(@Nonnull String id, @Nonnull String meaning, boolean struck, boolean taken) {
        this.id = id;
        this.meaning = meaning;
        this.struck = struck;
        this.taken = taken;
    }

    /** The authored spelling. */
    @Nonnull
    public String id() {
        return id;
    }

    /** Does the moment carry a hit (so a proc's {@code Hit} group and {@code Like} are read)? */
    public boolean struck() {
        return struck;
    }

    /** Is the hit one the holder took (its attacker is the moment's target)? */
    public boolean taken() {
        return taken;
    }

    /** The trigger {@code raw} names (any case), or null when blank or unknown. */
    @Nullable
    public static ProcTrigger parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String folded = raw.trim().toLowerCase(Locale.ROOT);
        for (ProcTrigger trigger : values()) {
            if (trigger.id.toLowerCase(Locale.ROOT).equals(folded)) {
                return trigger;
            }
        }
        return null;
    }

    /** Every spelling with its meaning, alternating, for the Asset Editor's dropdown. */
    @Nonnull
    static String[] documented() {
        ProcTrigger[] all = values();
        String[] out = new String[all.length * 2];
        for (int i = 0; i < all.length; i++) {
            out[i * 2] = all[i].id;
            out[i * 2 + 1] = all[i].meaning;
        }
        return out;
    }

    /** Every spelling, for a string-array leaf's closed set. */
    @Nonnull
    static String[] ids() {
        ProcTrigger[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].id;
        }
        return out;
    }
}
