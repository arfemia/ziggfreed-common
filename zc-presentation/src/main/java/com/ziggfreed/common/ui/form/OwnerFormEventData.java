package com.ziggfreed.common.ui.form;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import com.ziggfreed.common.ui.kit.FormLeafPainter;

/**
 * What the owner form round-trips: {@code Action} is {@code field} (a control changed, its live value under
 * {@code @Value}), {@code reset}, {@code save}, {@code back} or {@code close}; {@code Field} is the setting's index.
 */
public final class OwnerFormEventData {

    public String action;
    public String field;
    public String value;

    public static final BuilderCodec<OwnerFormEventData> CODEC =
            BuilderCodec.builder(OwnerFormEventData.class, OwnerFormEventData::new)
                    .append(new KeyedCodec<>(OwnerFormPaint.ACTION, Codec.STRING),
                            (data, v, info) -> data.action = v, (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>(OwnerFormPaint.FIELD, Codec.STRING),
                            (data, v, info) -> data.field = v, (data, info) -> data.field)
                    .add()
                    .append(new KeyedCodec<>(FormLeafPainter.VALUE_KEY, Codec.STRING),
                            (data, v, info) -> data.value = v, (data, info) -> data.value)
                    .add()
                    .build();
}
