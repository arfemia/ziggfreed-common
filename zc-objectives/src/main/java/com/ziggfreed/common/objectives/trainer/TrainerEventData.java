package com.ziggfreed.common.objectives.trainer;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link TrainerPage} round-trips on every binding: {@code action} is {@code close}, {@code select} (open
 * the entry {@code row} of the part at index {@code section}) or {@code primary} (the reading page's main button,
 * which acts on whatever entry the page shows, so it carries no id).
 */
public class TrainerEventData {

    public String action;
    public String section;
    public String row;

    public static final BuilderCodec<TrainerEventData> CODEC =
            BuilderCodec.builder(TrainerEventData.class, TrainerEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Section", Codec.STRING),
                            (data, value, info) -> data.section = value,
                            (data, info) -> data.section)
                    .add()
                    .append(new KeyedCodec<>("Row", Codec.STRING),
                            (data, value, info) -> data.row = value,
                            (data, info) -> data.row)
                    .add()
                    .build();
}
