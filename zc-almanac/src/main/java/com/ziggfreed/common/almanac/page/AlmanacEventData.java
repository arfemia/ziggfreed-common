package com.ziggfreed.common.almanac.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What the Almanac page round-trips on a binding: {@code action} is {@code select} (open the page on
 * {@code season}) or {@code close}.
 */
public class AlmanacEventData {

    public String action;
    public String season;

    public static final BuilderCodec<AlmanacEventData> CODEC =
            BuilderCodec.builder(AlmanacEventData.class, AlmanacEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Season", Codec.STRING),
                            (data, value, info) -> data.season = value,
                            (data, info) -> data.season)
                    .add()
                    .build();
}
