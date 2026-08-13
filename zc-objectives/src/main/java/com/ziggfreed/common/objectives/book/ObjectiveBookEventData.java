package com.ziggfreed.common.objectives.book;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the Objective Book page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the tab named in
 * {@code tab}), {@code accept} / {@code claim} / {@code turn_in} (act on the entry named in
 * {@code id}, staying on the tab named in {@code tab}). {@code tab} is {@code quests} or
 * {@code achievements}.
 *
 * <p>The page is stateless across events, so every binding carries the FULL next state and
 * {@link ObjectiveBookPage#handleDataEvent} reopens the page with it.
 */
public class ObjectiveBookEventData {

    public String action;
    public String tab;
    public String id;

    public static final BuilderCodec<ObjectiveBookEventData> CODEC =
            BuilderCodec.builder(ObjectiveBookEventData.class, ObjectiveBookEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Tab", Codec.STRING),
                            (data, value, info) -> data.tab = value,
                            (data, info) -> data.tab)
                    .add()
                    .append(new KeyedCodec<>("Id", Codec.STRING),
                            (data, value, info) -> data.id = value,
                            (data, info) -> data.id)
                    .add()
                    .build();
}
