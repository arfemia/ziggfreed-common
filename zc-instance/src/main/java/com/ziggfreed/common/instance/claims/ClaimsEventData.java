package com.ziggfreed.common.instance.claims;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What the Claims page round-trips on a binding: {@code action} is {@code select} (read {@code claim}),
 * {@code claim} (the Claim button, on the Claim being read), {@code claim_all} or {@code close}, or a rail
 * click, which carries {@code menu}. A reward chest's page ({@link RewardChestPage}) round-trips the same data:
 * {@code take} (one line: {@code claim} and {@code reward}), {@code take_all} or {@code close}.
 */
public class ClaimsEventData {

    public String action;
    public String claim;
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the page's own. */
    public String menu;
    /** A reward chest line's reward within {@code claim} ({@link ClaimGrants#rewardKey}); null elsewhere. */
    public String reward;

    public static final BuilderCodec<ClaimsEventData> CODEC =
            BuilderCodec.builder(ClaimsEventData.class, ClaimsEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Claim", Codec.STRING),
                            (data, value, info) -> data.claim = value,
                            (data, info) -> data.claim)
                    .add()
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, value, info) -> data.menu = value,
                            (data, info) -> data.menu)
                    .add()
                    .append(new KeyedCodec<>("Reward", Codec.STRING),
                            (data, value, info) -> data.reward = value,
                            (data, info) -> data.reward)
                    .add()
                    .build();
}
