package com.ziggfreed.common.loot.stamp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnedSources;
import com.ziggfreed.common.factor.FactorContext;

/**
 * The runtime table of named {@link RollPoolAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack shipping a file with an existing id replaces that pool
 * outright, and a server owner's layer wins over both.
 *
 * <h2>A pool may name the code that adds its per-roll entries: a named source</h2>
 *
 * <p>A pool file's {@code EntrySource} names a {@link NamedSource} a running mod registers under that
 * name ({@link #registerNamedSource}). Each roll asks it with the roll's moment ({@link FactorContext}:
 * the item being stamped, who stamps it), so a pool whose outcomes depend on the item (a weapon's own
 * skill) is still one pool every site draws the same way. Its entries follow the pool's authored
 * ones. Nothing registered under the name (a server without that mod, a misspelt name) and the pool
 * rolls its authored entries alone, never fails; a named source that throws is warned once and adds
 * nothing.
 *
 * <h2>A running mod can add entries to any pool, through an entry source</h2>
 *
 * <p>A mod whose own content decides which outcomes a pool gains registers an {@link EntrySource}
 * under its owner id with {@link #registerEntrySource}, the same owner-keyed rules a loot table's
 * roll sources follow ({@link OwnedSources}). A source is asked at {@link #resolve} time, so it
 * always answers from the mod's current state, and its entries are appended AFTER the pool's
 * authored ones and its named source's, sources in owner id order. Because {@link #resolve} is the
 * one read every stamp makes ({@link StampCapEngine#candidates}, {@link #poolOf}, a stamped reward's
 * identity), every site drawing from a pool draws the same entries. The rename and rarity a pool
 * authors ride along unchanged.
 *
 * <p>Either source is code, not content: {@link #all} and {@link #resolveAuthored} never include its
 * entries, so its owner validates what it adds. A source never conjures a pool no file ships, and
 * a source that throws costs its own entries only: it is warned once (until its owner registers
 * again) and the pool resolves without them.
 */
public final class RollPoolConfig extends AbstractKeyedAssetConfig<RollPoolAsset> {

    private static final RollPoolConfig INSTANCE = new RollPoolConfig();

    /** The moment a named source is handed when the read knows none: every leaf empty. */
    private static final FactorContext NO_MOMENT = FactorContext.builder().build();

    /** Programmatic entry sources by owner id, asked in owner id order. */
    @Nonnull
    private final OwnedSources<EntrySource> entrySources = new OwnedSources<>();

    /** Named sources by the name a pool's {@code EntrySource} gives, each asked only by the pools naming it. */
    @Nonnull
    private final OwnedSources<NamedSource> namedSources = new OwnedSources<>();

    /**
     * Entries a running mod appends to a pool whenever it is resolved.
     *
     * <p>Asked on every {@link #resolve}, so it should answer from state the owner already holds
     * (a map lookup, not a scan); an empty answer is the cheap and common one.
     */
    @FunctionalInterface
    public interface EntrySource {

        /**
         * The entries to append to pool {@code poolId} (lower-cased), in order; null or empty adds
         * nothing, and a null entry is skipped.
         */
        @Nullable
        List<StatRollEntry> entriesFor(@Nonnull String poolId);
    }

    /**
     * The entries a pool naming this source (its {@code EntrySource}) draws for one roll, from the
     * roll's moment.
     *
     * <p>Asked once per stamp, so it may read the item and the player; it never rolls anything itself
     * (the stamp draws among what it answers, so a weighted choice is weighted entries, and the odds
     * stay the engine's).
     */
    @FunctionalInterface
    public interface NamedSource {

        /**
         * The entries pool {@code poolId} (lower-cased) gains for a roll at {@code roll}: its item
         * ({@link FactorContext#item()}, absent when the read is about no item), its player and world.
         * Null or empty adds nothing, and a null entry is skipped.
         */
        @Nullable
        List<StatRollEntry> entriesFor(@Nonnull String poolId, @Nonnull FactorContext roll);
    }

    @Nonnull
    public static RollPoolConfig getInstance() {
        return INSTANCE;
    }

    private RollPoolConfig() {
    }

    /**
     * The pool {@code id} actually draws from, read about no moment: {@link #resolve(String, FactorContext)}
     * with none, so a named source is handed an empty context.
     */
    @Override
    @Nullable
    public RollPoolAsset resolve(@Nonnull String id) {
        return resolve(id, null);
    }

    /**
     * The pool {@code id} actually draws from for a roll at {@code roll}: the winning file, its
     * entries followed by its named source's (asked with {@code roll}, an empty context when null)
     * and then every registered {@link EntrySource}'s. Null when no file answers to {@code id}; a
     * source never conjures a pool that does not exist.
     */
    @Nullable
    public RollPoolAsset resolve(@Nonnull String id, @Nullable FactorContext roll) {
        RollPoolAsset pool = super.resolve(id);
        if (pool == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        List<StatRollEntry> sourced = new ArrayList<>();
        String named = pool.getEntrySource();
        if (named != null && !named.isBlank()) {
            FactorContext moment = roll != null ? roll : NO_MOMENT;
            sourced.addAll(namedSources.collectFrom(named.trim(), source -> source.entriesFor(key, moment),
                    name -> "roll pool '" + key + "': the named entry source '" + name + "'"));
        }
        if (!entrySources.isEmpty()) {
            sourced.addAll(entrySources.collect(source -> source.entriesFor(key),
                    owner -> "roll pool '" + key + "': the entry source of '" + owner + "'"));
        }
        if (sourced.isEmpty()) {
            return pool;
        }
        List<StatRollEntry> entries = new ArrayList<>();
        if (pool.getEntries() != null) {
            entries.addAll(Arrays.asList(pool.getEntries()));
        }
        entries.addAll(sourced);
        return pool.withEntries(entries.toArray(StatRollEntry[]::new));
    }

    /**
     * The pool a stamp spec names, through {@link #resolve}: null when the spec is null, names no
     * pool, or names one nothing answers to.
     */
    @Nullable
    public RollPoolAsset poolOf(@Nullable StampSpec spec) {
        return poolOf(spec, null);
    }

    /** {@link #poolOf(StampSpec)} for a roll at {@code roll}, which the pool's named source is handed. */
    @Nullable
    public RollPoolAsset poolOf(@Nullable StampSpec spec, @Nullable FactorContext roll) {
        String poolId = spec == null ? null : spec.getPool();
        return poolId == null || poolId.isBlank() ? null : resolve(poolId, roll);
    }

    /** The winning FILE for {@code id}, with no source's entries - what its author wrote. */
    @Nullable
    public RollPoolAsset resolveAuthored(@Nonnull String id) {
        return super.resolve(id);
    }

    // ==================== entry sources ====================

    /**
     * Register {@code source} under {@code owner} (matched without regard to case). Registering an
     * owner again REPLACES its source, so a mod that re-runs its setup never doubles its entries.
     */
    public void registerEntrySource(@Nonnull String owner, @Nonnull EntrySource source) {
        entrySources.register(owner, source);
    }

    /** Remove {@code owner}'s source, so its entries vanish from the next resolve. A no-op when none. */
    public void unregisterEntrySource(@Nonnull String owner) {
        entrySources.unregister(owner);
    }

    // ==================== named sources ====================

    /**
     * Register {@code source} under {@code name} (matched without regard to case), the name a pool's
     * {@code EntrySource} gives. Registering a name again REPLACES its source. Name it after the mod
     * ({@code Mymod_Weapon_Rolls}), since every mod's names share one table.
     */
    public void registerNamedSource(@Nonnull String name, @Nonnull NamedSource source) {
        namedSources.register(name, source);
    }

    /** Remove the source named {@code name}, so a pool naming it rolls its own entries alone. A no-op when none. */
    public void unregisterNamedSource(@Nonnull String name) {
        namedSources.unregister(name);
    }

    /** Route failing-source warnings to {@code sink} (null restores the log). Tests only. */
    void warnInto(@Nullable Consumer<String> sink) {
        entrySources.warnInto(sink);
        namedSources.warnInto(sink);
    }
}
