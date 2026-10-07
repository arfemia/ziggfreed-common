package com.ziggfreed.common.objectives.questlist;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link ZigNpcQuestPage} round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the list named in {@code tab}), {@code select}
 * (open the page on {@code questId}), {@code line} (a page line naming {@code questId}), {@code section} (fold or
 * unfold the list section named in {@code section}), {@code more} (show more of that section's rows), or a press on
 * the page: {@code primary} / {@code secondary} / {@code danger} (its action bar) and {@code toggle} (its header's
 * Track toggle).
 *
 * <p>The presses carry NO quest id on purpose. They act on whatever the page is currently showing, dispatched on that
 * quest's live state, so the same binding survives a partial update that swaps which quest is on the right; a binding
 * on a live element is never added twice.
 */
public class NpcQuestEventData {

    public String action;
    public String questId;
    public String tab;
    public String section;

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
                    .append(new KeyedCodec<>("Section", Codec.STRING),
                            (data, value, info) -> data.section = value,
                            (data, info) -> data.section)
                    .add()
                    .build();
}
