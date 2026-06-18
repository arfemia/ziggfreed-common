package com.ziggfreed.common.party.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The click/input event the party page round-trips: an action + an optional target uuid
 * (the player to invite/kick/transfer) + the current search query + the relevant party
 * id (for accept/decline). Mirrors the dialogue page's {@code DialogueEventData} shape.
 */
public class PartyEventData {

    public String action;
    public String target;
    public String query;
    public String partyId;

    public static final BuilderCodec<PartyEventData> CODEC =
            BuilderCodec.builder(PartyEventData.class, PartyEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Target", Codec.STRING),
                            (data, value, info) -> data.target = value,
                            (data, info) -> data.target)
                    .add()
                    // The "@" prefix makes this a value-capture key: the search field's
                    // current text is resolved client-side into this field on the event.
                    .append(new KeyedCodec<>("@Query", Codec.STRING),
                            (data, value, info) -> data.query = value,
                            (data, info) -> data.query)
                    .add()
                    .append(new KeyedCodec<>("PartyId", Codec.STRING),
                            (data, value, info) -> data.partyId = value,
                            (data, info) -> data.partyId)
                    .add()
                    .build();
}
