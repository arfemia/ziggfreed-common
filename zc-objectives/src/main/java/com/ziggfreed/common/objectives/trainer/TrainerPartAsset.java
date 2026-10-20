package com.ziggfreed.common.objectives.trainer;

import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A library part of the trainer page placed in a trainer block's {@code Sections}: {@code Learn}, {@code Quests} or
 * {@code Services}, each written as an empty group ({@code { "Learn": {} }}). It carries no knobs yet; a later one
 * lands here without changing the shape an author writes.
 */
public final class TrainerPartAsset {

    public static final BuilderCodec<TrainerPartAsset> CODEC =
            BuilderCodec.builder(TrainerPartAsset.class, TrainerPartAsset::new).build();

    public TrainerPartAsset() {
    }
}
