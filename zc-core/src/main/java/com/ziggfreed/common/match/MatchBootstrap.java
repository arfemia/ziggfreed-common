package com.ziggfreed.common.match;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.npc.AllNPCsLoadedEvent;

/**
 * Wires the engine's loads into the set readers' generations ({@link MatchSets}) at the library's {@code setup()}:
 * an item load or removal moves the item tag answers, an NPC group load or removal and a role load (the engine
 * rebuilds its group sets on all three) move the NPC group answers. The block family answers move with zc-world's
 * own fold, through the reader its {@code GatherBootstrap} fills.
 */
public final class MatchBootstrap {

    private MatchBootstrap() {
    }

    /** Register the generation listeners. Once, from the wiring root. */
    public static void install(@Nonnull PluginBase plugin) {
        EventRegistry events = plugin.getEventRegistry();
        events.register(LoadedAssetsEvent.class, Item.class,
                (LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> ev) -> MatchSets.itemsChanged());
        events.register(RemovedAssetsEvent.class, Item.class,
                (RemovedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> ev) -> MatchSets.itemsChanged());
        events.register(LoadedAssetsEvent.class, NPCGroup.class,
                (LoadedAssetsEvent<String, NPCGroup, IndexedLookupTableAssetMap<String, NPCGroup>> ev) ->
                        MatchSets.npcGroupsChanged());
        events.register(RemovedAssetsEvent.class, NPCGroup.class,
                (RemovedAssetsEvent<String, NPCGroup, IndexedLookupTableAssetMap<String, NPCGroup>> ev) ->
                        MatchSets.npcGroupsChanged());
        events.register(AllNPCsLoadedEvent.class, (AllNPCsLoadedEvent ev) -> MatchSets.npcGroupsChanged());
    }
}
