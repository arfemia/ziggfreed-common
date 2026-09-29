package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.stats.EquipStatBridge;
import com.ziggfreed.common.stats.EquipStatBridge.StatKey;
import com.ziggfreed.common.stats.EquipStatBridge.UtilityPlan;
import com.ziggfreed.common.stats.EquipStatBridge.UtilityPut;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * The PURE diff between what a player's active tiers should have written on the stat map and what
 * is there: which modifiers to put and which keys to sweep. No live {@code EntityStatMap}; the
 * existing modifier under a key is asked through a function, so the rule is pinned against a fake.
 *
 * <p>Every active tier is one prefixed source over {@link EquipStatBridge#planUtility}, the same
 * per-array-position diff-skip and stale-key sweep the held item's native {@code Utility} block
 * goes through, under {@link GearSetKeys.TierRef#prefix()}. A tier that was written last time and
 * is no longer active is swept the same way with nothing desired (every key under its prefix, on
 * every index). A key found on the entity that this engine wrote in an earlier boot and nothing
 * desires now ({@code strays}, read once per login) goes too.
 *
 * <p><b>Nothing here can touch another writer's key.</b> Every prefix comes from a {@link TierRef}
 * and every stray is filtered on {@link GearSetKeys#isOurs}, so the bridge's own
 * {@code ziggfreedcommon:} keys and a consumer's own are never probed and never removed; a test
 * pins it by recording every key the plan asks about.
 */
public final class GearSetPlan {

    /** One active tier and its stat block with the channels already resolved to indices. */
    public record Desired(@Nonnull TierRef tier, @Nonnull Int2ObjectMap<StaticModifier[]> byStatIndex) {
    }

    /** What to write and what to take off. */
    public record Plan(@Nonnull List<UtilityPut> puts, @Nonnull Set<StatKey> removes) {
    }

    private GearSetPlan() {
    }

    /**
     * A tier's authored stat block with each channel id resolved to its stat index through
     * {@code indexOf}; a channel that does not resolve ({@code < 0}) is named to {@code onUnknownStat}
     * and left out, never a throw. Two channels resolving to one index (a case variant) concatenate.
     */
    @Nonnull
    public static Int2ObjectMap<StaticModifier[]> resolve(@Nonnull GearSetAsset.Tier tier,
            @Nonnull ToIntFunction<String> indexOf, @Nonnull Consumer<String> onUnknownStat) {
        Int2ObjectMap<StaticModifier[]> out = new Int2ObjectOpenHashMap<>();
        for (Map.Entry<String, StatModifierSpec[]> entry : tier.statModifiers().entrySet()) {
            String statId = entry.getKey();
            StatModifierSpec[] specs = entry.getValue();
            if (statId == null || statId.isBlank() || specs == null || specs.length == 0) {
                continue;
            }
            int index = indexOf.applyAsInt(statId.trim());
            if (index < 0) {
                onUnknownStat.accept(statId.trim());
                continue;
            }
            List<StaticModifier> modifiers = new ArrayList<>(specs.length);
            StaticModifier[] already = out.get(index);
            if (already != null) {
                Collections.addAll(modifiers, already);
            }
            for (StatModifierSpec spec : specs) {
                if (spec != null) {
                    modifiers.add(spec.toModifier());
                }
            }
            out.put(index, modifiers.toArray(StaticModifier[]::new));
        }
        return out;
    }

    /**
     * The plan for one recompute.
     *
     * @param desired      the active tiers with their resolved stat blocks
     * @param lastWritten  the tiers written on the previous recompute (swept where no longer desired)
     * @param strays       every key of this engine's found on the entity by the once-per-login read,
     *                     each removed unless a desired tier writes it now; empty on every other pass
     * @param statMapSize  how many stat indices the entity's map has
     * @param existing     the {@link StaticModifier} under (index, key), or null when none
     */
    @Nonnull
    public static Plan plan(@Nonnull Collection<Desired> desired, @Nonnull Collection<TierRef> lastWritten,
            @Nonnull Collection<StatKey> strays, int statMapSize,
            @Nonnull BiFunction<Integer, String, StaticModifier> existing) {
        List<UtilityPut> puts = new ArrayList<>();
        Set<StatKey> removes = new LinkedHashSet<>();
        Set<String> desiredPrefixes = new LinkedHashSet<>();
        Set<StatKey> desiredKeys = new LinkedHashSet<>();

        for (Desired d : desired) {
            String prefix = d.tier().prefix();
            desiredPrefixes.add(prefix);
            desiredKeys.addAll(keysOf(d));
            UtilityPlan tierPlan = EquipStatBridge.planUtility(d.byStatIndex(), prefix, statMapSize, existing);
            puts.addAll(tierPlan.puts);
            removes.addAll(tierPlan.removes);
        }
        for (TierRef stale : lastWritten) {
            String prefix = stale.prefix();
            if (desiredPrefixes.contains(prefix)) {
                continue;
            }
            removes.addAll(EquipStatBridge.planUtility(null, prefix, statMapSize, existing).removes);
        }
        for (StatKey stray : strays) {
            if (GearSetKeys.isOurs(stray.key) && !desiredKeys.contains(stray)) {
                removes.add(stray);
            }
        }
        return new Plan(puts, removes);
    }

    /** Every (index, key) a desired tier writes, whether or not the put is diff-skipped. */
    @Nonnull
    static Set<StatKey> keysOf(@Nonnull Desired d) {
        Set<StatKey> out = new LinkedHashSet<>();
        String prefix = d.tier().prefix();
        for (Int2ObjectMap.Entry<StaticModifier[]> e : d.byStatIndex().int2ObjectEntrySet()) {
            StaticModifier[] arr = e.getValue();
            if (arr == null) {
                continue;
            }
            int offset = 0;
            for (StaticModifier modifier : arr) {
                if (modifier != null) {
                    out.add(new StatKey(e.getIntKey(), prefix + offset));
                    offset++;
                }
            }
        }
        return out;
    }

    /** The prefixes of every desired tier, what the applied table remembers for the next sweep. */
    @Nonnull
    public static Set<TierRef> tiersOf(@Nonnull Collection<Desired> desired) {
        Set<TierRef> out = new LinkedHashSet<>();
        for (Desired d : desired) {
            out.add(d.tier());
        }
        return out;
    }
}
