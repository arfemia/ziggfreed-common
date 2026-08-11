package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>{@code Values}</b> - a LIST of opaque strings, for a channel whose payload is genuinely
 *       several of something ({@code "Values": ["a", "b"]}). One entry per array element, so a
 *       channel never has to invent a separator inside {@code Value} and an entry may contain any
 *       character.</li>
 *   <li><b>{@code Param}</b> - an optional second opaque argument, for a channel that needs to say
 *       both "which thing" and "which flavour of it" without inventing a delimiter.</li>
 *   <li><b>{@code Amount}</b> - an optional number, for a channel whose payload is quantitative.</li>
 * </ul>
 *
 * <p>All four are independently optional so one record shape serves every channel; a channel's
 * owner documents which of them it reads. Every leaf is {@code appendInherited}, and the map is
 * decoded through a per-key merging codec, so a placement inheriting from a {@code Parent} can
 * override ONE channel and keep every sibling channel the parent authored. {@code Values} follows
 * that same rule as ONE leaf: omit it and the parent's list is inherited whole, author it and the
 * parent's list is replaced whole (an empty array is the way to inherit a channel and carry no
 * list at all).
 */
public final class PlacementBinding {

    @Nullable protected String param;
    @Nullable protected String value;
    @Nullable protected String[] values;
    @Nullable protected Double amount;

    public static final BuilderCodec<PlacementBinding> CODEC =
            BuilderCodec.builder(PlacementBinding.class, PlacementBinding::new)
                    .appendInherited(new KeyedCodec<>("Param", Codec.STRING, false),
                            (o, v) -> o.param = v, o -> o.param, (o, p) -> o.param = p.param)
                    .documentation("Optional second opaque argument, read only by the channel's owner.").add()
                    .appendInherited(new KeyedCodec<>("Value", Codec.STRING, false),
                            (o, v) -> o.value = v, o -> o.value, (o, p) -> o.value = p.value)
                    .documentation("The opaque payload string the channel's owner reads. The usual leaf to author.").add()
                    .appendInherited(new KeyedCodec<>("Values", Codec.STRING_ARRAY, false),
                            (o, v) -> o.values = v, o -> o.values, (o, p) -> o.values = p.values)
                    .documentation("Optional list payload, for a channel that takes several strings."
                            + " Authoring it replaces an inherited list whole; omitting it inherits one.").add()
                    .appendInherited(new KeyedCodec<>("Amount", Codec.DOUBLE, false),
                            (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                    .documentation("Optional numeric payload, for a channel whose value is a quantity.").add()
                    .build();

    public PlacementBinding() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static PlacementBinding of(@Nullable String param, @Nullable String value, @Nullable Double amount) {
        return of(param, value, null, amount);
    }

    /** As {@link #of(String, String, Double)}, additionally carrying the {@code Values} list. */
    @Nonnull
    public static PlacementBinding of(@Nullable String param, @Nullable String value,
            @Nullable String[] values, @Nullable Double amount) {
        PlacementBinding b = new PlacementBinding();
        b.param = param;
        b.value = value;
        b.values = values;
        b.amount = amount;
        return b;
    }

    /** Convenience for the common single-string case. */
    @Nonnull
    public static PlacementBinding value(@Nullable String value) {
        return of(null, value, null);
    }

    /** Convenience for the list case, the twin of {@link #value(String)}. */
    @Nonnull
    public static PlacementBinding values(@Nullable String... values) {
        return of(null, null, values, null);
    }

    @Nullable
    public String getParam() {
        return param;
    }

    @Nullable
    public String getValue() {
        return value;
    }

    /** The authored {@code Values} list, or null when the channel authored none. */
    @Nullable
    public String[] getValues() {
        return values;
    }

    @Nullable
    public Double getAmount() {
        return amount;
    }

    /** {@link #amount}, reader-defaulted to {@code 0.0} when unauthored. */
    public double effectiveAmount() {
        return amount != null ? amount : 0.0;
    }

    /**
     * {@link #values}, reader-defaulted to an empty list when unauthored, so a channel's owner
     * iterates it without a null guard. Entries are handed over exactly as authored: this library
     * interprets nothing, so trimming, blank-dropping and de-duplication belong to the owner.
     */
    @Nonnull
    public List<String> effectiveValues() {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String entry : values) {
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /** True when nothing at all is authored, so this entry carries no payload. */
    public boolean isBlank() {
        return (param == null || param.isBlank())
                && (value == null || value.isBlank())
                && (values == null || values.length == 0)
                && amount == null;
    }
}
