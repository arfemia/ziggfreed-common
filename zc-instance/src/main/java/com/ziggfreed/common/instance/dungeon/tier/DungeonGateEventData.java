package com.ziggfreed.common.instance.dungeon.tier;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** The press a dungeon's gate page round-trips: enter a rung (its dungeon id), or close. */
public class DungeonGateEventData {

    public String action;
    public String dungeon;

    public static final BuilderCodec<DungeonGateEventData> CODEC =
            BuilderCodec.builder(DungeonGateEventData.class, DungeonGateEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Dungeon", Codec.STRING),
                            (data, value, info) -> data.dungeon = value,
                            (data, info) -> data.dungeon)
                    .add()
                    .build();
}
