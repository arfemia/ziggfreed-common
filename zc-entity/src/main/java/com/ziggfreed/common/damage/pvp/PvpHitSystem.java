package com.ziggfreed.common.damage.pvp;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.onhit.CritSystem;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.common.stats.damage.NativeOutgoing;
import com.ziggfreed.common.util.SafeLog;

/**
 * The PvP hit step, in the Filter damage group after the crit step ({@link CritSystem}), so a cut takes the whole hit,
 * a crit and a weak spot included, and a later flat lands at the cut too ({@link NativeOutgoing#scaleHit}): a hit one
 * player lands on another (a blow, a shot, a damage over time they put on) is cut by the victim's
 * {@value PvpHits#RESIST}, and its knockback by their {@value PvpHits#KNOCKBACK_RESIST}, each held at its file's cap
 * ({@link PvpHits}). The engine has already said whether the hit may land at all (its PvP switch and spawn protection,
 * a safe zone): a hit it refused arrives cancelled and is left alone. A hit on a creature, a fall or fire never reads a
 * PvP stat. A disarmed attacker's hit never gets this far: {@link Disarmed} cancels it in the Gather group, before any
 * step rolls a crit or fires a moment for it.
 */
public final class PvpHitSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, CritSystem.class));

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled() || !(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> victim = chunk.getReferenceTo(index);
            if (PvpHits.playerAttacker(store, damage, victim) == null || !(damage.getAmount() > 0f)) {
                return;
            }
            double through = PvpHits.damageThrough(store, victim);
            if (through < 1.0) {
                damage.setAmount((float) (damage.getAmount() * through));
                NativeOutgoing.scaleHit(damage, through);
            }
            if (source.getSourceType() != DamageSourceType.DoT) {
                double knockback = PvpHits.knockbackThrough(store, victim);
                if (knockback < 1.0) {
                    scaleKnockback(damage, knockback);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[pvp] the PvP hit step failed: " + t.getMessage());
        }
    }

    /**
     * Multiply the knockback the hit carries by {@code factor}, the engine's own way (a modifier on the hit's knockback
     * component, applied as it pushes, as worn armor's and a guard's cuts are). A hit with no knockback is left alone.
     */
    private static void scaleKnockback(@Nonnull Damage damage, double factor) {
        KnockbackComponent knockback = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);
        if (knockback != null) {
            knockback.addModifier(Math.max(0.0, factor));
        }
    }

    /**
     * In the Gather damage group, before any Filter step (a crit's roll and its word, a weak spot, a proc's moment):
     * a hit whose source wears a disarm ({@link Disarm}: a blow, a shot, a thrown bomb, a deployable's damage, a damage
     * over time they put on) is cancelled and its knockback taken to nothing, whoever it lands on but the wearer.
     */
    public static final class Disarmed extends DamageEventSystem {

        @Nonnull
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getGatherDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                if (damage.isCancelled() || !(damage.getSource() instanceof Damage.EntitySource source)) {
                    return;
                }
                Ref<EntityStore> attacker = source.getRef();
                if (attacker == null || !attacker.isValid() || Nearby.same(attacker, chunk.getReferenceTo(index))
                        || !Disarm.isDisarmed(store, attacker)) {
                    return;
                }
                damage.setCancelled(true);
                scaleKnockback(damage, 0.0);
            } catch (Throwable t) {
                SafeLog.warn("[pvp] the disarm step failed: " + t.getMessage());
            }
        }
    }

    /**
     * In the Gather damage group, before any step reads the hit: a hit one player deals another notes its victim's id
     * on the hit ({@link PvpHits#victimOf}), so a hit-aware condition asked later (the bound grants' hit term, a proc)
     * knows whom it lands on. Only on a player's hit on a player; every other hit is left as it is.
     */
    public static final class Stamp extends DamageEventSystem {

        @Nonnull
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getGatherDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                if (damage.isCancelled() || PvpHits.playerAttacker(store, damage, victim) == null) {
                    return;
                }
                PlayerRef player = store.getComponent(victim, PlayerRef.getComponentType());
                if (player != null && player.getUuid() != null) {
                    damage.putMetaObject(PvpHits.Meta.VICTIM_ID, player.getUuid());
                }
            } catch (Throwable t) {
                SafeLog.fine("[pvp] a player's hit could not note its victim: " + t.getMessage());
            }
        }
    }
}
