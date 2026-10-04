package com.ziggfreed.common.calendar.spawn;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.spawning.assets.spawns.config.WorldNPCSpawn;
import com.ziggfreed.common.asset.AssetStoreWriter;
import com.ziggfreed.common.util.SafeLog;

/**
 * The production sink: the engine's world-spawn store, written through {@link AssetStoreWriter} on its
 * writer thread (the tick thread never waits on the asset lock). The engine decodes the body with its own
 * codec and rebuilds the rule in place. A rule id another file already ships is refused, so an event can
 * never rewrite (and later retire) a rule it does not own.
 */
final class WorldSpawnRuleSink implements CalendarSpawns.RuleSink {

    /** The asset-pack key the calendar's rules are written under. */
    static final String PACK_KEY = "ziggfreedcommon-calendar";

    /** Rule ids this sink has written this session; any other id already in the store belongs to somebody else. */
    private final Set<String> owned = ConcurrentHashMap.newKeySet();

    /** Rule ids already refused, so a clash the reconciler asks about every tick warns once. */
    private final Set<String> refused = ConcurrentHashMap.newKeySet();

    @Override
    public boolean write(@Nonnull String ruleId, @Nonnull String json) {
        AssetStore<String, WorldNPCSpawn, IndexedLookupTableAssetMap<String, WorldNPCSpawn>> store =
                AssetRegistry.getAssetStore(WorldNPCSpawn.class);
        if (!owned.contains(ruleId) && store.getAssetMap().getAsset(ruleId) != null) {
            if (refused.add(ruleId)) {
                SafeLog.warn("[calendar] a calendar spawn names the rule '" + ruleId + "', which another file"
                        + " already ships, so the calendar leaves it alone; give the calendar spawn a Rule id of"
                        + " its own");
            }
            return false;
        }
        owned.add(ruleId);
        RawAsset<String> raw = new RawAsset<>(null, ruleId, null, 0, json.toCharArray(), null,
                ContainedAssetCodec.Mode.NONE);
        String what = "calendar spawn rule " + ruleId;
        AssetStoreWriter.offThread(what, () -> AssetStoreWriter.writeBuffers(what, store, PACK_KEY, List.of(raw)));
        return true;
    }
}
