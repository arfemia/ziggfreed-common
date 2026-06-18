package com.ziggfreed.common.instance.queue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The button event the queue page round-trips: just an action ("leave" or "close"). */
public class QueueEventData {

    public String action;

    public static final BuilderCodec<QueueEventData> CODEC =
            BuilderCodec.builder(QueueEventData.class, QueueEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .build();
}
