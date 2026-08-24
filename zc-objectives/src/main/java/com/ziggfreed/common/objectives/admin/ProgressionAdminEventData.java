package com.ziggfreed.common.objectives.admin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the progression admin page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code back}, or {@code toggle} (flip the switch named
 * in {@code id}). The page holds no filter state, so the full state a binding carries is exactly
 * these two fields.
 */
public class ProgressionAdminEventData {

    public String action;

    /** The {@link SystemSwitch#id()} a toggle click names. */
    public String id;

    public static final BuilderCodec<ProgressionAdminEventData> CODEC =
            BuilderCodec.builder(ProgressionAdminEventData.class, ProgressionAdminEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Id", Codec.STRING),
                            (data, value, info) -> data.id = value,
                            (data, info) -> data.id)
                    .add()
                    .build();
}
