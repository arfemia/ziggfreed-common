package com.ziggfreed.common.cast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A cast's hit was applied to one target through the shared hit routine ({@link HitResolver#applyHit}):
 * the native face of the on-hit hooks, carrying the {@link HitContext} a {@link HitAction} is handed
 * plus the engine damage source and cause the hit was dispatched with.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired once per target hit,
 * on the world thread, right after the hit's damage dispatch completed and its on-hit hook ran (a hit
 * whose damage dispatch threw fires nothing). Built only when somebody is listening ({@link CastEvents}),
 * so a server with no listener pays one listener check per hit.
 *
 * <p><b>Dispatched, not landed.</b> The damage went into the engine's damage pipeline, which may still
 * reduce or cancel it (armor, an invulnerable target); a mod that needs what landed listens to the
 * engine's own damage events. Not cancellable: the hit has already been applied.
 *
 * <p>Everything here is valid only synchronously during dispatch. The context's accessor is the store
 * the hit was applied against: read through it freely, but a structural write inside a tick must be
 * deferred. The attacker is {@link #attackerRef()}: the damage source's entity, else the context's own
 * source.
 */
public final class ZigCastHitEvent implements IEvent<Void> {

    private final HitContext context;
    @Nullable private final Damage.Source damageSource;
    @Nullable private final DamageCause damageCause;

    public ZigCastHitEvent(@Nonnull HitContext context, @Nullable Damage.Source damageSource,
                           @Nullable DamageCause damageCause) {
        this.context = context;
        this.damageSource = damageSource;
        this.damageCause = damageCause;
    }

    /** What an on-hit hook is handed: the accessor, the target, the amount, the position, the cause label. */
    @Nonnull
    public HitContext context() {
        return context;
    }

    /** The entity that was hit. */
    @Nonnull
    public Ref<EntityStore> target() {
        return context.target();
    }

    /** The damage amount the hit dispatched, before the engine's pipeline had its say. */
    public double damageAmount() {
        return context.damageAmount();
    }

    /** The engine damage source the hit was dispatched with, or null when the caller set none. */
    @Nullable
    public Damage.Source damageSource() {
        return damageSource;
    }

    /** The engine damage cause the hit was dispatched with, or null when the caller set none. */
    @Nullable
    public DamageCause damageCause() {
        return damageCause;
    }

    /** Who dealt it: the damage source's entity when it has one, else the context's source; null when neither. */
    @Nullable
    public Ref<EntityStore> attackerRef() {
        if (damageSource instanceof Damage.EntitySource entitySource) {
            return entitySource.getRef();
        }
        return context.source();
    }
}
