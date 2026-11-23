package com.ziggfreed.common.objectives.admin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import com.ziggfreed.common.ui.kit.FormLeafPainter;

/**
 * What the Kill Drops admin page round-trips: {@code Action} is {@code field} (a setting's control changed, its live
 * value under {@code @Value}), {@code reset}, {@code save}, {@code page} (show the page {@code Page} names),
 * {@code back} or {@code close}; {@code Field} is the setting's index on the page shown.
 */
public final class KillDropsAdminEventData {

    public String action;
    public String field;
    public String value;
    public String page;

    public static final BuilderCodec<KillDropsAdminEventData> CODEC =
            BuilderCodec.builder(KillDropsAdminEventData.class, KillDropsAdminEventData::new)
                    .append(new KeyedCodec<>(KillDropsAdminPage.ACTION, Codec.STRING),
                            (data, v, info) -> data.action = v, (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>(KillDropsAdminPage.FIELD, Codec.STRING),
                            (data, v, info) -> data.field = v, (data, info) -> data.field)
                    .add()
                    .append(new KeyedCodec<>(FormLeafPainter.VALUE_KEY, Codec.STRING),
                            (data, v, info) -> data.value = v, (data, info) -> data.value)
                    .add()
                    .append(new KeyedCodec<>(KillDropsAdminPage.PAGE, Codec.STRING),
                            (data, v, info) -> data.page = v, (data, info) -> data.page)
                    .add()
                    .build();
}
