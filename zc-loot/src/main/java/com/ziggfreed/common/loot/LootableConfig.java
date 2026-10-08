package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnedSources;

/**
 * The runtime table of named {@link LootableAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack that ships a file with an existing id replaces that table
 * outright, and a server owner's layer wins over both.
 *
 * <h2>Contributions are folded on top, after the id layering</h2>
 *
 * <p>A file naming another table in {@code ContributesTo} does not compete for that id - it ENRICHES
 * whichever version of it won. So the order is: pick the winning file for each id the ordinary way,
 * then append every contributor's rolls and pool entries to it. That order is what lets an owner
 * override a table without losing the contributions other packs made to it, and lets a contributing
 * pack be removed without touching the table it enriched.
 *
 * <p>{@link #resolve} answers the ENRICHED table, because that is what actually pays out and every
 * reader wants the same answer. {@link #all} and {@link #resolveAuthored} answer the files as they
 * were written, which is what a validator and an editor pick list want: a contributor's own mistakes
 * belong to its own file, and reporting them twice helps nobody.
 *
 * <h2>A running mod can add rolls too, through a roll source</h2>
 *
 * <p>A file is not the only way to enrich a table. A mod whose own content decides which rolls a
 * table gains (rolls it composes from assets of its own, say) registers a {@link RollSource} under
 * its owner id with {@link #registerRollSource}. A source is asked at {@link #resolve} time, so it
 * always answers from the mod's current state and nothing has to be refolded when that state
 * changes. Its rolls are appended AFTER every {@code ContributesTo} contributor's, sources in owner
 * id order, and they never touch the table's pool. Because the one read every site goes through is
 * {@link #resolve} ({@link LootEngine#resolve}, {@link LootEngine#resolveRolls} and a nested
 * {@code Lootable} reward included), every site that rolls a table sees the same rolls.
 *
 * <p>A source is code, not content: {@link #all} and {@link #resolveAuthored} never include its
 * rolls, so its owner validates what it adds. A source that throws costs its own rolls only: it is
 * warned once (until its owner registers again) and the table resolves without them. Those
 * owner-keyed rules are {@link OwnedSources}'s, the same ones a roll pool's entry sources follow
 * ({@code stamp.RollPoolConfig}).
 */
public final class LootableConfig extends AbstractKeyedAssetConfig<LootableAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Lootables";

    private static final LootableConfig INSTANCE = new LootableConfig();

    /** Enriched tables by id, rebuilt whenever a layer changes. Empty when nothing contributes. */
    @Nonnull
    private volatile Map<String, LootableAsset> enriched = Map.of();

    /** Contributor ids by the target id each names, including targets no table answers to. */
    @Nonnull
    private volatile Map<String, List<String>> contributorIds = Map.of();

    /** Programmatic roll sources by owner id, asked in owner id order (the shared owner-keyed rules). */
    @Nonnull
    private final OwnedSources<RollSource> rollSources = new OwnedSources<>();

    /**
     * Rolls a running mod appends to a table whenever it is resolved.
     *
     * <p>Asked on every {@link #resolve}, so it should answer from state the owner already holds
     * (a map lookup, not a scan); an empty answer is the cheap and common one.
     */
    @FunctionalInterface
    public interface RollSource {

        /**
         * The rolls to append to table {@code tableId} (lower-cased), in the order they should run;
         * null or empty adds nothing, and a null entry is skipped.
         */
        @Nullable
        List<Roll> rollsFor(@Nonnull String tableId);
    }

    @Nonnull
    public static LootableConfig getInstance() {
        return INSTANCE;
    }

    private LootableConfig() {
        super(MOD_GATE_STORE);
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, LootableAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        rebuildContributions();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, LootableAsset> layer) {
        super.mergePackLayer(layer);
        rebuildContributions();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, LootableAsset> layer) {
        super.mergeOwnerLayer(layer);
        rebuildContributions();
    }

    /**
     * The table {@code id} actually pays out: the winning file, enriched by every contributor to it,
     * then by every registered {@link RollSource}'s rolls. Null when no file answers to {@code id};
     * a source never conjures a table that does not exist.
     */
    @Override
    @Nullable
    public LootableAsset resolve(@Nonnull String id) {
        String key = id.toLowerCase(Locale.ROOT);
        LootableAsset folded = enriched.get(key);
        LootableAsset table = folded != null ? folded : super.resolve(id);
        if (table == null || rollSources.isEmpty()) {
            return table;
        }
        List<Roll> sourced = sourcedRolls(key);
        if (sourced.isEmpty()) {
            return table;
        }
        List<Roll> rolls = new ArrayList<>(table.rollsOrEmpty());
        rolls.addAll(sourced);
        return LootableAsset.of(table.getId() == null ? key : table.getId(), rolls.toArray(Roll[]::new),
                table.getPool(), table.getContributesTo());
    }

    // ==================== roll sources ====================

    /**
     * Register {@code source} under {@code owner} (matched without regard to case). Registering an
     * owner again REPLACES its source, so a mod that re-runs its setup never doubles its rolls.
     */
    public void registerRollSource(@Nonnull String owner, @Nonnull RollSource source) {
        rollSources.register(owner, source);
    }

    /** Remove {@code owner}'s source, so its rolls vanish from the next resolve. A no-op when none. */
    public void unregisterRollSource(@Nonnull String owner) {
        rollSources.unregister(owner);
    }

    /** Every source's rolls for {@code key}, in owner order; a throwing source adds nothing. */
    @Nonnull
    private List<Roll> sourcedRolls(@Nonnull String key) {
        return rollSources.collect(source -> source.rollsFor(key),
                owner -> "loot table '" + key + "': the roll source of '" + owner + "'");
    }

    /** Route failing-source warnings to {@code sink} (null restores the log). Tests only. */
    void warnInto(@Nullable Consumer<String> sink) {
        rollSources.warnInto(sink);
    }

    /** The winning FILE for {@code id}, with no contributions folded in - what its author wrote. */
    @Nullable
    public LootableAsset resolveAuthored(@Nonnull String id) {
        return super.resolve(id);
    }

    /** The ids of every table contributing to {@code id}, sorted; empty when none do. */
    @Nonnull
    public List<String> contributorsOf(@Nonnull String id) {
        return contributorIds.getOrDefault(id.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * Every {@code ContributesTo} target no loaded table answers to. Each one is a contribution that
     * silently pays nothing, which is exactly what the validator exists to say out loud.
     */
    @Nonnull
    public Set<String> unresolvedContributionTargets() {
        Set<String> out = new TreeSet<>();
        for (String target : contributorIds.keySet()) {
            if (super.resolve(target) == null) {
                out.add(target);
            }
        }
        return out;
    }

    // ==================== the fold ====================

    /**
     * Recompute the enriched view from the current layers. Cheap (a table count, not a roll count)
     * and rerun on every merge, so a hot re-import can never leave a contribution attached to a file
     * that has since been replaced.
     */
    private void rebuildContributions() {
        Map<String, List<LootableAsset>> byTarget = new TreeMap<>();
        for (Map.Entry<String, LootableAsset> entry : all().entrySet()) {
            LootableAsset asset = entry.getValue();
            String target = asset == null ? null : asset.getContributesTo();
            if (target == null || target.isBlank()) {
                continue;
            }
            String key = target.toLowerCase(Locale.ROOT);
            if (key.equals(entry.getKey())) {
                // A file naming itself is already its own content; folding it in would double it.
                continue;
            }
            byTarget.computeIfAbsent(key, k -> new ArrayList<>()).add(asset);
        }

        Map<String, List<String>> ids = new LinkedHashMap<>();
        Map<String, LootableAsset> folded = new LinkedHashMap<>();
        for (Map.Entry<String, List<LootableAsset>> entry : byTarget.entrySet()) {
            String target = entry.getKey();
            List<LootableAsset> contributors = entry.getValue();
            contributors.sort(Comparator.comparing(a -> a.getId() == null ? "" : a.getId()));
            ids.put(target, contributors.stream().map(a -> a.getId() == null ? "" : a.getId()).toList());
            LootableAsset base = super.resolve(target);
            if (base != null) {
                folded.put(target, merge(target, base, contributors));
            }
        }
        this.contributorIds = Map.copyOf(ids);
        this.enriched = Map.copyOf(folded);
    }

    /** {@code base} with every contributor's rolls appended and every pool entry added to its bag. */
    @Nonnull
    private static LootableAsset merge(@Nonnull String id, @Nonnull LootableAsset base,
            @Nonnull List<LootableAsset> contributors) {
        List<Roll> rolls = new ArrayList<>(base.rollsOrEmpty());
        List<LootPool.Entry> entries = new ArrayList<>();
        collectEntries(base.getPool(), entries);
        // The target decides how often its pool is drawn. A target with no pool of its own borrows the
        // first contributor's formula, so a pool that exists only through contributions still draws.
        LootPool picksFrom = base.getPool();
        for (LootableAsset contributor : contributors) {
            rolls.addAll(contributor.rollsOrEmpty());
            collectEntries(contributor.getPool(), entries);
            if (picksFrom == null) {
                picksFrom = contributor.getPool();
            }
        }
        LootPool pool = entries.isEmpty() && picksFrom == null
                ? null
                : LootPool.of(picksFrom == null ? null : picksFrom.getRolls(),
                        entries.toArray(LootPool.Entry[]::new));
        return LootableAsset.of(id, rolls.isEmpty() ? null : rolls.toArray(Roll[]::new), pool, null);
    }

    private static void collectEntries(@Nullable LootPool pool, @Nonnull List<LootPool.Entry> out) {
        if (pool == null || pool.getEntries() == null) {
            return;
        }
        for (LootPool.Entry entry : pool.getEntries()) {
            if (entry != null) {
                out.add(entry);
            }
        }
    }
}
