package com.ziggfreed.common.ui.spike;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What a press on the {@code /zigspike} bench sends back: the button's action and, for a row appended into
 * the opened section, its index. Spike branch only, never merged.
 */
public class SpikeEventData {

    public String action;
    public String index;

    public static final BuilderCodec<SpikeEventData> CODEC =
            BuilderCodec.builder(SpikeEventData.class, SpikeEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Index", Codec.STRING),
                            (data, value, info) -> data.index = value,
                            (data, info) -> data.index)
                    .add()
                    .build();
}
