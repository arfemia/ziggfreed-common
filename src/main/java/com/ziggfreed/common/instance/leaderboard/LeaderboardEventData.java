package com.ziggfreed.common.instance.leaderboard;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The tab-switch event the leaderboard page round-trips: action + the bucket key. */
public class LeaderboardEventData {

    public String action;
    public String bucket;

    public static final BuilderCodec<LeaderboardEventData> CODEC =
            BuilderCodec.builder(LeaderboardEventData.class, LeaderboardEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Bucket", Codec.STRING),
                            (data, value, info) -> data.bucket = value,
                            (data, info) -> data.bucket)
                    .add()
                    .build();
}
