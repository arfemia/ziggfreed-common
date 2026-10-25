package com.ziggfreed.common.instance.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The mark on a spoils chest, the prop a won fight leaves where its boss fell ({@link SpoilsChests}): the fight whose
 * Claims it offers, and when it goes.
 *
 * <p><b>Saved with the chunk, on purpose.</b> A chest whose section falls asleep (nobody near it) is parked with its
 * section and comes back with this mark when the section wakes, so it still stands for a party that walked off and
 * came back. A chest a restart or a closed world left on disk comes back with it too, and the mark is how it is
 * found on that load and removed ({@link SpoilsChestSystems.Sweep}): the chests of this session are known by their
 * fight and their entity's uuid, and nothing is known after a restart. The component id is a persisted format: never
 * rename it.
 */
public final class SpoilsChest implements Component<EntityStore> {

    /** The registered type; null when registration failed, and every reader guards on that. */
    @Nullable
    public static ComponentType<EntityStore, SpoilsChest> TYPE;

    /** The registration id (namespaced, stable). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:SpoilsChest";

    /** The save's {@code Version} leaf: a save from before it reads as version 0, unchanged (zc-core's {@code codec/} router). */
    public static final int VERSION = 1;

    @Nonnull
    public static final BuilderCodec<SpoilsChest> CODEC = BuilderCodec
            .builder(SpoilsChest.class, SpoilsChest::new)
            .versioned()
            .codecVersion(VERSION)
            .append(new KeyedCodec<>("Fight", Codec.STRING), (c, v) -> c.fight = v, c -> c.fight)
            .documentation("The fight whose Claims this chest offers: the boss fight's run id.")
            .add()
            .append(new KeyedCodec<>("ExpiresAt", Codec.LONG), (c, v) -> c.expiresAt = v == null ? 0L : v,
                    c -> c.expiresAt)
            .documentation("When the chest goes at the latest, epoch milliseconds.")
            .add()
            .build();

    @Nullable private String fight;
    private long expiresAt;
    /** When the upkeep next looks at this chest; never saved. */
    private transient long nextCheckMs;

    public SpoilsChest() {
    }

    /** The mark of a chest offering {@code fight}'s Claims until {@code expiresAt}. */
    @Nonnull
    public static SpoilsChest of(@Nonnull String fight, long expiresAt) {
        SpoilsChest mark = new SpoilsChest();
        mark.fight = fight;
        mark.expiresAt = expiresAt;
        return mark;
    }

    /**
     * Register the component type, codec-backed so a chunk save carries it. Called once at setup, before any world
     * loads. Never throws: a failure logs and leaves {@link #TYPE} unset, and every reader guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, SpoilsChest> register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(SpoilsChest.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "could not register the spoils chest's mark", t);
            return null;
        }
    }

    /** The fight whose Claims this chest offers, or null for a mark too damaged to read. */
    @Nullable
    public String fight() {
        return fight == null || fight.isBlank() ? null : fight.trim();
    }

    /** When the chest goes at the latest, epoch milliseconds. */
    public long expiresAt() {
        return expiresAt;
    }

    /** True once {@code nowMs} reaches the next look, which is then set {@code everyMs} ahead. */
    boolean due(long nowMs, long everyMs) {
        if (nowMs < nextCheckMs) {
            return false;
        }
        nextCheckMs = nowMs + everyMs;
        return true;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        SpoilsChest copy = of(fight == null ? "" : fight, expiresAt);
        copy.fight = fight;
        return copy;
    }
}
