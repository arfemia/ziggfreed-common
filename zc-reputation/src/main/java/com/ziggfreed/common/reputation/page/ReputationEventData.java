package com.ziggfreed.common.reputation.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What the Reputation page round-trips on a binding: {@code action} is {@code select} (open the page on
 * {@code reputation}) or {@code close}, or a rail click, which carries {@code menu}.
 */
public class ReputationEventData {

    public String action;
    public String reputation;
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the page's own. */
    public String menu;

    public static final BuilderCodec<ReputationEventData> CODEC =
            BuilderCodec.builder(ReputationEventData.class, ReputationEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Reputation", Codec.STRING),
                            (data, value, info) -> data.reputation = value,
                            (data, info) -> data.reputation)
                    .add()
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, value, info) -> data.menu = value,
                            (data, info) -> data.menu)
                    .add()
                    .build();
}
