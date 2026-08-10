package com.ziggfreed.common.instance.result;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The footer-button event the results page round-trips: just an action. */
public class ResultsEventData {

    public String action;

    public static final BuilderCodec<ResultsEventData> CODEC =
            BuilderCodec.builder(ResultsEventData.class, ResultsEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .build();
}
