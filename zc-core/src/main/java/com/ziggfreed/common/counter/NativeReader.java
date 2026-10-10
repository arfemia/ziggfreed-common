package com.ziggfreed.common.counter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A number the engine already records for a player, read when asked and never counted a second time:
 * how many of the engine's own objectives they finished, their standing with each faction. A reader is
 * registered under the id of the tally it feeds ({@link NativeReaders}); whoever ranks or shows tallies
 * asks it for a live player and files its answer under that id, the total as the id itself and each line
 * as {@code <id>/<thing>} ({@link Counters#key}).
 *
 * <p>A reader sits with the one module allowed to put that question to the engine (zc-reputation's engine
 * door for standing, say), so the same number is never read twice by two modules. It is called on the
 * world thread that owns {@code store}.
 */
public interface NativeReader {

    /**
     * What the engine records for one player: the grand total (null when a total means nothing, as a sum
     * of standings with unrelated factions would) and one line per thing, in the order the reader gives.
     */
    record Reading(@Nullable Long total, @Nonnull Map<String, Long> lines) {

        public Reading {
            lines = lines == null || lines.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        }
    }

    /**
     * What the engine records for the player at {@code ref}, or null when it cannot read it now (its plugin
     * is off, no player is there). World thread.
     */
    @Nullable
    Reading read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);

    /**
     * Every thing this reader can name a line for, in the order they are shown side by side (the server's
     * factions, say), so a view can give each its own column without knowing them in advance. Empty, the
     * default, for a reader whose lines are not each worth a column.
     */
    @Nonnull
    default List<String> things() {
        return List.of();
    }

    /**
     * The translation key naming {@code thing}'s line, as authored (the caller resolves it), or null, the
     * default, to name the line by the tally's own name.
     */
    @Nullable
    default String thingNameKey(@Nonnull String thing) {
        return null;
    }
}
