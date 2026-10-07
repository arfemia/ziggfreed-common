package com.ziggfreed.common.objectives.title.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What the picker round-trips on every binding: {@code Action} ({@code press}, {@code none}, {@code open},
 * {@code back}, {@code close}); for a press, {@code Title}, the tile's title id; for an open,
 * {@code Achievement}, the id of the achievement a not-earned line comes from.
 */
public final class TitlePickerEventData {

    public String action;
    public String title;
    public String achievement;

    public static final BuilderCodec<TitlePickerEventData> CODEC =
            BuilderCodec.builder(TitlePickerEventData.class, TitlePickerEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, v, info) -> data.action = v,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Title", Codec.STRING),
                            (data, v, info) -> data.title = v,
                            (data, info) -> data.title)
                    .add()
                    .append(new KeyedCodec<>("Achievement", Codec.STRING),
                            (data, v, info) -> data.achievement = v,
                            (data, info) -> data.achievement)
                    .add()
                    .build();
}
