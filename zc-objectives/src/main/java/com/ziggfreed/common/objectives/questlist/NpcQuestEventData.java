package com.ziggfreed.common.objectives.questlist;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link ZigNpcQuestPage} round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the list named in {@code tab}),
 * {@code select} (open the detail panel on {@code questId}), or one of the lifecycle presses
 * {@code accept} / {@code turnIn} / {@code claim} / {@code abandon} / {@code track}.
 *
 * <p>The lifecycle presses carry NO quest id on purpose. They act on whatever the detail panel is
 * currently showing, so the same binding survives a partial update that swaps which quest is on the
 * right - and a page update cannot add or change an event binding, only restyle what is already
 * there.
 */
public class NpcQuestEventData {

    public String action;
    public String questId;
    public String tab;

    public static final BuilderCodec<NpcQuestEventData> CODEC =
            BuilderCodec.builder(NpcQuestEventData.class, NpcQuestEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("QuestId", Codec.STRING),
                            (data, value, info) -> data.questId = value,
                            (data, info) -> data.questId)
                    .add()
                    .append(new KeyedCodec<>("Tab", Codec.STRING),
                            (data, value, info) -> data.tab = value,
                            (data, info) -> data.tab)
                    .add()
                    .build();
}
