package com.ziggfreed.common.commerce.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link ZigShopPage} round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code select} (open the detail panel on
 * {@code offerId}), {@code buy}, or {@code reroll}.
 *
 * <p><b>{@code position} is a POSITION, and the page never trusts it as one.</b> A reroll names the
 * shelf and the slot it is about, and the page re-resolves that slot from the draw it just made
 * server-side before charging anything - a client-sent index decides which button was pressed, never
 * which contract is swapped.
 */
public class ShopEventData {

    public String action;
    public String offerId;
    public String shelfId;
    public Integer position;

    public static final BuilderCodec<ShopEventData> CODEC =
            BuilderCodec.builder(ShopEventData.class, ShopEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("OfferId", Codec.STRING),
                            (data, value, info) -> data.offerId = value,
                            (data, info) -> data.offerId)
                    .add()
                    .append(new KeyedCodec<>("ShelfId", Codec.STRING),
                            (data, value, info) -> data.shelfId = value,
                            (data, info) -> data.shelfId)
                    .add()
                    .append(new KeyedCodec<>("Position", Codec.INTEGER),
                            (data, value, info) -> data.position = value,
                            (data, info) -> data.position)
                    .add()
                    .build();
}
