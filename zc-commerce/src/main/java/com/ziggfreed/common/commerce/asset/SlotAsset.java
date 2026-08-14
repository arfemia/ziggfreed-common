package com.ziggfreed.common.commerce.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The leaves EVERY slot of a rotating set carries: how many candidates it yields, and whether it may
 * come up empty.
 *
 * <p>A slot is how a rotation is SHAPED rather than left to chance - "two easy contracts and one
 * hard one" instead of three draws that might all land easy. It is declared once here and appended
 * into each domain's own slot codec, which adds the ONE word that domain filters on (a contract's
 * difficulty band, an offer's tier), so the structure cannot drift while each side keeps the word
 * its own authors already use.
 *
 * <p>Every leaf is {@code appendInherited}, so a file with a {@code Parent} can retune one slot's
 * count and keep the rest.
 */
public class SlotAsset {

    @Nullable protected Integer count;
    @Nullable protected Boolean optional;

    public static final BuilderCodec<SlotAsset> CODEC =
            appendLeaves(BuilderCodec.builder(SlotAsset.class, SlotAsset::new)).build();

    /**
     * Register the two shared slot leaves on {@code builder}. Each domain's slot codec starts from
     * this call, which is what keeps the field names from drifting apart.
     */
    @Nonnull
    protected static <T extends SlotAsset, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .documentation("How many DISTINCT candidates this slot yields. Unauthored means 1; write 2 rather "
                        + "than repeating the same slot twice.").add()
                .appendInherited(new KeyedCodec<>("Optional", Codec.BOOLEAN, false),
                        (o, v) -> o.optional = v, o -> o.optional, (o, p) -> o.optional = p.optional)
                .documentation("Skip this slot silently when nothing eligible can fill it, instead of leaving a "
                        + "gap that looks broken. Author it on the rarest band, where a thin catalogue is normal.").add();
    }

    public SlotAsset() {
    }

    /** How many distinct candidates, at least 1. */
    public int countOrOne() {
        return count == null || count < 1 ? 1 : count;
    }

    /** The authored count exactly as written, for an audit that must see a bad one. */
    @Nullable
    public Integer getCount() {
        return count;
    }

    /** May this slot come up empty without anybody being told? */
    public boolean isOptional() {
        return optional != null && optional;
    }

    /**
     * The candidate label this slot draws, or null for "anything eligible". Each domain's slot
     * spells it in its own word; this is the one read a draw or an audit uses.
     */
    @Nullable
    public String label() {
        return null;
    }
}
