package com.ziggfreed.common.almanac.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What the Almanac page round-trips on a binding: {@code action} is {@code select} (open the page on
 * {@code season}) or {@code close}, or a rail click, which carries {@code menu}.
 */
public class AlmanacEventData {

    public String action;
    public String season;
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the page's own. */
    public String menu;

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
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, value, info) -> data.menu = value,
                            (data, info) -> data.menu)
                    .add()
                    .build();
}
