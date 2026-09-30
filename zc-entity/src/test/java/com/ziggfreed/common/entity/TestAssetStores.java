package com.ziggfreed.common.entity;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

/**
 * Seeds an engine asset map in a unit JVM, the way the engine's own tests do it: a store with no
 * event listeners and no file monitoring, swapped into the asset class's private static
 * {@code ASSET_STORE} slot for the length of one test and put back on {@link Seeded#close()}. So the
 * LIVE read paths ({@code ItemQuality.getAssetMap()}, {@code EntityStatType.getAssetMap()}) answer
 * from values the test itself authored, with no server and nothing registered process-wide.
 *
 * <p><b>One leak to know about</b>: {@code StatIndexCache} memoizes every stat id it resolves for
 * the life of the JVM, and its reset hook is package-private to production code, so a channel id
 * seeded here keeps resolving to its seeded index after {@link Seeded#close()}. Seed only ids no
 * other test asks about (a {@code Test_}-prefixed id unique to the test).
 *
 * <p>Like {@link TestItems}, it is used only from tests tagged {@code engine-items}, which run in the
 * {@code engineItemTest} task under the engine's own log manager ({@code gradle/zc-module.gradle}):
 * the engine's {@code AssetStore} constructor asks {@code HytaleLogger} for its logger, which
 * refuses to load under any other manager.
 */
public final class TestAssetStores {

    /** A seeded store, put back to what the slot held before on {@link #close()}. */
    public interface Seeded extends AutoCloseable {
        @Override
        void close();
    }

    private TestAssetStores() {
    }

    /** Quality index {@code i} resolves to {@code byIndex[i]}, and each quality's id to its index. */
    @Nonnull
    public static Seeded qualities(@Nonnull ItemQuality... byIndex) {
        String[] ids = new String[byIndex.length];
        for (int i = 0; i < byIndex.length; i++) {
            ids[i] = byIndex[i].getId();
        }
        return install(ItemQuality.class, new SeededMap<>(ItemQuality[]::new, ids, byIndex));
    }

    /** Stat channel id {@code idsByIndex[i]} resolves to index {@code i}. No channel asset is needed. */
    @Nonnull
    public static Seeded statChannels(@Nonnull String... idsByIndex) {
        return install(EntityStatType.class, new SeededMap<>(EntityStatType[]::new, idsByIndex, new EntityStatType[0]));
    }

    @Nonnull
    private static <T extends JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, T>>> Seeded install(
            @Nonnull Class<T> assetClass, @Nonnull SeededMap<T> map) {
        try {
            Field slot = assetClass.getDeclaredField("ASSET_STORE");
            slot.setAccessible(true);
            Object original = slot.get(null);
            slot.set(null, new SeededStore.Builder<>(assetClass, map).build());
            return () -> {
                try {
                    slot.set(null, original);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot seed the " + assetClass.getSimpleName() + " store", e);
        }
    }

    /** An asset map over fixed arrays: an id's index is its position, matched without regard to case. */
    private static final class SeededMap<T extends JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, T>>>
            extends IndexedLookupTableAssetMap<String, T> {

        private final List<String> ids;
        private final T[] assets;

        SeededMap(@Nonnull IntFunction<T[]> arrayProvider, @Nonnull String[] ids, @Nonnull T[] assets) {
            super(arrayProvider);
            this.ids = List.of(ids).stream().map(id -> id.toLowerCase(Locale.ROOT)).toList();
            this.assets = assets.clone();
        }

        @Override
        public int getIndex(String key) {
            int index = key == null ? -1 : ids.indexOf(key.toLowerCase(Locale.ROOT));
            return index < 0 ? AssetMapWithIndexes.NOT_FOUND : index;
        }

        @Override
        public int getIndexOrDefault(String key, int def) {
            int index = getIndex(key);
            return index == AssetMapWithIndexes.NOT_FOUND ? def : index;
        }

        @Nullable
        @Override
        public T getAsset(int index) {
            return index < 0 || index >= assets.length ? null : assets[index];
        }
    }

    /** A store with no event listeners, no file monitoring and nothing to load. */
    private static final class SeededStore<T extends JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, T>>>
            extends AssetStore<String, T, IndexedLookupTableAssetMap<String, T>> {

        private final IEventBus eventBus = new EventBus(false);

        SeededStore(@Nonnull Builder<T> builder) {
            super(builder);
        }

        @Nonnull
        @Override
        protected IEventBus getEventBus() {
            return eventBus;
        }

        @Override
        public void addFileMonitor(@Nonnull String packKey, Path path) {
        }

        @Override
        public void removeFileMonitor(Path path) {
        }

        @Override
        protected void handleRemoveOrUpdate(Set<String> toBeRemoved, Map<String, T> toBeUpdated,
                @Nonnull AssetUpdateQuery query) {
        }

        /** The engine's builder is a protected member of the store, so it is reached from inside one. */
        private static final class Builder<T extends JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, T>>>
                extends AssetStore.Builder<String, T, IndexedLookupTableAssetMap<String, T>, Builder<T>> {

            Builder(@Nonnull Class<T> assetClass, @Nonnull SeededMap<T> map) {
                super(String.class, assetClass, map);
                setPath("TestSeeded");
                setReplaceOnRemove(id -> null);
            }

            @Nonnull
            @Override
            public AssetStore<String, T, IndexedLookupTableAssetMap<String, T>> build() {
                return new SeededStore<>(this);
            }
        }
    }
}
