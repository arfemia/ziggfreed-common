package com.ziggfreed.common.objectives.book;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the Objective Book page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the tab named in
 * {@code tab}), {@code accept} / {@code claim} / {@code turn_in} / {@code abandon} /
 * {@code track} (act on the entry named in {@code id}, staying on the tab named in {@code tab}),
 * or one of the quest-list filter verbs: {@code category} (the category named in
 * {@code category}), {@code status} (the lifecycle filter named in {@code status}),
 * {@code search} (run the text in {@code searchInput}), {@code clear_search}, and
 * {@code tag} (the tag the dropdown picked, in {@code dropdownValue}). {@code tab} is
 * {@code quests} or {@code achievements}.
 *
 * <p>The page is stateless across events, so every binding carries the FULL next state -
 * the filter fields included - and {@link ObjectiveBookPage#handleDataEvent} reopens the page
 * with it.
 */
public class ObjectiveBookEventData {

    public String action;
    public String tab;
    public String id;
    public String category;
    public String status;
    public String search;
    public String tag;
    /** The live dropdown value a {@code ValueChanged} binding captured ({@code @DropdownValue}). */
    public String dropdownValue;
    /** The live search-field value an {@code Activating} binding captured ({@code @SearchInput}). */
    public String searchInput;

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
                    .append(new KeyedCodec<>("Category", Codec.STRING),
                            (data, value, info) -> data.category = value,
                            (data, info) -> data.category)
                    .add()
                    .append(new KeyedCodec<>("Status", Codec.STRING),
                            (data, value, info) -> data.status = value,
                            (data, info) -> data.status)
                    .add()
                    .append(new KeyedCodec<>("Search", Codec.STRING),
                            (data, value, info) -> data.search = value,
                            (data, info) -> data.search)
                    .add()
                    .append(new KeyedCodec<>("Tag", Codec.STRING),
                            (data, value, info) -> data.tag = value,
                            (data, info) -> data.tag)
                    .add()
                    .append(new KeyedCodec<>("DropdownValue", Codec.STRING),
                            (data, value, info) -> data.dropdownValue = value,
                            (data, info) -> data.dropdownValue)
                    .add()
                    .append(new KeyedCodec<>("SearchInput", Codec.STRING),
                            (data, value, info) -> data.searchInput = value,
                            (data, info) -> data.searchInput)
                    .add()
                    .build();
}
