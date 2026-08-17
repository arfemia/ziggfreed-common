package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;

/**
 * The ONE authored gate leaf over the factor vocabulary: {@code {Factor, Param?, Min?, Max?}},
 * evaluated against whatever {@link FactorRegistry} the reading engine was handed. Wherever a
 * factor value must PASS a bound - an NPC placement's {@code Requires}, a dialogue option's
 * {@code Conditions} - it is this type, never a second condition schema per consumer.
 *
 * <pre>{@code
 * "Conditions": [ {"Factor": "yourmod:feature", "Param": "shop", "Min": 1} ]
 * }</pre>
 *
 * <p><b>An unresolvable factor FAILS CLOSED.</b> {@link #accepts} rejects {@code null}, and the
 * registry answers null for an unregistered id, a throwing provider, and a provider that cannot
 * answer alike - so content gated on an id nobody claimed stays gated rather than opening
 * unconditionally. A condition with NEITHER bound therefore passes as long as the factor resolves
 * to ANY non-null finite value - a presence check ONLY for a factor whose absent-case reading is
 * itself {@code null}, which is most of this package's vocabulary but not all of it: a factor id
 * that deliberately answers a definite non-null number for its own "absent" case (see
 * {@link ModFactors}'s {@code hytale:mod_installed}, which resolves a definite {@code 0} rather
 * than {@code null} when the named mod is not installed) is NOT gated by a bounds-less condition -
 * author {@code Min}/{@code Max} explicitly against that id's own documented reading instead of
 * relying on the bounds-less shortcut.
 *
 * <p><b>The codec is a FACTORY, not a single static.</b> Every consumer wants its OWN Asset
 * Editor pick list on the {@code Factor} field - the ids a placement may gate on are not the ids
 * a dialogue may gate on - so build one with {@link #codec(String)} naming your own dataset id.
 * {@link #CODEC} is the no-dropdown instance for a consumer that serves no dataset (an
 * unserved dropdown renders as an EMPTY pick list, which is worse for an author than a plain
 * text field).
 */
public final class FactorCondition {

    @Nullable protected String factor;
    @Nullable protected String param;
    @Nullable protected Double min;
    @Nullable protected Double max;

    /** The plain codec: no Asset Editor dropdown on {@code Factor}, so it stays a free text field. */
    public static final BuilderCodec<FactorCondition> CODEC = codec(null);

    /**
     * A codec whose {@code Factor} field offers the Asset Editor pick list served under
     * {@code editorDropdownDataSetId}; {@code null}/blank builds the plain free-text form.
     *
     * <p>Only name a dataset your own mod actually serves (one
     * {@code AssetEditorRequestDataSetEvent} handler per id, answering off your live registry) -
     * an unserved id yields a silently empty pick list rather than an error.
     */
    @Nonnull
    public static BuilderCodec<FactorCondition> codec(@Nullable String editorDropdownDataSetId) {
        BuilderCodec.Builder<FactorCondition> builder =
                BuilderCodec.builder(FactorCondition.class, FactorCondition::new);

        var factorField = builder
                .appendInherited(new KeyedCodec<>("Factor", Codec.STRING, false),
                        (o, v) -> o.factor = v, o -> o.factor, (o, p) -> o.factor = p.factor)
                .documentation("The namespaced factor id to gate on. An id nobody registered cannot resolve, "
                        + "so the gate fails CLOSED and the content stays hidden.");
        if (editorDropdownDataSetId != null && !editorDropdownDataSetId.isBlank()) {
            factorField = factorField.metadata(new UIEditor(new UIEditor.Dropdown(editorDropdownDataSetId)));
        }

        return factorField.add()
                .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                        (o, v) -> o.param = v, o -> o.param, (o, p) -> o.param = p.param)
                .documentation("Optional provider-interpreted argument, opaque here - whatever the factor's own "
                        + "owner documents (a stat id, an item id, a tag).").add()
                .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .documentation("Lower bound, inclusive: the resolved value must be >= Min. Omit for no lower bound.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .documentation("Upper bound, inclusive: the resolved value must be <= Max. Omit for no upper bound.").add()
                .build();
    }

    public FactorCondition() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static FactorCondition of(@Nullable String factor, @Nullable String param,
            @Nullable Double min, @Nullable Double max) {
        FactorCondition c = new FactorCondition();
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
     * The PURE bound test: does {@code resolved} satisfy this condition's authored bounds?
     *
     * <ul>
     *   <li>{@code null} ALWAYS fails - the factor could not be answered, and a gate that cannot
     *       be evaluated must never open.</li>
     *   <li>A non-finite value fails for the same reason: a NaN cannot be compared meaningfully.</li>
     *   <li>Both bounds are INCLUSIVE and independently optional.</li>
     *   <li>No bounds at all plus a non-null value PASSES - the presence check.</li>
     * </ul>
     */
    public boolean accepts(@Nullable Double resolved) {
        if (resolved == null || !Double.isFinite(resolved)) {
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
