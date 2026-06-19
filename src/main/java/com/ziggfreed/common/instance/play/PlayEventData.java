package com.ziggfreed.common.instance.play;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The button event the {@link PlayModePage} round-trips: an {@code action}
 * ({@code "pick"} a mode / {@code "leave"} the queue / {@code "close"}) and, for a mode
 * pick, the {@code mode} wire id ({@code "public"}/{@code "party"}/{@code "solo"}).
 */
public class PlayEventData {

    public String action;
    public String mode;

    public static final BuilderCodec<PlayEventData> CODEC =
            BuilderCodec.builder(PlayEventData.class, PlayEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Mode", Codec.STRING),
                            (data, value, info) -> data.mode = value,
                            (data, info) -> data.mode)
                    .add()
                    .build();
}
