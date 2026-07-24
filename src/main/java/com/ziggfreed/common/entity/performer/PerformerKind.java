package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which backend owns a {@link StationPerformer}: the bare-{@code Holder} puppet double
 * ({@link HolderPerformer}) or the Role-driven {@code NPCEntity} ({@link NpcRolePerformer}).
 * Carried on {@link PerformerIdentityComponent} (as its stable {@link #code()} string) so a
 * reconcile sweep and telemetry can tell the two apart without touching the concrete backend.
 */
public enum PerformerKind {

    /** The bare-{@code Holder} skinned puppet double (the shipped, proven route). */
    HOLDER("BareHolder"),

    /** The Role-driven {@code NPCEntity} performer (native gait, engine A*). */
    NPC_ROLE("NpcRole");

    @Nonnull
    private final String code;

    PerformerKind(@Nonnull String code) {
        this.code = code;
    }

    /** The stable string this kind serializes as on {@link PerformerIdentityComponent}. */
    @Nonnull
    public String code() {
        return code;
    }

    /**
     * The kind for a stored {@link #code()}, defaulting to {@link #HOLDER} for a null/unknown
     * code (so a forward-authored or corrupt value never NPEs a sweep).
     */
    @Nonnull
    public static PerformerKind fromCode(@Nullable String code) {
        if (code != null) {
            for (PerformerKind k : values()) {
                if (k.code.equals(code)) {
                    return k;
                }
            }
        }
        return HOLDER;
    }
}
