package com.ziggfreed.common.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.testing.EngineAssetStores;

/**
 * A second apply of an infinite effect the entity already wears sends its viewers nothing. The engine's
 * {@code addInfiniteEffect} queues an Add on every call, and a client meeting an Add for an effect it
 * already shows starts a second set of its particles without stopping the first, so a mob whose aura was
 * applied again after players saw it dragged a trail of them. It has bitten twice.
 *
 * <p>The worn aura is seeded through the controller's own load path ({@code addActiveEntityEffects}), as
 * a saved mob comes back with a chunk, over a real {@code EffectControllerComponent}. A timed effect is
 * left to the engine, which refreshes or extends it: the engine's add itself cannot run in a unit JVM
 * (its first check, {@code LivingEntityEffectSystem}, builds a query off the running entity module at
 * class init), so that half is pinned at the decision and its refresh is the smoke's.
 *
 * <p>Tagged {@code engine-items}: the effect store swap builds the engine's {@code AssetStore}, which
 * loads only under the engine's own log manager.
 */
@Tag("engine-items")
class EffectAddsTest {

    private static final String AURA_ID = "Test_Effect_Adds_Aura";
    private static final String TIMED_ID = "Test_Effect_Adds_Timed";
    private static final int AURA = 0;
    private static final int TIMED = 1;

    /** The repeat of a worn infinite effect reads neither the entity nor the store. */
    private static final Ref<EntityStore> NO_REF = null;
    private static final ComponentAccessor<EntityStore> NO_ACCESSOR = null;

    /** An effect as its JSON authors it: the engine's own fields, set the way its codec sets them. */
    private static final class Authored extends EntityEffect {
        Authored(@Nonnull String id, boolean infinite, float duration, @Nonnull OverlapBehavior overlap) {
            super(id);
            this.infinite = infinite;
            this.duration = duration;
            this.overlapBehavior = overlap;
        }
    }

    @Nonnull
    private static EntityEffect aura() {
        return new Authored(AURA_ID, true, 0f, OverlapBehavior.EXTEND);
    }

    @Nonnull
    private static EntityEffect timed() {
        return new Authored(TIMED_ID, false, 10f, OverlapBehavior.OVERWRITE);
    }

    /** A controller holding {@code worn}, its change queue emptied as after the viewers' first sync. */
    @Nonnull
    private static EffectControllerComponent wearing(@Nonnull ActiveEntityEffect... worn) {
        EffectControllerComponent ctrl = new EffectControllerComponent();
        ctrl.addActiveEntityEffects(worn);
        ctrl.clearChanges();
        return ctrl;
    }

    @Test
    void aSecondApplyOfAWornInfiniteEffectSendsItsViewersNothing() {
        EntityEffect aura = aura();
        try (EngineAssetStores.Swap ignored = EngineAssetStores.effects(aura, timed())) {
            EffectControllerComponent ctrl = wearing(new ActiveEntityEffect(AURA_ID, AURA, true, false));

            assertTrue(EffectAdds.asAuthored(ctrl, NO_REF, AURA, aura, NO_ACCESSOR),
                    "the repeat answers as the engine would for an effect with no conditions");
            assertTrue(EffectAdds.infinite(ctrl, NO_REF, AURA, aura, NO_ACCESSOR),
                    "and so does the no-expiry apply");

            assertEquals(0, ctrl.consumeChanges().length, "no Add reaches a client, so no second set of particles");
            assertTrue(ctrl.getActiveEffects().get(AURA).isInfinite(), "and the aura stays on");
        }
    }

    @Test
    void aTimedEffectAFirstApplyAndATimedInstanceGoToTheEngine() {
        EntityEffect aura = aura();
        EntityEffect timed = timed();
        try (EngineAssetStores.Swap ignored = EngineAssetStores.effects(aura, timed)) {
            EffectControllerComponent ctrl = wearing(new ActiveEntityEffect(TIMED_ID, TIMED, 5f, false, null, false));

            assertFalse(EffectAdds.repeatsWorn(ctrl, TIMED, timed.isInfinite()),
                    "a timed repeat refreshes or extends the effect, so the engine takes it");
            assertFalse(EffectAdds.repeatsWorn(ctrl, AURA, aura.isInfinite()), "a first apply goes on");

            EffectControllerComponent timedAura = wearing(new ActiveEntityEffect(AURA_ID, AURA, 5f, false, null, false));
            assertFalse(EffectAdds.repeatsWorn(timedAura, AURA, true),
                    "an infinite apply over a timed instance makes it infinite, so the engine takes it");

            EffectControllerComponent worn = wearing(new ActiveEntityEffect(TIMED_ID, TIMED, true, false));
            assertFalse(EffectAdds.repeatsWorn(worn, TIMED, timed.isInfinite()),
                    "a timed apply over a worn infinite instance is the engine's, which leaves it as it is");
        }
    }
}
