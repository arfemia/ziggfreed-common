package com.ziggfreed.common.entity.performer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The resolved appearance/behaviour config a caller hands a performer at spawn - the Java value the
 * seam's {@code Puppet.Look} schema resolves to. Immutable; built once per spawn. Orthogonal knobs,
 * each independently meaningful: no leaf bundles another's behaviour.
 *
 * <ul>
 *   <li>{@link #source()} - which appearance mechanism ({@code PlayerClone}/{@code Model} drive the
 *       bare-{@code Holder} backend; {@code NpcRole} drives the NPC backend).</li>
 *   <li>{@link #modelId()} / {@link #fallbackModelId()} - a fixed model id for {@code Model}, and
 *       the shared resolution-ladder tail for EVERY source (unresolvable look -&gt; fallback model
 *       -&gt; engine default, never a red-X).</li>
 *   <li>{@link #roleId()} - the Role asset id for {@code NpcRole}.</li>
 *   <li>{@link #skinSource()} - whose appearance an {@code NpcRole} NPC wears.</li>
 *   <li>{@link #speedMps()} - walk speed override ({@code null} defers to the backend default / the
 *       role asset's own walk speed).</li>
 *   <li>{@link #persist()} - {@code false} (default) marks the performer transient
 *       ({@code NonSerialized}); {@code true} reserves the future persistent-performer posture.</li>
 * </ul>
 */
public final class PerformerLook {

    /** Which appearance MECHANISM a performer uses; a union discriminator, not a mode. */
    public enum LookSource {
        /** Clone the live player's skin onto a bare-{@code Holder} double (the default). */
        PLAYER_CLONE,
        /** A fixed authored model id on a bare-{@code Holder} double. */
        MODEL,
        /** A Role-driven {@code NPCEntity} performer. */
        NPC_ROLE
    }

    /** Whose appearance an {@code NpcRole} performer wears; a union discriminator, not a mode. */
    public enum SkinSource {
        /** Clone the live player's skin onto the Role NPC (continuity with the crowned puppet look). */
        PLAYER_CLONE,
        /** Use the Role asset's own model (a fixed apprentice/golem worker). */
        ROLE_DEFAULT
    }

    @Nonnull
    private final LookSource source;
    @Nullable
    private final String modelId;
    @Nullable
    private final String fallbackModelId;
    @Nullable
    private final String roleId;
    @Nonnull
    private final SkinSource skinSource;
    @Nullable
    private final Double speedMps;
    private final boolean persist;

    private PerformerLook(@Nonnull Builder b) {
        this.source = b.source;
        this.modelId = b.modelId;
        this.fallbackModelId = b.fallbackModelId;
        this.roleId = b.roleId;
        this.skinSource = b.skinSource;
        this.speedMps = b.speedMps;
        this.persist = b.persist;
    }

    /** A plain player-clone look on the bare-{@code Holder} backend (the default arm). */
    @Nonnull
    public static PerformerLook playerClone() {
        return builder().build();
    }

    @Nonnull
    public LookSource source() {
        return source;
    }

    @Nullable
    public String modelId() {
        return modelId;
    }

    @Nullable
    public String fallbackModelId() {
        return fallbackModelId;
    }

    @Nullable
    public String roleId() {
        return roleId;
    }

    @Nonnull
    public SkinSource skinSource() {
        return skinSource;
    }

    @Nullable
    public Double speedMps() {
        return speedMps;
    }

    public boolean persist() {
        return persist;
    }

    /** The backend {@link PerformerKind} this look routes to. */
    @Nonnull
    public PerformerKind kind() {
        return source == LookSource.NPC_ROLE ? PerformerKind.NPC_ROLE : PerformerKind.HOLDER;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for a {@link PerformerLook}; defaults to a transient player-clone look. */
    public static final class Builder {
        @Nonnull
        private LookSource source = LookSource.PLAYER_CLONE;
        @Nullable
        private String modelId;
        @Nullable
        private String fallbackModelId;
        @Nullable
        private String roleId;
        @Nonnull
        private SkinSource skinSource = SkinSource.PLAYER_CLONE;
        @Nullable
        private Double speedMps;
        private boolean persist;

        @Nonnull
        public Builder source(@Nonnull LookSource source) {
            this.source = source;
            return this;
        }

        @Nonnull
        public Builder modelId(@Nullable String modelId) {
            this.modelId = modelId;
            return this;
        }

        @Nonnull
        public Builder fallbackModelId(@Nullable String fallbackModelId) {
            this.fallbackModelId = fallbackModelId;
            return this;
        }

        @Nonnull
        public Builder roleId(@Nullable String roleId) {
            this.roleId = roleId;
            return this;
        }

        @Nonnull
        public Builder skinSource(@Nonnull SkinSource skinSource) {
            this.skinSource = skinSource;
            return this;
        }

        @Nonnull
        public Builder speedMps(@Nullable Double speedMps) {
            this.speedMps = speedMps;
            return this;
        }

        @Nonnull
        public Builder persist(boolean persist) {
            this.persist = persist;
            return this;
        }

        @Nonnull
        public PerformerLook build() {
            return new PerformerLook(this);
        }
    }
}
