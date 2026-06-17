package com.ziggfreed.common.dialogue.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The option-click event the dialogue page round-trips: action + node + option index. */
public class DialogueEventData {

    public String action;
    public String node;
    public String option;

    public static final BuilderCodec<DialogueEventData> CODEC =
            BuilderCodec.builder(DialogueEventData.class, DialogueEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Node", Codec.STRING),
                            (data, value, info) -> data.node = value,
                            (data, info) -> data.node)
                    .add()
                    .append(new KeyedCodec<>("Option", Codec.STRING),
                            (data, value, info) -> data.option = value,
                            (data, info) -> data.option)
                    .add()
                    .build();
}
