package com.ziggfreed.common.settings.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What the Settings tab round-trips on a binding: {@code Action} is {@code toggle} (a switch was pressed),
 * {@code choice} (a dropdown changed, its value under {@code @Value}), {@code tile} or {@code close};
 * {@code Row} is the drawn row's index; a rail click carries {@code Menu} instead.
 */
public class SettingsEventData {

    public String action;
    public String row;
    public String value;
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the page's own. */
    public String menu;

    public static final BuilderCodec<SettingsEventData> CODEC =
            BuilderCodec.builder(SettingsEventData.class, SettingsEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, v, info) -> data.action = v, (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Row", Codec.STRING),
                            (data, v, info) -> data.row = v, (data, info) -> data.row)
                    .add()
                    .append(new KeyedCodec<>(SettingsPage.VALUE_KEY, Codec.STRING),
                            (data, v, info) -> data.value = v, (data, info) -> data.value)
                    .add()
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, v, info) -> data.menu = v, (data, info) -> data.menu)
                    .add()
                    .build();
}
