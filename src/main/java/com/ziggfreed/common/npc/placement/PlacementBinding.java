package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One entry of a placement's {@code Interact.Bindings} map: the payload a consumer mod reads on
 * its own namespaced channel. This library forwards it verbatim and interprets NOTHING.
 *
 * <p>The map is keyed by channel id ({@code "yourmod:ui_target"}), so the KEY says who the entry
 * is for and this record carries only the payload:
 * <pre>{@code
 * "Bindings": { "yourmod:ui_target": { "Value": "hub" },
 *               "yourmod:npc_id":    { "Value": "guide" } }
 * }</pre>
 *
 * <ul>
 *   <li><b>{@code Value}</b> - the usual case: a single opaque string the channel's owner reads.</li>
 *   <li><b>{@code Param}</b> - an optional second opaque argument, for a channel that needs to say
 *       both "which thing" and "which flavour of it" without inventing a delimiter.</li>
 *   <li><b>{@code Amount}</b> - an optional number, for a channel whose payload is quantitative.</li>
 * </ul>
 *
 * <p>All three are independently optional so one record shape serves every channel; a channel's
 * owner documents which of them it reads. Every leaf is {@code appendInherited}, and the map is
 * decoded through a per-key merging codec, so a placement inheriting from a {@code Parent} can
 * override ONE channel and keep every sibling channel the parent authored.
 */
public final class PlacementBinding {

    @Nullable protected String param;
    @Nullable protected String value;
    @Nullable protected Double amount;

    public static final BuilderCodec<PlacementBinding> CODEC =
            BuilderCodec.builder(PlacementBinding.class, PlacementBinding::new)
                    .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                            (o, v) -> o.param = v, o -> o.param, (o, p) -> o.param = p.param)
                    .documentation("Optional second opaque argument, read only by the channel's owner.").add()
                    .appendInherited(new KeyedCodec<>("Value", Codec.STRING, false),
                            (o, v) -> o.value = v, o -> o.value, (o, p) -> o.value = p.value)
                    .documentation("The opaque payload string the channel's owner reads. The usual leaf to author.").add()
                    .appendInherited(new KeyedCodec<>("Amount", Codec.DOUBLE, false),
                            (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                    .documentation("Optional numeric payload, for a channel whose value is a quantity.").add()
                    .build();

    public PlacementBinding() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static PlacementBinding of(@Nullable String param, @Nullable String value, @Nullable Double amount) {
        PlacementBinding b = new PlacementBinding();
        b.param = param;
        b.value = value;
        b.amount = amount;
        return b;
    }

    /** Convenience for the common single-string case. */
    @Nonnull
    public static PlacementBinding value(@Nullable String value) {
        return of(null, value, null);
    }

    @Nullable
    public String getParam() {
        return param;
    }

    @Nullable
    public String getValue() {
        return value;
    }

    @Nullable
    public Double getAmount() {
        return amount;
    }

    /** {@link #amount}, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveAmount() {
        return amount != null ? amount : 0.0;
    }

    /** True when nothing at all is authored, so this entry carries no payload. */
    public boolean isBlank() {
        return (param == null || param.isBlank())
                && (value == null || value.isBlank())
                && amount == null;
    }
}
