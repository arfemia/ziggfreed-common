package com.ziggfreed.common.proc;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The moments a proc ({@link ProcAsset}) answers, a closed vocabulary: each is produced in one place, for players
 * only ({@link ProcSystems}, {@link ProcMoments}, {@link GuardBreaks}, and {@link Procs#crit} and
 * {@link Procs#finisher} for the two a consumer decides).
 */
public enum ProcTrigger {

    HIT("Hit", "A direct hit the holder lands on a creature (zc's DEAL_DAMAGE moment)", true, false),
    CRIT("Crit", "A hit the holder lands that a mod decided was critical (never on a server with no crit roll)",
            true, false),
    KILL("Kill", "A creature the holder is credited with killing", false, false),
    DODGE("Dodge", "The holder's own dodge once it went through (a dodge effect landed after their dodge key: a "
            + "refused dodge, or a forward or back press, never counts)", false, false),
    GUARD_START("GuardStart", "The holder's guard goes up (they start wielding a block)", false, false),
    BLOCKED("Blocked", "A hit on the holder that their guard blocked", true, true),
    HIT_TAKEN("HitTaken", "A hit a foe lands on the holder", true, true),
    SPRINT_HIT("SprintHit", "A direct hit the holder lands while sprinting", true, false),
    LANDING("Landing", "The holder lands after a fall (the moment's amount is the fall in blocks)", false, false),
    CROUCH_STILL("CrouchStill", "The holder crouches and stands still (asked a few times a second while they do; "
            + "the moment's amount is the seconds held)", false, false),
    CAST("Cast", "The holder casts an ability: a mod's own cast as it goes off, or an Ability1 to Ability4 chain "
            + "from their input once it got past its opening (it finished, or still runs half a second on)",
            false, false),
    BLOCK_BREAK("BlockBreak", "A block the holder broke (zc's BREAK_BLOCK moment)", false, false),
    HARVEST("Harvest", "Something the holder harvested (zc's PICKUP_ITEM moment)", false, false),
    DODGED("Dodged", "A foe's hit that came to nothing because the holder's dodge made them invulnerable",
            true, true),
    EFFECT_LANDED("EffectLanded", "An effect the proc names lands: on the holder, or with Effect OnFoe, the "
            + "holder's own on a foe they struck", false, false),
    EFFECT_ENDED("EffectEnded", "An effect the proc names comes off (it ran out, or was taken off): the holder's, "
            + "or with Effect OnFoe, the holder's own on a foe they struck", false, false),
    HIT_INCOMING("HitIncoming", "A hit about to land on the holder, asked after every armor, guard and resistance "
            + "step and just before it comes off their health, so a Soak can still lower or stop it (the moment's "
            + "amount is the damage it would deal, its target the attacker; a fall or a burn has none)", true, true),
    ALLY_HIT_INCOMING("AllyHitIncoming", "A hit about to land on one of the holder's allies within the proc's "
            + "AllyRadius, asked the same way (the moment's target is the ally, its amount the damage it would "
            + "deal)", false, false),
    HEAL_GIVEN("HealGiven", "A heal the holder gives through CommonLib's heal (a talent's, an ability's), as it "
            + "lands (the moment's amount is the health it restores, its target the one healed; none when the holder "
            + "heals themselves); a tick of a heal over time never counts", false, false),
    GUARD_BROKE("GuardBroke", "A foe's guard broken under the holder: a hit of theirs it blocked drained it (the "
            + "engine's guard break landed on it soon after), or a proc of theirs broke it (the foe is the moment's "
            + "target)", false, false),
    FINISHER("Finisher", "A hit the holder lands that a mod decided closed a combo, the last swing of a chain (never "
            + "on a server with no mod deciding combos)", true, false);

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

    /** Is the moment about an effect landing or ending (so a proc's {@code Effect} group is read)? */
    public boolean aboutEffect() {
        return this == EFFECT_LANDED || this == EFFECT_ENDED;
    }

    /** Can the moment name the move behind it (so a proc's {@code Ability} group is read): a hit, or a cast? */
    public boolean namesMove() {
        return struck || this == CAST;
    }

    /**
     * Is the moment a hit that has not landed yet (so a {@code Soak} may still lower it and {@code LeavesUnder} reads
     * it): one on the holder, or on an ally of theirs?
     */
    public boolean incoming() {
        return this == HIT_INCOMING || this == ALLY_HIT_INCOMING;
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
