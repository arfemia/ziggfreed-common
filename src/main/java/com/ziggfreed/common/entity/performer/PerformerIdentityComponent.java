package com.ziggfreed.common.entity.performer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A registered ECS component every performer entity carries (both backends, for uniformity) binding
 * it back to its owning session: {@code OwnerUuid} (who spawned it), {@code StationKey} (the station
 * block key), {@code Kind} (which backend, for the reconcile sweep + telemetry), {@code SpawnedAtMs}
 * (age heuristics). Systems query performers natively over this component (see
 * {@link PerformerReconciler#sweep}) instead of tracking refs in per-session maps that go stale
 * across a restart.
 *
 * <p><b>Registration.</b> A library component has no plugin of its own that owns it, so a consumer
 * plugin registers it ONCE in {@code setup()} via {@link #register(ComponentRegistryProxy)} (which
 * sets {@link #TYPE}). Every attach/query site guards on {@code TYPE != null}, so a performer still
 * spawns/despawns cleanly even if a consumer never registers it - it just loses the reconcile
 * capability (the identity component is never attached, the sweep finds nothing).
 *
 * <p><b>Attach point.</b> {@code preAddToWorld} - the pre-commit {@code Holder}, before the entity
 * enters the store (no live-ref race). The bare-{@code Holder} backend attaches it via
 * {@code PlayerPuppetService.spawn}'s pre-add decorator; the NPC backend attaches it in
 * {@code NPCPlugin.spawnEntity}'s own {@code preAddToWorld} callback.
 *
 * <p><b>Serialization.</b> The component itself is serialized (its codec is registered); whether the
 * ENTITY persists is the SEPARATE {@code NonSerialized} marker the {@link PerformerLook#persist()}
 * knob controls. A transient (default) performer is already gone at boot, so the boot sweep is
 * belt-and-suspenders; a persistent performer's identity + boot sweep is the load-bearing orphan
 * control.
 */
public final class PerformerIdentityComponent implements Component<EntityStore> {

    /**
     * The registered type, or {@code null} until {@link #register} runs. Public so a consumer's own
     * systems can query it after registration.
     */
    @Nullable
    public static ComponentType<EntityStore, PerformerIdentityComponent> TYPE;

    /** The registration id (namespaced, stable). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:PerformerIdentity";

    @Nonnull
    public static final BuilderCodec<PerformerIdentityComponent> CODEC = BuilderCodec
            .builder(PerformerIdentityComponent.class, PerformerIdentityComponent::new)
            .append(new KeyedCodec<>("OwnerUuid", Codec.STRING),
                    (c, v) -> c.ownerUuid = v,
                    c -> c.ownerUuid).add()
            .append(new KeyedCodec<>("StationKey", Codec.STRING),
                    (c, v) -> c.stationKey = v,
                    c -> c.stationKey).add()
            .append(new KeyedCodec<>("Kind", Codec.STRING),
                    (c, v) -> c.kind = v,
                    c -> c.kind).add()
            .append(new KeyedCodec<>("SpawnedAtMs", Codec.LONG),
                    (c, v) -> c.spawnedAtMs = v == null ? 0L : v,
                    c -> Long.valueOf(c.spawnedAtMs)).add()
            .build();

    /** The owning session UUID, stored as a string ({@code null}/blank = no owner). */
    @Nullable
    public String ownerUuid;

    /** The station block key ({@code "<worldUuid>:<x>:<y>:<z>"}). */
    @Nullable
    public String stationKey;

    /** The backend {@link PerformerKind#code()}. */
    @Nullable
    public String kind;

    /** Epoch-millis the performer was spawned. */
    public long spawnedAtMs;

    public PerformerIdentityComponent() {
    }

    /**
     * Registers this component type on {@code registry} (a consumer plugin's
     * {@code getEntityStoreRegistry()}), setting {@link #TYPE}. Idempotent from the caller's view:
     * call ONCE at plugin {@code setup()}. Never throws - a registration failure logs and leaves
     * {@link #TYPE} unset (performers still work, just without reconcile).
     *
     * @return the registered type, or {@code null} on failure.
     */
    @Nullable
    public static ComponentType<EntityStore, PerformerIdentityComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(PerformerIdentityComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            try {
                ZiggfreedCommonPlugin.LOGGER.atWarning().withCause(t)
                        .log("[ziggfreed-common][performer] PerformerIdentityComponent register failed");
            } catch (Throwable ignored) {
                // log-manager-less unit JVM.
            }
            return null;
        }
    }

    /** The registered type, or {@code null} when not yet registered. */
    @Nullable
    public static ComponentType<EntityStore, PerformerIdentityComponent> getComponentType() {
        return TYPE;
    }

    /** Populates this component's fields from a {@link PerformerIdentity} value. */
    @Nonnull
    public PerformerIdentityComponent set(@Nonnull PerformerIdentity id) {
        this.ownerUuid = id.ownerUuid() != null ? id.ownerUuid().toString() : null;
        this.stationKey = id.stationKey();
        this.kind = id.kind().code();
        this.spawnedAtMs = id.spawnedAtMs();
        return this;
    }

    /** The pure {@link PerformerIdentity} snapshot of this component (the reconcile decision input). */
    @Nonnull
    public PerformerIdentity toIdentity() {
        UUID owner = null;
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            try {
                owner = UUID.fromString(ownerUuid);
            } catch (RuntimeException ignored) {
                owner = null;
            }
        }
        String key = stationKey != null ? stationKey : "";
        return new PerformerIdentity(owner, key, PerformerKind.fromCode(kind), spawnedAtMs);
    }

    /** A fresh component carrying {@code id}'s fields (the attach-site factory). */
    @Nonnull
    public static PerformerIdentityComponent of(@Nonnull PerformerIdentity id) {
        return new PerformerIdentityComponent().set(id);
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public PerformerIdentityComponent clone() {
        PerformerIdentityComponent c = new PerformerIdentityComponent();
        c.ownerUuid = this.ownerUuid;
        c.stationKey = this.stationKey;
        c.kind = this.kind;
        c.spawnedAtMs = this.spawnedAtMs;
        return c;
    }
}
