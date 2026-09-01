package com.ziggfreed.common.npc.placement.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.placement.anchor.AnchorPosition;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stamp every placed NPC carries, binding it back to the placement that put it there.
 *
 * <p><b>This component is the DESPAWN authority.</b> A sweep over it answers "what is standing
 * that should not be", which is the only question that can be answered from a resident entity:
 * the placement was deleted, its gate now denies, or its {@code Where} no longer matches this
 * world. It deliberately does NOT answer "what is missing that should be there" - see
 * {@link NpcPlacementLedger} for that half, and for why the two cannot be one authority.
 *
 * <p><b>Registration.</b> A library component has no plugin of its own, so
 * {@code NpcBootstrap} registers it once at library {@code setup()} via
 * {@link #register(ComponentRegistryProxy)}. Every attach and query site guards on
 * {@code TYPE != null}, so a registration failure degrades the engine to "no reconcile" rather
 * than breaking spawning.
 *
 * <p><b>Attach point: the pre-add {@code Holder}.</b> The stamp goes on before the entity enters
 * the store, so there is no window in which a placed NPC exists without knowing what it is (and
 * no live-ref race in the attach itself).
 *
 * <p>The component is serialized, and a plugin-spawned NPC persists by default, so a placed NPC
 * carries its stamp across a restart. That is what lets a boot sweep tell a placed NPC apart from
 * every other NPC in the world.
 */
public final class PlacedNpcComponent implements Component<EntityStore> {

    /** The registered type, or {@code null} until {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, PlacedNpcComponent> TYPE;

    /** The registration id (namespaced, stable - it is persisted in every saved world). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:PlacedNpc";

    @Nonnull
    public static final BuilderCodec<PlacedNpcComponent> CODEC = BuilderCodec
            .builder(PlacedNpcComponent.class, PlacedNpcComponent::new)
            .append(new KeyedCodec<>("PlacementId", Codec.STRING),
                    (c, v) -> c.placementId = v, c -> c.placementId).add()
            .append(new KeyedCodec<>("Namespace", Codec.STRING),
                    (c, v) -> c.namespace = v, c -> c.namespace).add()
            .append(new KeyedCodec<>("MatchedWorld", Codec.STRING),
                    (c, v) -> c.matchedWorld = v, c -> c.matchedWorld).add()
            .append(new KeyedCodec<>("AnchorKey", Codec.STRING),
                    (c, v) -> c.anchorKey = v, c -> c.anchorKey).add()
            .append(new KeyedCodec<>("KeepAlive", Codec.BOOLEAN),
                    (c, v) -> c.keepAlive = v != null && v, c -> Boolean.valueOf(c.keepAlive)).add()
            .append(new KeyedCodec<>("SpawnedAtMs", Codec.LONG),
                    (c, v) -> c.spawnedAtMs = v == null ? 0L : v, c -> Long.valueOf(c.spawnedAtMs)).add()
            .build();

    /** The placement this NPC belongs to, lower-cased. */
    @Nullable
    public String placementId;

    /** The owning mod's namespace (diagnostics, per-mod listing). */
    @Nullable
    public String namespace;

    /** The name of the world the placement matched when this NPC was placed. */
    @Nullable
    public String matchedWorld;

    /** The anchor instance this NPC occupies ({@link AnchorPosition#anchorKey()}). */
    @Nullable
    public String anchorKey;

    /** Whether this NPC's chunk was pinned at placement. */
    public boolean keepAlive;

    /** Epoch millis at placement. */
    public long spawnedAtMs;

    public PlacedNpcComponent() {
    }

    /**
     * Register this component type on {@code registry}. Call ONCE at plugin {@code setup()}.
     * Never throws: a failure logs and leaves {@link #TYPE} unset.
     *
     * @return the registered type, or {@code null} on failure
     */
    @Nullable
    public static ComponentType<EntityStore, PlacedNpcComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(PlacedNpcComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[placement] PlacedNpcComponent register failed", t);
            return null;
        }
    }

    /** The registered type, or {@code null} when not yet registered. */
    @Nullable
    public static ComponentType<EntityStore, PlacedNpcComponent> getComponentType() {
        return TYPE;
    }

    /** Populate this component from a {@link PlacedNpcIdentity}. */
    @Nonnull
    public PlacedNpcComponent set(@Nonnull PlacedNpcIdentity id) {
        this.placementId = id.placementId();
        this.namespace = id.namespace();
        this.matchedWorld = id.matchedWorld();
        this.anchorKey = id.anchorKey();
        this.keepAlive = id.keepAlive();
        this.spawnedAtMs = id.spawnedAtMs();
        return this;
    }

    /** A fresh component carrying {@code id}'s fields (the attach-site factory). */
    @Nonnull
    public static PlacedNpcComponent of(@Nonnull PlacedNpcIdentity id) {
        return new PlacedNpcComponent().set(id);
    }

    /** The pure snapshot the reconcile policy runs on. */
    @Nonnull
    public PlacedNpcIdentity toIdentity() {
        return PlacedNpcIdentity.of(placementId, namespace, matchedWorld, anchorKey, keepAlive, spawnedAtMs);
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public PlacedNpcComponent clone() {
        PlacedNpcComponent c = new PlacedNpcComponent();
        c.placementId = this.placementId;
        c.namespace = this.namespace;
        c.matchedWorld = this.matchedWorld;
        c.anchorKey = this.anchorKey;
        c.keepAlive = this.keepAlive;
        c.spawnedAtMs = this.spawnedAtMs;
        return c;
    }
}
