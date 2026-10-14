package com.ziggfreed.common.instance.reward;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * One {@link InstanceReward} as a store writes it to disk, the Gson shape {@link PendingRewardStore} and
 * {@code ClaimStore} share: {@code kind}, {@code id}, {@code quantity}, {@code displayKey}, {@code iconItemId},
 * and the optional {@code spec} a deferred row keeps ({@code {"kind": "Currency", "params": {...}}}).
 *
 * <p>The spec is additive: a file written before it was kept has none and reads exactly as it did, and a
 * build that does not know it skips it, so neither store's version moves for it.
 */
public final class StoredReward {

    private String kind;
    private String id;
    private int quantity;
    private String displayKey;
    private String iconItemId;
    /** Absent in a file written before a deferred row kept its spec; read as none. */
    private StoredSpec spec;

    /** The persisted shape of a {@link RewardSpec}: its kind and its parameters, as written. */
    private static final class StoredSpec {
        String kind;
        Map<String, String> params;
    }

    private StoredReward() {
    }

    /** {@code reward} in the shape a store writes. */
    @Nonnull
    public static StoredReward of(@Nonnull InstanceReward reward) {
        StoredReward s = new StoredReward();
        s.kind = reward.kind().name();
        s.id = reward.id();
        s.quantity = reward.quantity();
        s.displayKey = reward.displayKey();
        s.iconItemId = reward.iconItemId();
        RewardSpec spec = reward.spec();
        if (spec != null) {
            s.spec = new StoredSpec();
            s.spec.kind = spec.kind();
            s.spec.params = new LinkedHashMap<>(spec.params());
        }
        return s;
    }

    /** The reward this row holds, or null for a row too damaged to read (no kind, no id, an unknown kind). */
    @Nullable
    public InstanceReward toReward() {
        if (kind == null || id == null) {
            return null;
        }
        try {
            return new InstanceReward(InstanceReward.Kind.valueOf(kind), id, quantity, displayKey, iconItemId,
                    specOf(spec));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The spec a row kept, or null when it kept none or kept one with no kind. */
    @Nullable
    private static RewardSpec specOf(@Nullable StoredSpec stored) {
        if (stored == null || stored.kind == null || stored.kind.isBlank()) {
            return null;
        }
        return RewardSpec.of(stored.kind, stored.params == null ? Map.of() : stored.params);
    }
}
