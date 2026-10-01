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

/**
 * The runtime table of named {@link RollPoolAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack shipping a file with an existing id replaces that pool
 * outright, and a server owner's layer wins over both.
 *
 * <h2>A running mod can add entries, through an entry source</h2>
 *
 * <p>A mod whose own content decides which outcomes a pool gains registers an {@link EntrySource}
 * under its owner id with {@link #registerEntrySource}, the same owner-keyed rules a loot table's
 * roll sources follow ({@link OwnedSources}). A source is asked at {@link #resolve} time, so it
 * always answers from the mod's current state, and its entries are appended AFTER the pool's
 * authored ones, sources in owner id order. Because {@link #resolve} is the one read every stamp
 * makes ({@link StampCapEngine#candidates}, {@link #poolOf}, a stamped reward's identity), every
 * site drawing from a pool draws the same entries. The rename and rarity a pool authors ride along
 * unchanged.
 *
 * <p>A source is code, not content: {@link #all} and {@link #resolveAuthored} never include its
 * entries, so its owner validates what it adds. A source never conjures a pool no file ships, and
 * a source that throws costs its own entries only: it is warned once (until its owner registers
 * again) and the pool resolves without them.
 */
public final class RollPoolConfig extends AbstractKeyedAssetConfig<RollPoolAsset> {

    private static final RollPoolConfig INSTANCE = new RollPoolConfig();

    /** Programmatic entry sources by owner id, asked in owner id order. */
    @Nonnull
    private final OwnedSources<EntrySource> entrySources = new OwnedSources<>();

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

    @Nonnull
    public static RollPoolConfig getInstance() {
        return INSTANCE;
    }

    private RollPoolConfig() {
    }

    /**
     * The pool {@code id} actually draws from: the winning file, its entries followed by every
     * registered {@link EntrySource}'s. Null when no file answers to {@code id}; a source never
     * conjures a pool that does not exist.
     */
    @Override
    @Nullable
    public RollPoolAsset resolve(@Nonnull String id) {
        RollPoolAsset pool = super.resolve(id);
        if (pool == null || entrySources.isEmpty()) {
            return pool;
        }
        String key = id.toLowerCase(Locale.ROOT);
        List<StatRollEntry> sourced = entrySources.collect(source -> source.entriesFor(key),
                owner -> "roll pool '" + key + "': the entry source of '" + owner + "'");
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
        String poolId = spec == null ? null : spec.getPool();
        return poolId == null || poolId.isBlank() ? null : resolve(poolId);
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

    /** Route failing-source warnings to {@code sink} (null restores the log). Tests only. */
    void warnInto(@Nullable Consumer<String> sink) {
        entrySources.warnInto(sink);
    }
}
