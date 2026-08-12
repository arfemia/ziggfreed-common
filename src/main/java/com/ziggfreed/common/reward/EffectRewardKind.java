package com.ziggfreed.common.reward;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The reward kind that applies a native effect: {@code {"Kind": "effect", "Params": {"Effect": "..."}}}.
 *
 * <p>It lives up here in the wiring layer rather than beside the other reward kinds, and the reason is
 * a rule worth keeping: the loot layer sits UNDERNEATH everything that pays out, so it must never
 * reach sideways into a domain like effects. If it did, every engine that grants a reward would drag
 * the effect system in behind it.
 *
 * <p>So the loot layer keeps a hole where an effect grant would be, and this class fills it from
 * above, where seeing both is allowed. That is the same shape every cross-domain reward should take:
 * the layer that can see both ends registers the kind, and the layer underneath just knows a name.
 *
 * <p>{@code DurationSeconds} overrides the effect asset's own duration when written; omit it to use
 * whatever the asset says.
 */
public final class EffectRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "effect";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    private EffectRewardKind() {
    }

    /** Register the effect kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new EffectRewardKind());
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String effectId = spec.paramOr("effect", spec.paramOr("id", "")).trim();
        if (effectId.isEmpty()) {
            throw new IllegalStateException(
                    "an '" + KIND + "' reward named no effect - it needs an 'Effect' parameter");
        }
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            throw new IllegalStateException("no live player to apply effect '" + effectId + "' to");
        }
        double seconds = spec.doubleParam("durationseconds", -1.0);
        boolean applied = seconds > 0.0
                ? NativeEffectUtil.applyFor(ref.getStore(), ref, effectId, (float) seconds,
                        OverlapBehavior.OVERWRITE)
                : NativeEffectUtil.apply(ref.getStore(), ref, effectId);
        if (!applied) {
            throw new IllegalStateException("effect '" + effectId + "' could not be applied");
        }
    }
}
