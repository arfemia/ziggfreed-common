package com.ziggfreed.common.playerstats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.NativeReader;
import com.ziggfreed.common.counter.NativeReaders;
import com.ziggfreed.common.playerstats.asset.StatAsset;
import com.ziggfreed.common.util.SafeLog;

/**
 * The store's side of the native readers (zc-core's {@link NativeReaders}): which stats are read rather
 * than counted, and what each reads for one player. A reader reads only while a stat file of its id says
 * {@code Kind: Native}, so the file, shipped by whoever registers the reader, is what puts it on the board;
 * a reader with no such file, or a file with no reader, reads nothing. An answer is filed under the file's
 * own spelling of the id, the total as the id and each line as {@code <id>/<thing>} ({@link StatKeys}),
 * and is ranked, never kept in the player's record.
 */
public final class NativeStats {

    /** The stat ids whose reader has thrown, so the log says each once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private NativeStats() {
    }

    /**
     * The reader of {@code stat} when its file says {@code Kind: Native} and some module registered one for
     * its id; null otherwise.
     */
    @Nullable
    public static NativeReader readerOf(@Nullable StatAsset stat) {
        if (stat == null || stat.getId() == null || stat.kind() != StatKind.NATIVE) {
            return null;
        }
        return NativeReaders.get(stat.getId());
    }

    /**
     * Every native stat's reading for the player at {@code ref}, keyed as the board ranks it; empty when no
     * stat is native or nothing could be read. A reader that throws costs only its own stat. World thread.
     */
    @Nonnull
    static Map<String, Long> read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull StatIndex index) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String id : NativeReaders.ids()) {
            StatAsset stat = index.stat(id);
            NativeReader reader = readerOf(stat);
            if (reader == null) {
                continue;
            }
            NativeReader.Reading reading;
            try {
                reading = reader.read(store, ref);
            } catch (Throwable t) {
                NativeReaders.recordFailure(id, t.getMessage());
                if (WARNED.add(id)) {
                    SafeLog.warn("[stats] the native reader for '" + stat.getId() + "' failed, so it ranks nothing"
                            + " for now: " + t.getMessage());
                }
                continue;
            }
            if (reading != null) {
                file(stat.getId(), reading, out);
            }
        }
        return out;
    }

    private static void file(@Nonnull String statId, @Nonnull NativeReader.Reading reading,
            @Nonnull Map<String, Long> out) {
        if (reading.total() != null) {
            out.put(StatKeys.total(statId), reading.total());
        }
        for (Map.Entry<String, Long> line : reading.lines().entrySet()) {
            if (line.getValue() != null && StatKeys.usableThing(line.getKey())) {
                out.put(StatKeys.line(statId, line.getKey()), line.getValue());
            }
        }
    }
}
