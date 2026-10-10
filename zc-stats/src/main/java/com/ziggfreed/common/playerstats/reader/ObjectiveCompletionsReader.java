package com.ziggfreed.common.playerstats.reader;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.components.ObjectiveHistoryComponent;
import com.hypixel.hytale.builtin.adventure.objectives.historydata.ObjectiveHistoryData;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.NativeReader;

/**
 * How many of the engine's own objectives a player has finished, read from the history the engine keeps
 * on the player ({@code ObjectiveHistoryComponent}, each objective's {@code TimesCompleted}) and never
 * counted again: the total over every objective, and one line per objective id. The library's quests are
 * its own record ({@code Quests_Completed}), not this one.
 *
 * <p>The library's manifest does not declare the engine's objectives plugin, so with it switched off
 * ({@code ObjectivePlugin.get()} is null) the reader answers nothing. World thread.
 */
public final class ObjectiveCompletionsReader implements NativeReader {

    /** The stat this reads, shipped as {@code Server/ZiggfreedCommon/Stats/Objectives_Completed.json}. */
    public static final String STAT_ID = "Objectives_Completed";

    @Override
    @Nullable
    public Reading read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ObjectivePlugin plugin = ObjectivePlugin.get();
        if (plugin == null || !ref.isValid()) {
            return null;
        }
        ComponentType<EntityStore, ObjectiveHistoryComponent> type = plugin.getObjectiveHistoryComponentType();
        ObjectiveHistoryComponent history = type == null ? null : store.getComponent(ref, type);
        Map<String, ObjectiveHistoryData> finished = history == null ? null : history.getObjectiveHistoryMap();
        if (finished == null) {
            return null;
        }
        long total = 0L;
        Map<String, Long> lines = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectiveHistoryData> entry : finished.entrySet()) {
            ObjectiveHistoryData data = entry.getValue();
            int times = data == null ? 0 : data.getTimesCompleted();
            if (times > 0 && entry.getKey() != null) {
                total += times;
                lines.put(entry.getKey(), (long) times);
            }
        }
        return new Reading(total, lines);
    }
}
