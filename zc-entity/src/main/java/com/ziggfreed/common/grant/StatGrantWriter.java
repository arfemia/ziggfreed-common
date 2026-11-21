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
import com.ziggfreed.common.stats.StatIndexCache.Landing;
import com.ziggfreed.common.stats.condition.BoundGrants;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writes {@link StatGrant}s: one native additive {@code StaticModifier} on a stat's maximum per key and stat, the
 * amounts of every placement under that key summed (times how often each is held). It reads back every modifier it
 * finds under the owner's prefix on the entity's stat map (the map is saved with the entity, so what an earlier
 * session wrote is found too), plans the difference ({@link StatGrantPlan}) and writes only that, through the stat
 * map's keyed put and remove. A grant naming a retired stat id ({@code StatAliases}) writes each of its successors at
 * the alias's scale, as a saved roll reads. A grant bound to a condition ({@code While}) is handed to
 * {@code BoundGrants} instead, whose holder pass puts or takes off its modifier as the condition holds. The keys under
 * an owner's prefix it never owns are a runtime writer's, an {@code @} straight after the prefix: the conditional
 * fold's ({@code ConditionalStats.MODIFIER_PREFIX}), a bound grant's ({@code BoundGrants.MODIFIER_PREFIX}) and a
 * counter's stacks (zc-track's {@code proc/}), all under {@code zigtrack:} for the rollback sweep. World thread.
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
        BoundGrants.set(stats, ref, owner, bound(placed, prefix));
    }

    /**
     * What {@code placed} binds to a condition (a grant with {@code While}): one bound amount per placement and
     * grant, its id the placement's key past the owner's prefix, the stat and the grant's place among that key's
     * bound grants, so a rebuild names the same amount the same way. The holder pass writes them ({@link BoundGrants}).
     */
    @Nonnull
    static List<BoundGrants.Bound> bound(@Nonnull List<Placed> placed, @Nonnull String prefix) {
        List<BoundGrants.Bound> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Placed one : placed) {
            if (!(one.grant() instanceof StatGrant grant) || !grant.bound() || one.count() <= 0
                    || grant.stat() == null) {
                continue;
            }
            String key = one.key().startsWith(prefix) ? one.key().substring(prefix.length()) : one.key();
            // Counted before the stat check, so a key's later grants keep their ids whether or not this stat loads.
            int place = seen.merge(key, 1, Integer::sum);
            for (Landing landing : StatIndexCache.landingsOf(grant.stat())) {
                double amount = grant.amountOn(baseMax(landing.index())) * landing.scale() * one.count();
                out.add(BoundGrants.Bound.additive(key + ":" + landing.statId() + ":" + place, landing.statId(),
                        amount, grant.whileConditions(), grant.holdSeconds()));
            }
        }
        return out;
    }

    /** What {@code placed} wants on the stat map: per stat and key, the summed amount. A bound grant is never here. */
    @Nonnull
    static Map<StatGrantPlan.Target, Float> wanted(@Nonnull List<Placed> placed) {
        Map<StatGrantPlan.Target, Float> out = new LinkedHashMap<>();
        for (Placed one : placed) {
            if (!(one.grant() instanceof StatGrant grant) || one.count() <= 0 || grant.stat() == null
                    || grant.bound()) {
                continue;
            }
            for (Landing landing : StatIndexCache.landingsOf(grant.stat())) {
                double amount = grant.amountOn(baseMax(landing.index())) * landing.scale() * one.count();
                if (amount == 0.0 || !Double.isFinite(amount)) {
                    continue;
                }
                out.merge(new StatGrantPlan.Target(landing.index(), one.key()), (float) amount, Float::sum);
            }
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
                // A key with an @ right after the owner's prefix is a runtime writer's, never a grant (no track id
                // holds an @): the conditional fold's Onto modifiers (zigtrack:@cond:) and a counter's stacks
                // (zigtrack:@counter:) sit under zigtrack: for the 1.7.x rollback sweep (R755), and a rebuild that
                // took them off would leave them off until they change.
                if (key == null || !key.startsWith(prefix) || isRuntimeKey(key, prefix)) {
                    continue;
                }
                out.put(new StatGrantPlan.Target(i, key), amountOf(modifiers.get(key)));
            }
        }
        return out;
    }

    /**
     * Is {@code key} (under {@code prefix}) a runtime writer's rather than a grant's: an {@code @} straight after the
     * owner's prefix ({@code zigtrack:@cond:}, {@code zigtrack:@counter:}), or the conditional fold's own prefix?
     */
    static boolean isRuntimeKey(@Nonnull String key, @Nonnull String prefix) {
        return ConditionalStats.writesKey(key) || BoundGrants.writesKey(key)
                || (key.length() > prefix.length() && key.startsWith(prefix) && key.charAt(prefix.length()) == '@');
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
