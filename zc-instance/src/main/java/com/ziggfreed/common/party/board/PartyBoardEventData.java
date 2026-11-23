package com.ziggfreed.common.party.board;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The event the party board's page round-trips: an action, the choice it names ({@code Value}: a depth's id, a count,
 * a note's id), the notice a button is on ({@code Notice}, its poster's uuid), and the announce box's live state, which
 * every binding on the post form carries ({@code @Announce}, read client-side from the box), so a repaint keeps it.
 */
public class PartyBoardEventData {

    public String action;
    public String value;
    public String notice;
    public Boolean announce;

    public static final BuilderCodec<PartyBoardEventData> CODEC =
            BuilderCodec.builder(PartyBoardEventData.class, PartyBoardEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Value", Codec.STRING),
                            (data, value, info) -> data.value = value,
                            (data, info) -> data.value)
                    .add()
                    .append(new KeyedCodec<>("Notice", Codec.STRING),
                            (data, value, info) -> data.notice = value,
                            (data, info) -> data.notice)
                    .add()
                    // The "@" prefix makes this a value-capture key: the box's state is resolved client-side.
                    .append(new KeyedCodec<>("@Announce", Codec.BOOLEAN),
                            (data, value, info) -> data.announce = value,
                            (data, info) -> data.announce)
                    .add()
                    .build();
}
