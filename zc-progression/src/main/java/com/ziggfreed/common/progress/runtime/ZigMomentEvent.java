package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.ZoneRef;

/**
 * One produced moment (a block broken, a mob killed, an item crafted, picked up or placed, a quest
 * collected, any kind a mod produces), announced on the shared engine event bus: the native face of
 * {@link MomentListener}, carrying exactly the {@link Moment} a listener is handed.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO, fired by the one reaction fan-out
 * ({@link ProgressionRuntime#momentListener()}) right AFTER every registered {@link MomentListener}
 * has reacted, for every moment any producer fires and for the reaction-only moments too (a hit dealt).
 * A moment re-dispatched under an alias reaches the engines alone and never this event, as it never
 * reaches a listener. Built only when somebody is listening ({@link MomentEvents}), so a server with
 * no listener pays one listener check per moment.
 *
 * <p>Fired on the world thread, inside the producing system's own dispatch: {@link #store()},
 * {@link #ref()} and {@link #commandBuffer()} are valid only synchronously during it, and a write
 * goes through the command buffer when there is one, exactly as a {@link MomentListener}'s does.
 * Nothing a listener does can refuse the moment or keep the engines from seeing it; this event is
 * not cancellable. Keep a listener to the reaction: anything slow slows every action in the game.
 *
 * <p>A listener filters on {@link #kindId()} (an objective kind such as {@code BREAK_BLOCK} or
 * {@code KILL_ENTITY}; compare it case-insensitively, as every kind id is matched) and reads the
 * rest off the moment.
 */
public final class ZigMomentEvent implements IEvent<Void> {

    private final Moment moment;

    public ZigMomentEvent(@Nonnull Moment moment) {
        this.moment = moment;
    }

    /** The whole moment, exactly as the registered listeners saw it. */
    @Nonnull
    public Moment moment() {
        return moment;
    }

    /** The objective kind, e.g. {@code BREAK_BLOCK}. */
    @Nonnull
    public String kindId() {
        return moment.kindId();
    }

    /** What it happened to, in that kind's own vocabulary (a block id, a role id, an item id). */
    @Nonnull
    public String target() {
        return moment.target();
    }

    /** The optional secondary filter, or null. */
    @Nullable
    public String qualifier() {
        return moment.qualifier();
    }

    /** The magnitude: 1 for a discrete action, the batch size for a craft. */
    public long amount() {
        return moment.amount();
    }

    /** Where the player was, or null when nothing was resolved. */
    @Nullable
    public ZoneRef zone() {
        return moment.zone();
    }

    /** The store the player lives in; valid only during dispatch. */
    @Nonnull
    public Store<EntityStore> store() {
        return moment.store();
    }

    /** The player this moment is credited to (a turret's kill names its owner); valid only during dispatch. */
    @Nonnull
    public Ref<EntityStore> ref() {
        return moment.ref();
    }

    /** The buffer the producing system was handed, or null for a producer that has none. */
    @Nullable
    public CommandBuffer<EntityStore> commandBuffer() {
        return moment.commandBuffer();
    }

    /** The producer's own record as {@code type}, or null when the moment carries none of that shape. */
    @Nullable
    public <T extends MomentPayload> T payload(@Nonnull Class<T> type) {
        return moment.payload(type);
    }
}
