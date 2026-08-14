package com.ziggfreed.common.shop.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.commerce.asset.SlotAsset;

/**
 * One slot of a rotating shelf: the shared slot leaves ({@code Count} / {@code Optional}) plus the
 * one word a shelf filters on, an offer's {@code Tier}.
 *
 * <pre>{@code
 * "Slots": [ { "Tier": "Lesser" }, { "Tier": "Greater" }, { "Tier": "Master", "Optional": true } ]
 * }</pre>
 *
 * <p>Slots are how a shelf is SHAPED rather than left to chance: three slots naming three tiers show
 * one of each every rotation, where three unfiltered draws could show three of the same. Author no
 * slots at all and the shelf simply draws from everything the pool holds.
 */
public final class PoolSlotAsset extends SlotAsset {

    @Nullable protected String tier;

    public static final BuilderCodec<PoolSlotAsset> CODEC =
            appendLeaves(BuilderCodec.builder(PoolSlotAsset.class, PoolSlotAsset::new))
                    .appendInherited(new KeyedCodec<>("Tier", Codec.STRING, false),
                            (o, v) -> o.tier = v, o -> o.tier, (o, p) -> o.tier = p.tier)
                    .documentation("Only draw offers whose own Pool.Tier is this word. It is a free label the "
                            + "content invents - lesser, greater, master - matched however it is capitalized. "
                            + "Unauthored draws from anything in the pool.").add()
                    .build();

    public PoolSlotAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static PoolSlotAsset of(@Nullable String tier, @Nullable Integer count, @Nullable Boolean optional) {
        PoolSlotAsset s = new PoolSlotAsset();
        s.tier = tier;
        s.count = count;
        s.optional = optional;
        return s;
    }

    /** The authored tier exactly as written, or null for "anything in the pool". */
    @Nullable
    public String getTier() {
        return tier == null || tier.isBlank() ? null : tier.trim();
    }

    /** The candidate label this slot draws, lower-cased for matching, or null for anything. */
    @Override
    @Nullable
    public String label() {
        String authored = getTier();
        return authored == null ? null : authored.toLowerCase(Locale.ROOT);
    }
}
