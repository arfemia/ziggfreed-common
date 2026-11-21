package com.ziggfreed.common.stats.condition;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The kinds of crowd control a foe's rules name ({@link BossRules}), a closed list: a freeze counts as a stun, a grab
 * or a drive as a carry, a flinch and a guard break as an interrupt. An effect's kind is its effect family's
 * {@code Control} ({@link EffectFamilyAsset}); a step of a fixed kind (a push, a taunt, a guard break) names its own.
 * Authored by {@link #id()}, matched without regard to case.
 */
public enum ControlKind {

    STUN("Stun", "A stun, a stagger, a knockdown or a freeze"),
    HOLD("Hold", "A root, a snare or a pin in place"),
    SLOW("Slow", "A slow"),
    PUSH("Push", "A knockback, a launch or a lift"),
    PULL("Pull", "A pull toward whoever cast it"),
    CARRY("Carry", "A grab or a drive that carries the foe"),
    INTERRUPT("Interrupt", "A flinch or a guard break"),
    TAUNT("Taunt", "A taunt that turns the foe onto its caster"),
    DECOY("Decoy", "A decoy that draws the foe away"),
    WEAKNESS("Weakness", "A weakness: the foe takes more damage"),
    WITHER("Wither", "A wither: the foe deals less damage"),
    MARK("Mark", "A mark: a counter's stacks and the look they show");

    private static final ControlKind[] ALL = values();

    @Nonnull
    private final String id;
    @Nonnull
    private final String meaning;

    ControlKind(@Nonnull String id, @Nonnull String meaning) {
        this.id = id;
        this.meaning = meaning;
    }

    /** The authored name ({@code Stun}, {@code Taunt}). */
    @Nonnull
    public String id() {
        return id;
    }

    /** A one-line meaning, for the Asset Editor's dropdown. */
    @Nonnull
    public String meaning() {
        return meaning;
    }

    /** This kind's bit in a mask of kinds. */
    public int bit() {
        return 1 << ordinal();
    }

    /** Is this kind in {@code mask}? */
    public boolean in(int mask) {
        return (mask & bit()) != 0;
    }

    /** The kind named {@code raw} (trimmed, any case), or null for a blank or unknown name. */
    @Nullable
    public static ControlKind parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (ControlKind kind : ALL) {
            if (kind.id.toLowerCase(Locale.ROOT).equals(wanted)) {
                return kind;
            }
        }
        return null;
    }

    /** Every kind's authored name, in order. */
    @Nonnull
    public static String[] ids() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = ALL[i].id;
        }
        return out;
    }

    /** Alternating name and meaning, every kind in order: the editor's documented dropdown. */
    @Nonnull
    public static String[] editorPairs() {
        String[] out = new String[ALL.length * 2];
        for (int i = 0; i < ALL.length; i++) {
            out[i * 2] = ALL[i].id;
            out[i * 2 + 1] = ALL[i].meaning;
        }
        return out;
    }

    /** The kinds in {@code mask}, in order. */
    @Nonnull
    public static Set<ControlKind> ofMask(int mask) {
        EnumSet<ControlKind> out = EnumSet.noneOf(ControlKind.class);
        for (ControlKind kind : ALL) {
            if (kind.in(mask)) {
                out.add(kind);
            }
        }
        return out;
    }
}
