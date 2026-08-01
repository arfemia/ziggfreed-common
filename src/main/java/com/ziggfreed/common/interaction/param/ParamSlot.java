package com.ziggfreed.common.interaction.param;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A number-bearing leaf on a custom interaction Type: the authored {@code Base} plus the
 * optional {@code Key} that makes it modifiable per caster at FIRE time via a {@link ParamFold}.
 *
 * <p>Authored JSON shape (PascalCase, one nested sub-object per number - never flat
 * {@code FooBase}/{@code FooKey} soup):
 * <pre>{@code
 * "Damage": { "Key": "damage", "Base": 12.0 }
 * }</pre>
 * A missing {@code Key} means "not modifiable" and {@link #resolve} returns {@link #getBase()}
 * unfolded. A missing {@code Base} decodes to {@code 0}.
 *
 * <p><b>{@link #codec()} is a LAZY, memoized accessor, never a {@code public static final CODEC}
 * field</b>, so a consumer composing this into an owning Type's codec does so lazily end to end
 * and nothing here forces a codec class-init in a unit JVM merely by loading this class. Both
 * keys are wired {@code appendInherited} (not plain {@code append}) so a native {@code Parent} on
 * the OWNING Type actually inherits this nested slot instead of silently dropping it when the
 * child JSON omits the group entirely.
 *
 * <p>The codec declares NO validators, deliberately: {@code Validators.min}/{@code range}
 * construct a {@code RangeValidator}, the exact class whose {@code <clinit>} touches
 * {@code HytaleLogger} and throws outside a live server (the same trap documented on
 * {@code RunCommandInteraction.java:127-137}). Range / sanity policy for an authored {@code Base}
 * belongs in the consumer's own content validator, not in this shared leaf's codec.
 */
public final class ParamSlot {

    @Nullable private String key;
    private double base;

    /** Codec ctor. */
    public ParamSlot() {
    }

    public ParamSlot(@Nullable String key, double base) {
        this.key = key;
        this.base = base;
    }

    @Nullable
    public String getKey() {
        return key;
    }

    public double getBase() {
        return base;
    }

    /**
     * Fold this slot for one fire. Returns {@link #getBase()} unchanged when {@code fold} is
     * null or {@link #getKey()} is null/blank (matching {@link ParamFold}'s own no-resolver /
     * blank-key degradation, so this leaf never needs a resolver reference just to read a
     * constant).
     */
    public double resolve(@Nullable ParamFold fold,
                          @Nullable Store<EntityStore> store,
                          @Nullable Ref<EntityStore> caster,
                          @Nullable CastScope scope) {
        if (fold == null || key == null || key.isBlank()) {
            return base;
        }
        return fold.resolve(store, caster, scope, key, base);
    }

    @Override
    @Nonnull
    public String toString() {
        return "ParamSlot{key='" + key + "', base=" + base + '}';
    }

    /** Memoized; safe to call repeatedly, returns the same instance. Keys: {@code "Key"}, {@code "Base"}. */
    @Nonnull
    public static BuilderCodec<ParamSlot> codec() {
        return CodecHolder.CODEC;
    }

    /** Lazy holder (Bill Pugh idiom): the codec is built on first {@link #codec()} call, never at class-load. */
    private static final class CodecHolder {
        private static final BuilderCodec<ParamSlot> CODEC = build();

        @Nonnull
        private static BuilderCodec<ParamSlot> build() {
            return BuilderCodec.builder(ParamSlot.class, ParamSlot::new)
                    .documentation("A number-bearing leaf: an authored Base plus an optional modifier Key.")
                    .appendInherited(new KeyedCodec<>("Key", Codec.STRING, false),
                            (o, v) -> o.key = v, o -> o.key, (o, p) -> o.key = p.key)
                            .documentation("The declared modifier key. Omitted = the value is not modifiable.").add()
                    .appendInherited(new KeyedCodec<>("Base", Codec.DOUBLE, false),
                            (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                            .documentation("The authored base value before any per-caster fold.").add()
                    .build();
        }
    }
}
