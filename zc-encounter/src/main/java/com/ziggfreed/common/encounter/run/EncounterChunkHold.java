package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkFlag;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Keeps an OPEN fight's chunk ticking: while a run is engaged and not yet settled, the tick resets
 * the encounter chunk's active timer, the very lever the engine pulls for a chunk a player stands
 * in, so the fight keeps running long enough for the binding's own guards to decide it. Without it
 * a chunk nobody is standing in stops ticking within seconds of going cold, the encounter entity is
 * unloaded with it, and the run ends as a world unload before {@code WipeGraceSeconds} could let a
 * player run back or {@code MaxRunSeconds} could time it out.
 *
 * <p>The hold is the open window, and for an OWNED run (one a consumer spawned with an owner key,
 * a round standing its boss up at an arena the party has not reached yet) the wait before it as
 * well: an owned run's owner, difficulty, party and multiplier live only on the run, which no
 * chunk save carries, so an owned encounter unloaded before its party arrived would come back as
 * a fresh, unowned run. A settled run (defeated or wiped) lets go either way, so a script's
 * re-arm wait, or a boss standing in a chunk everyone left, follows the engine's ordinary cold
 * schedule: the chunk unloads, the entity is stored with it, and the script starts over from its
 * start state the next time the chunk loads. An unowned run that has not engaged (a placed world
 * boss, a console spawn) is never held. Nothing is pinned loaded, and a chunk that is not ticking
 * any more is simply not held.
 *
 * <p>World thread only; one map lookup and one component read per call; never throws.
 */
public final class EncounterChunkHold {

    private EncounterChunkHold() {
    }

    /** Reset the active timer of the chunk under {@code encounterRef}; answers whether it was ticking. */
    public static boolean holdTicking(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> encounterRef) {
        try {
            TransformComponent at = store.getComponent(encounterRef, TransformComponent.getComponentType());
            if (at == null) {
                return false;
            }
            World world = store.getExternalData().getWorld();
            ChunkStore chunks = world.getChunkStore();
            long index = ChunkUtil.indexChunkFromBlock(at.getPosition().x, at.getPosition().z);
            Ref<ChunkStore> chunkRef = chunks.getChunkReference(index);
            if (chunkRef == null || !chunkRef.isValid()) {
                return false;
            }
            WorldChunk chunk = chunks.getStore().getComponent(chunkRef, WorldChunk.getComponentType());
            if (chunk == null || !chunk.is(ChunkFlag.TICKING)) {
                return false;
            }
            chunk.resetActiveTimer();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
