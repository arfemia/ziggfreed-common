package com.ziggfreed.common.world.record;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.Short2ObjectMapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;

/**
 * A generic per-block record store on a chunk section: one payload value of the caller's own codec
 * type per block position, kept on the section itself and saved and loaded with the chunk. The
 * keyed, sparse counterpart to {@code world.placed.PlacedBlockSection}'s one-bit-per-block array,
 * for a record too rich for a bit - laid out like the engine's own per-block section data (a map
 * keyed by the block's section-local {@link ChunkUtil#indexBlock} index, the
 * {@code BlockComponentSection} {@code "Blocks"} shape).
 *
 * <p><b>Register once, act through the {@link Handle}.</b> {@link #register} builds the component
 * codec around the caller's payload codec and registers it on the chunk-store registry under the
 * caller's registry id - call it once at plugin setup, BEFORE any world loads. It never throws: a
 * failed registration logs and hands back a handle whose every read answers "nothing was ever
 * stored" and whose every write does nothing. The same class may back any number of registrations,
 * each under its own id with its own payload type.
 *
 * <p><b>An empty section costs almost nothing.</b> The record map is allocated lazily on the first
 * write and released again when the last record is removed; with no records the codec skips its one
 * key entirely, so an empty section serializes to the bare version marker.
 *
 * <p><b>A mutation must be followed by {@link Handle#markDirty}.</b> The engine saves a chunk
 * section only when the section's own {@code ChunkSection} component is flagged as needing a save;
 * nothing watches a plugin component for changes, and unlike a block place or break, a record write
 * has no accompanying engine block change to raise that flag for it. {@code ensureAndGet} (when it
 * creates) and {@code remove} (when it removes) mark the section themselves; a caller that mutates
 * a fetched record IN PLACE calls {@code markDirty} once when done, or the change survives only
 * until the section's next unload.
 *
 * <p><b>The save snapshot is a deep copy.</b> The engine snapshots a section's components on the
 * world thread ({@link #clone}) and serializes the snapshot on an IO thread, so this component's
 * clone copies every record through the payload codec: a record mutated mid-save can never tear
 * the bytes being written. The payload codec must therefore round-trip its own values, which is the
 * same contract persistence itself demands of it.
 *
 * <p>Must only be touched on the owning world's thread, like every other chunk-store component.
 */
public final class BlockRecordSection<T> implements Component<ChunkStore> {

    /** The codec version, so a later change to the layout can tell an older section apart. */
    public static final int VERSION = 0;

    /** The payload codec, kept per instance so {@link #clone} can deep-copy records through it. */
    @Nonnull
    private final BuilderCodec<T> payloadCodec;

    /** Null until this section holds its first record; back to null when it holds none again. */
    @Nullable
    private Short2ObjectMap<T> records;

    private BlockRecordSection(@Nonnull BuilderCodec<T> payloadCodec) {
        this.payloadCodec = payloadCodec;
    }

    /**
     * Register a per-block record component under {@code registryId}, carrying {@code payloadCodec}
     * values. Call once at plugin setup, BEFORE any world loads. Never throws: a failure logs and
     * the returned handle degrades to "nothing was ever stored".
     */
    @Nonnull
    public static <T> Handle<T> register(@Nonnull ComponentRegistryProxy<ChunkStore> registry,
            @Nonnull String registryId, @Nonnull BuilderCodec<T> payloadCodec) {
        ComponentType<ChunkStore, BlockRecordSection<T>> type = null;
        try {
            @SuppressWarnings("unchecked")
            Class<? super BlockRecordSection<T>> componentClass =
                    (Class<? super BlockRecordSection<T>>) (Class<?>) BlockRecordSection.class;
            type = registry.registerComponent(componentClass, registryId, buildCodec(payloadCodec));
        } catch (Throwable t) {
            SafeLog.warn("[record] the '" + registryId + "' chunk record component could not be"
                    + " registered: nothing will be stored or read back this boot", t);
        }
        return new Handle<>(registryId, type, payloadCodec);
    }

    /**
     * The component codec around one payload codec: the record map behind a single optional
     * {@code "Records"} key, keyed by section-local block index. {@link #register} builds it for
     * the registry; it is public so a test or an offline reader can round-trip a section without a
     * component registry.
     */
    @Nonnull
    public static <T> BuilderCodec<BlockRecordSection<T>> buildCodec(@Nonnull BuilderCodec<T> payloadCodec) {
        @SuppressWarnings("unchecked")
        Class<BlockRecordSection<T>> componentClass =
                (Class<BlockRecordSection<T>>) (Class<?>) BlockRecordSection.class;
        return BuilderCodec.builder(componentClass, () -> new BlockRecordSection<>(payloadCodec))
                .versioned()
                .codecVersion(VERSION)
                .append(new KeyedCodec<>("Records",
                                new Short2ObjectMapCodec<>(payloadCodec, Short2ObjectOpenHashMap::new, false), false),
                        (section, map) -> section.records = (map == null || map.isEmpty()) ? null : map,
                        section -> section.records)
                .documentation("Per-block records, keyed by the block's section-local index; absent when the section holds none.")
                .add()
                .build();
    }

    // ==================== per-section reads and writes ====================

    /** The record at this section-local index, or null. */
    @Nullable
    public T get(int index) {
        Short2ObjectMap<T> data = records;
        return data != null ? data.get((short) index) : null;
    }

    /** Store (or replace) the record at this section-local index. */
    public void put(int index, @Nonnull T record) {
        Short2ObjectMap<T> data = records;
        if (data == null) {
            data = new Short2ObjectOpenHashMap<>(4);
            records = data;
        }
        data.put((short) index, record);
    }

    /**
     * Remove the record at this section-local index.
     *
     * @return true when a record was actually removed
     */
    public boolean removeRecord(int index) {
        Short2ObjectMap<T> data = records;
        if (data == null) {
            return false;
        }
        boolean removed = data.remove((short) index) != null;
        if (removed && data.isEmpty()) {
            // The last record in this section is gone; carrying an empty map helps nobody.
            records = null;
        }
        return removed;
    }

    /** How many blocks in this section carry a record. */
    public int recordCount() {
        Short2ObjectMap<T> data = records;
        return data != null ? data.size() : 0;
    }

    /** Visit every record in this section, in no defined order. */
    public void forEachRecord(@Nonnull Visitor<T> visitor) {
        Short2ObjectMap<T> data = records;
        if (data == null) {
            return;
        }
        for (Short2ObjectMap.Entry<T> entry : data.short2ObjectEntrySet()) {
            int index = entry.getShortKey() & 0xFFFF;
            visitor.visit(ChunkUtil.xFromIndex(index), ChunkUtil.yFromIndex(index),
                    ChunkUtil.zFromIndex(index), entry.getValue());
        }
    }

    // ==================== component plumbing ====================

    /**
     * The save-snapshot copy: every record deep-copied through the payload codec, so a record the
     * world thread mutates after the snapshot can never tear the bytes an IO thread is writing. A
     * record its own codec cannot round-trip would fail the save's serialization anyway; if the
     * copy fails, the clone falls back to sharing the live map so the save still carries the data.
     */
    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        BlockRecordSection<T> copy = new BlockRecordSection<>(payloadCodec);
        Short2ObjectMap<T> data = records;
        if (data == null || data.isEmpty()) {
            return copy;
        }
        try {
            Short2ObjectMap<T> map = new Short2ObjectOpenHashMap<>(data.size());
            ExtraInfo info = new ExtraInfo();
            for (Short2ObjectMap.Entry<T> entry : data.short2ObjectEntrySet()) {
                map.put(entry.getShortKey(), payloadCodec.decode(payloadCodec.encode(entry.getValue(), info), info));
            }
            copy.records = map;
        } catch (Throwable t) {
            SafeLog.warn("[record] a section's records could not be deep-copied for a save snapshot;"
                    + " sharing the live records instead", t);
            copy.records = data;
        }
        return copy;
    }

    /** One record visited: the block's SECTION-LOCAL coordinates plus its payload. */
    @FunctionalInterface
    public interface Visitor<T> {
        void visit(int localX, int localY, int localZ, @Nonnull T record);
    }

    // ==================== the position-facing handle every caller uses ====================

    /**
     * The typed access surface one registration hands back: position-facing accessors over one
     * registered record component. All world-thread only. Every method degrades to null / false /
     * no-op when registration failed or the section ref is not valid.
     */
    public static final class Handle<T> {

        @Nonnull
        private final String registryId;

        @Nullable
        private final ComponentType<ChunkStore, BlockRecordSection<T>> type;

        @Nonnull
        private final BuilderCodec<T> payloadCodec;

        private Handle(@Nonnull String registryId,
                @Nullable ComponentType<ChunkStore, BlockRecordSection<T>> type,
                @Nonnull BuilderCodec<T> payloadCodec) {
            this.registryId = registryId;
            this.type = type;
            this.payloadCodec = payloadCodec;
        }

        /** The id the component registered under; also its key in a saved chunk. */
        @Nonnull
        public String registryId() {
            return registryId;
        }

        /** Whether registration took; when false, every accessor answers "nothing was ever stored". */
        public boolean isRegistered() {
            return type != null;
        }

        /** The record at this position, or null. The caller who mutates it calls {@link #markDirty}. */
        @Nullable
        public T get(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
            ComponentType<ChunkStore, BlockRecordSection<T>> componentType = type;
            if (componentType == null || sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockRecordSection<T> section = accessor.getComponent(sectionRef, componentType);
            return section != null ? section.get(ChunkUtil.indexBlock(x, y, z)) : null;
        }

        /**
         * The record at this position, minted from the payload codec's defaults when absent (the
         * mint marks the section dirty; a caller's own later mutation still calls
         * {@link #markDirty}). Null only when registration failed or the ref is not valid.
         */
        @Nullable
        public T ensureAndGet(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
            ComponentType<ChunkStore, BlockRecordSection<T>> componentType = type;
            if (componentType == null || sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockRecordSection<T> section = accessor.getComponent(sectionRef, componentType);
            if (section == null) {
                section = accessor.ensureAndGetComponent(sectionRef, componentType);
            }
            int index = ChunkUtil.indexBlock(x, y, z);
            T record = section.get(index);
            if (record == null) {
                record = payloadCodec.getDefaultValue();
                section.put(index, record);
                markDirty(accessor, sectionRef);
            }
            return record;
        }

        /**
         * Remove the record at this position (marking the section dirty when something was there).
         *
         * @return true when a record was actually removed
         */
        public boolean remove(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
            ComponentType<ChunkStore, BlockRecordSection<T>> componentType = type;
            if (componentType == null || sectionRef == null || !sectionRef.isValid()) {
                return false;
            }
            BlockRecordSection<T> section = accessor.getComponent(sectionRef, componentType);
            if (section == null) {
                return false;
            }
            boolean removed = section.removeRecord(ChunkUtil.indexBlock(x, y, z));
            if (removed) {
                markDirty(accessor, sectionRef);
            }
            return removed;
        }

        /** Visit every record in this section (section-local coordinates), in no defined order. */
        public void forEach(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef, @Nonnull Visitor<T> visitor) {
            ComponentType<ChunkStore, BlockRecordSection<T>> componentType = type;
            if (componentType == null || sectionRef == null || !sectionRef.isValid()) {
                return;
            }
            BlockRecordSection<T> section = accessor.getComponent(sectionRef, componentType);
            if (section != null) {
                section.forEachRecord(visitor);
            }
        }

        /** How many blocks in this section carry a record. */
        public int count(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef) {
            ComponentType<ChunkStore, BlockRecordSection<T>> componentType = type;
            if (componentType == null || sectionRef == null || !sectionRef.isValid()) {
                return 0;
            }
            BlockRecordSection<T> section = accessor.getComponent(sectionRef, componentType);
            return section != null ? section.recordCount() : 0;
        }

        /**
         * Flag the owning section as needing a save. Call once after mutating a fetched record in
         * place; without it the engine has no reason to write the section out, and the mutation
         * survives only until the section's next unload.
         */
        public void markDirty(@Nonnull ComponentAccessor<ChunkStore> accessor,
                @Nonnull Ref<ChunkStore> sectionRef) {
            if (sectionRef == null || !sectionRef.isValid()) {
                return;
            }
            ChunkSection section = accessor.getComponent(sectionRef, ChunkSection.getComponentType());
            if (section != null) {
                section.markNeedsSaving();
            }
        }
    }
}
