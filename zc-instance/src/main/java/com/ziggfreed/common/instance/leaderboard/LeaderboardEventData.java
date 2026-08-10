package com.ziggfreed.common.instance.leaderboard;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state event the leaderboard page round-trips. {@code action} is one of
 * {@code group} (primary/difficulty switch, value in {@code group}), {@code tab}
 * (secondary/size switch, value in {@code bucket}), {@code sort} (rank metric, value in
 * {@code sort}), {@code statsort} (Stats-view column metric, value in {@code statSort}),
 * {@code view} (rankings/stats, value in {@code view}), or {@code close}. The Rankings sort
 * ({@code sort}) and the Stats sort ({@code statSort}) are carried independently on every
 * binding so both views' sort states survive a round-trip.
 */
public class LeaderboardEventData {

    public String action;
    public String bucket;
    public String group;
    public String sort;
    public String statSort;
    public String view;

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
                    .append(new KeyedCodec<>("Group", Codec.STRING),
                            (data, value, info) -> data.group = value,
                            (data, info) -> data.group)
                    .add()
                    .append(new KeyedCodec<>("Sort", Codec.STRING),
                            (data, value, info) -> data.sort = value,
                            (data, info) -> data.sort)
                    .add()
                    .append(new KeyedCodec<>("StatSort", Codec.STRING),
                            (data, value, info) -> data.statSort = value,
                            (data, info) -> data.statSort)
                    .add()
                    .append(new KeyedCodec<>("View", Codec.STRING),
                            (data, value, info) -> data.view = value,
                            (data, info) -> data.view)
                    .add()
                    .build();
}
