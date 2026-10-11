package com.ziggfreed.common.effect.grid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;

/**
 * The {@code EntityEffect} bodies a {@link RuntimeEffectGrid} synthesizes: one damage-modifier map
 * ({@code OutgoingDamage} or {@code DamageResistance}) keyed on damage causes, each key carrying one
 * modifier of one {@code CalculationType}. Pure string building through Gson, so a body is testable
 * without a server.
 *
 * <p><b>Outgoing keys match children, resistance keys do not.</b> The engine's outgoing-damage step
 * walks a hit's cause up its {@code Inherits} chain and sums EVERY key it meets, so an
 * {@code OutgoingDamage} map keyed on both {@code Physical} and {@code Slashing} pays a slashing hit
 * twice. {@link #rootCauses} drops every cause whose ancestor is already listed, which is the key set
 * an outgoing body wants. The resistance lookup matches the exact cause instead, so a resistance body
 * keys every cause it means to cover.
 */
public final class EffectBodies {

    private EffectBodies() {
    }

    /** The damage-dealt modifier map: a positive {@code Percent} raises the carrier's hits. */
    public static final String OUTGOING_DAMAGE = "OutgoingDamage";

    /** The damage-taken modifier map: a positive {@code Percent} shrinks hits on the carrier. */
    public static final String DAMAGE_RESISTANCE = "DamageResistance";

    /** A fraction of the hit (0.25 is a quarter). */
    public static final String PERCENT = "Percent";

    /** A whole amount of damage, added (outgoing) or taken off (resistance) before the percent. */
    public static final String FLAT = "Flat";

    /**
     * One effect body: {@code field} keyed on each of {@code causeIds} with one {@code calculationType}
     * modifier of {@code amount}, invisible (no {@code StatusEffectIcon}), never a debuff. An
     * {@code infinite} body stays until removed; a timed one takes its duration from the apply call
     * and overwrites itself when re-applied.
     */
    @Nonnull
    public static String damageModifiers(@Nonnull String field, @Nonnull Collection<String> causeIds,
            @Nonnull String calculationType, double amount, @Nullable String comment, boolean infinite) {
        JsonObject root = new JsonObject();
        if (comment != null && !comment.isBlank()) {
            root.addProperty("$Comment", comment);
        }
        if (infinite) {
            root.addProperty("Infinite", true);
        } else {
            root.addProperty("OverlapBehavior", "Overwrite");
        }
        root.addProperty("Debuff", false);
        JsonObject map = new JsonObject();
        for (String cause : distinct(causeIds)) {
            JsonObject modifier = new JsonObject();
            modifier.addProperty("CalculationType", calculationType);
            modifier.addProperty("Amount", amount);
            JsonArray modifiers = new JsonArray();
            modifiers.add(modifier);
            map.add(cause, modifiers);
        }
        root.add(field, map);
        return root.toString();
    }

    /**
     * The causes of {@code causeIds} none of whose ancestors is also listed, in their given order: the
     * key set for an {@code OutgoingDamage} body, which the engine matches against a hit's whole
     * inheritance chain. Reads each cause's parent from the loaded {@code DamageCause} assets; a cause
     * that is not loaded is kept as its own root.
     */
    @Nonnull
    public static List<String> rootCauses(@Nonnull Collection<String> causeIds) {
        return rootCauses(causeIds, EffectBodies::loadedParentOf);
    }

    /** {@link #rootCauses(Collection)} over a caller-supplied parent lookup (null for a root). */
    @Nonnull
    public static List<String> rootCauses(@Nonnull Collection<String> causeIds,
            @Nonnull Function<String, String> parentOf) {
        List<String> listed = distinct(causeIds);
        Set<String> folded = new LinkedHashSet<>();
        for (String id : listed) {
            folded.add(id.toLowerCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        for (String id : listed) {
            if (!hasListedAncestor(id, folded, parentOf)) {
                out.add(id);
            }
        }
        return out;
    }

    private static boolean hasListedAncestor(@Nonnull String id, @Nonnull Set<String> folded,
            @Nonnull Function<String, String> parentOf) {
        Set<String> seen = new LinkedHashSet<>();
        String current = parentOf.apply(id);
        while (current != null && !current.isBlank() && seen.add(current.toLowerCase(Locale.ROOT))) {
            if (folded.contains(current.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = parentOf.apply(current);
        }
        return false;
    }

    @Nullable
    private static String loadedParentOf(@Nonnull String causeId) {
        try {
            DamageCause cause = DamageCause.getAssetMap().getAsset(causeId);
            return cause != null ? cause.getInherits() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Non-blank ids, first spelling kept, duplicates (ignoring case) dropped. */
    @Nonnull
    private static List<String> distinct(@Nonnull Collection<String> ids) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank() && seen.add(id.toLowerCase(Locale.ROOT))) {
                out.add(id);
            }
        }
        return out;
    }
}
