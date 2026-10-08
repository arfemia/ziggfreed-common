package com.ziggfreed.common.almanac.asset;

import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A built-in part of a season's page placed in its {@code Sections}: {@code Tallies}, {@code Keepsakes} or
 * {@code Links}, each written as an empty group ({@code { "Tallies": {} }}). It carries no knobs yet; a
 * later one lands here without changing the shape an author writes.
 */
public final class AlmanacPartAsset {

    public static final BuilderCodec<AlmanacPartAsset> CODEC =
            BuilderCodec.builder(AlmanacPartAsset.class, AlmanacPartAsset::new).build();

    public AlmanacPartAsset() {
    }
}
