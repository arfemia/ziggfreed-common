package com.ziggfreed.common.commerce.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link ZigBoardPage} round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the list named in {@code tab}),
 * {@code select} (open the detail panel on {@code bountyId}), or one of the lifecycle presses
 * {@code accept} / {@code turnIn} / {@code claim} / {@code abandon} / {@code reroll}.
 *
 * <p>The lifecycle presses carry NO id on purpose, except the reroll's position. They act on
 * whatever the detail panel is currently showing, so the same binding survives a partial update that
 * swaps which contract is on the right - and a page update cannot add or change an event binding,
 * only restyle what is already there.
 *
 * <p><b>The position is re-resolved server-side before anything is charged.</b> A client-sent index
 * says which button was pressed; the draw the page just made says which contract sits there.
 */
public class BoardEventData {

    public String action;
    public String bountyId;
    public String tab;
    public Integer position;

    public static final BuilderCodec<BoardEventData> CODEC =
            BuilderCodec.builder(BoardEventData.class, BoardEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("BountyId", Codec.STRING),
                            (data, value, info) -> data.bountyId = value,
                            (data, info) -> data.bountyId)
                    .add()
                    .append(new KeyedCodec<>("Tab", Codec.STRING),
                            (data, value, info) -> data.tab = value,
                            (data, info) -> data.tab)
                    .add()
                    .append(new KeyedCodec<>("Position", Codec.INTEGER),
                            (data, value, info) -> data.position = value,
                            (data, info) -> data.position)
                    .add()
                    .build();
}
