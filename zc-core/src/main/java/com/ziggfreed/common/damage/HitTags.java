package com.ziggfreed.common.damage;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCalculatorSystems;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;

/**
 * Reads back, at damage time, the native asset {@code Tags} authored on the interaction that dealt a
 * hit, so a consumer can tell one hit from another without a damage type of its own.
 *
 * <p><b>Authoring.</b> Put the tag on the {@code DamageEntity} node that deals the damage, or on any
 * asset containing it: a burst Selector file carrying {@code "Tags": {"Hit": ["KweebecNightmare_Moonbloom"]}}
 * tags the inline {@code HitEntity} damage node inside it, and {@code "Tags": {"MmoAbility":
 * ["Tempest"]}} on a {@code DamageEntity} node tags that node alone. No codec field, component or
 * damage system is added: the engine already attaches the node's {@code DamageCalculator} to the
 * hit ({@code DamageCalculatorSystems.DAMAGE_SEQUENCE}), and {@link HitTagIndex} maps that object
 * back to the node's tags. Works on a server running this library alone.
 *
 * <p><b>Keys.</b> {@link #KEY} ({@code Hit}) is always indexed; a consumer reading another key
 * registers it once at setup with {@link #indexKey}. Keys match ignoring case; values come back in
 * their authored spelling.
 *
 * <p><b>What reads untagged.</b> A hit with no calculator attached (fall, an explosion, an effect's
 * damage over time, a mod's own {@code Damage}); every hit but the first of a strike whose calculator
 * names more than one damage type (the engine splits it into one hit per type and attaches the
 * calculator to the first only), so keep a tagged node to one type; a node sharing its calculator
 * with a node tagged differently (a {@code Parent} child that authors no {@code DamageCalculator} of
 * its own), so give a tagged child its own; and, for a moment after an interaction reload, a chain
 * that was already running. A tagged node naming several types, and a shared calculator, each log
 * one warning when the table is built.
 *
 * <p>Thread-safe and lock-free on the read path; the first read after an interaction load rebuilds
 * the table once.
 */
public final class HitTags {

    /** The tag key every consumer shares for naming a hit: {@code "Tags": {"Hit": [...]}}. */
    public static final String KEY = "Hit";

    private HitTags() {
    }

    /**
     * Index another {@code Tags} key, so {@link #values} and {@link #declared} answer for it. Keys
     * match ignoring case. {@link #KEY} is indexed already. Call once at setup; a new key marks the
     * table dirty.
     */
    public static void indexKey(@Nonnull String tagKey) {
        HitTagIndex.indexKey(tagKey);
    }

    /** The values under {@link #KEY} on the hit's damage node, in authored spelling; empty when untagged or unresolved. */
    @Nonnull
    public static List<String> of(@Nonnull Damage d) {
        return values(d, KEY);
    }

    /**
     * The values under {@code tagKey} on the hit's damage node (merged with every asset containing
     * it), in authored spelling; empty when the hit is untagged, carries no calculator, or the key is
     * not indexed (which logs once).
     */
    @Nonnull
    public static List<String> values(@Nonnull Damage d, @Nonnull String tagKey) {
        DamageCalculator calculator = calculatorOf(d);
        if (calculator == null) {
            return List.of();
        }
        return HitTagIndex.values(calculator, tagKey);
    }

    /** Whether the hit's {@link #KEY} values contain {@code value}, ignoring case. */
    public static boolean has(@Nonnull Damage d, @Nonnull String value) {
        for (String tag : of(d)) {
            if (tag.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every value any loaded interaction declares under {@code tagKey}, for audits (a key naming a tag
     * no content carries). Sorted and compared ignoring case, so its {@code contains} ignores case.
     */
    @Nonnull
    public static Set<String> declared(@Nonnull String tagKey) {
        return HitTagIndex.declared(tagKey);
    }

    /** Drop the table so the next read rebuilds it; the library calls this on every interaction load and removal. */
    public static void markDirty() {
        HitTagIndex.markDirty();
    }

    /** The calculator the engine attached to the hit, or null when it carries none. */
    @Nullable
    private static DamageCalculator calculatorOf(@Nonnull Damage d) {
        try {
            DamageCalculatorSystems.DamageSequence sequence =
                    d.getIfPresentMetaObject(DamageCalculatorSystems.DAMAGE_SEQUENCE);
            return sequence == null ? null : sequence.getDamageCalculator();
        } catch (Throwable t) {
            // A meta store that cannot be read (an engine class not initialised in a bare JVM): untagged.
            return null;
        }
    }
}
