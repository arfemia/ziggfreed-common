package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One authored gate on a placement: {@code {Factor, Param?, Min?, Max?}}, evaluated against the
 * extensible numeric vocabulary {@link PlacementFactorRegistry} resolves.
 *
 * <p>This is the READ side of the same namespaced-channel paradigm {@link PlacementBinding} is
 * the write side of. A {@code Binding} hands an opaque payload OUT and is never interpreted here;
 * a {@code Condition} asks a registered provider for a number and compares it to the authored
 * bounds. Both keep the vocabulary owned by the mod that registers it, so a pack author can gate
 * an NPC on a consumer's own state with no Java.
 *
 * <pre>{@code
 * "Requires": { "Conditions": [ {"Factor": "yourmod:feature", "Param": "bounty", "Min": 1} ] }
 * }</pre>
 *
 * <p><b>An unregistered factor resolves to 0 and the gate FAILS CLOSED.</b> A placement gated on
 * a mod that is not installed simply does not appear, rather than appearing unconditionally.
 * {@link NpcPlacementValidator} reports an unregistered factor so the fail-closed case is
 * visible at authoring time instead of only as a missing NPC.
 *
 * <p>A condition with neither bound authored is a presence check: it passes as long as the factor
 * itself is registered, which is how "only where this mod is installed" is expressed.
 */
public final class PlacementCondition {

    @Nullable protected String factor;
    @Nullable protected String param;
    @Nullable protected Double min;
    @Nullable protected Double max;

    public static final BuilderCodec<PlacementCondition> CODEC =
            BuilderCodec.builder(PlacementCondition.class, PlacementCondition::new)
                    .appendInherited(new KeyedCodec<>("Factor", Codec.STRING, false),
                            (o, v) -> o.factor = v, o -> o.factor, (o, p) -> o.factor = p.factor)
                    .documentation("The namespaced factor id to gate on. Unregistered resolves to 0, so the gate fails CLOSED.").add()
                    .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                            (o, v) -> o.param = v, o -> o.param, (o, p) -> o.param = p.param)
                    .documentation("Optional provider-interpreted argument, opaque here (whatever the factor's owner documents).").add()
                    .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                            (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                    .documentation("Lower bound, inclusive: the resolved value must be >= Min. Omit for no lower bound.").add()
                    .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                            (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                    .documentation("Upper bound, inclusive: the resolved value must be <= Max. Omit for no upper bound.").add()
                    .build();

    public PlacementCondition() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static PlacementCondition of(@Nullable String factor, @Nullable String param,
            @Nullable Double min, @Nullable Double max) {
        PlacementCondition c = new PlacementCondition();
        c.factor = factor;
        c.param = param;
        c.min = min;
        c.max = max;
        return c;
    }

    @Nullable
    public String getFactor() {
        return factor;
    }

    @Nullable
    public String getParam() {
        return param;
    }

    @Nullable
    public Double getMin() {
        return min;
    }

    @Nullable
    public Double getMax() {
        return max;
    }

    /**
     * The PURE bound test: does {@code resolved} satisfy this condition's authored bounds? A
     * condition with no bounds passes any finite value. A non-finite value never passes (a
     * provider that returned NaN cannot be reasoned about, so the gate stays closed).
     */
    public boolean accepts(double resolved) {
        if (!Double.isFinite(resolved)) {
            return false;
        }
        if (min != null && resolved < min) {
            return false;
        }
        return max == null || !(resolved > max);
    }

    /** True when no factor id is authored, so this entry can never be evaluated. */
    public boolean isBlank() {
        return factor == null || factor.isBlank();
    }
}
