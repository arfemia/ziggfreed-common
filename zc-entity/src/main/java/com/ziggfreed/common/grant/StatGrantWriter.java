package com.ziggfreed.common.grant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writes {@link StatGrant}s: one native additive {@code StaticModifier} on a stat's maximum per key and stat, the
 * amounts of every placement under that key summed (times how often each is held). It reads back every modifier it
 * finds under the owner's prefix on the entity's stat map (the map is saved with the entity, so what an earlier
 * session wrote is found too), plans the difference ({@link StatGrantPlan}) and writes only that, through the stat
 * map's keyed put and remove. World thread.
 */
public final class StatGrantWriter implements GrantWriter {

    public static final StatGrantWriter INSTANCE = new StatGrantWriter();

    private StatGrantWriter() {
    }

    @Override
    public void write(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Placed> placed) {
        if (!ref.isValid()) {
            return;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
        if (stats == null) {
            return;
        }
        String prefix = owner + ":";
        StatGrantPlan.Plan plan = StatGrantPlan.plan(owned(stats, prefix), wanted(placed));
        for (StatGrantPlan.Target gone : plan.removes()) {
            stats.removeModifier(gone.stat(), gone.key());
        }
        for (Map.Entry<StatGrantPlan.Target, Float> put : plan.puts().entrySet()) {
            stats.putModifier(put.getKey().stat(), put.getKey().key(), new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, put.getValue()));
        }
    }

    /** What {@code placed} wants on the stat map: per stat and key, the summed amount. */
    @Nonnull
    static Map<StatGrantPlan.Target, Float> wanted(@Nonnull List<Placed> placed) {
        Map<StatGrantPlan.Target, Float> out = new LinkedHashMap<>();
        for (Placed one : placed) {
            if (!(one.grant() instanceof StatGrant grant) || one.count() <= 0 || grant.stat() == null) {
                continue;
            }
            int index = StatIndexCache.resolve(grant.stat());
            if (index == StatIndexCache.UNRESOLVED) {
                StatIndexCache.warnUnknownOnce(grant.stat());
                continue;
            }
            double amount = grant.amountOn(baseMax(index)) * one.count();
            if (amount == 0.0 || !Double.isFinite(amount)) {
                continue;
            }
            out.merge(new StatGrantPlan.Target(index, one.key()), (float) amount, Float::sum);
        }
        return out;
    }

    /** Every modifier under {@code prefix} on {@code stats}: its flat amount, or NaN for one of another shape. */
    @Nonnull
    private static Map<StatGrantPlan.Target, Float> owned(@Nonnull EntityStatMap stats, @Nonnull String prefix) {
        Map<StatGrantPlan.Target, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < stats.size(); i++) {
            EntityStatValue value = stats.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }
            List<String> keys = new ArrayList<>(modifiers.keySet());
            for (String key : keys) {
                if (key == null || !key.startsWith(prefix)) {
                    continue;
                }
                out.put(new StatGrantPlan.Target(i, key), amountOf(modifiers.get(key)));
            }
        }
        return out;
    }

    private static float amountOf(@Nullable Modifier modifier) {
        if (modifier instanceof StaticModifier flat && flat.getTarget() == Modifier.ModifierTarget.MAX
                && flat.getCalculationType() == StaticModifier.CalculationType.ADDITIVE) {
            return flat.getAmount();
        }
        return Float.NaN;
    }

    /** The stat's own base maximum (its asset's Max), 0 when the asset cannot be read. */
    private static double baseMax(int index) {
        try {
            EntityStatType asset = EntityStatType.getAssetMap().getAsset(index);
            return asset == null ? 0.0 : asset.getMax();
        } catch (Throwable t) {
            SafeLog.fine("[grant] a stat's base could not be read: " + t.getMessage());
            return 0.0;
        }
    }
}
