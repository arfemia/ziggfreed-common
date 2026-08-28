package com.ziggfreed.common.objectives.book;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the Objective Book page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code tab} (switch to the tab named in {@code tab},
 * resetting the other tab's filters), a quest verb ({@code primary} / {@code abandon} /
 * {@code turn_in} / {@code toggletrack} / {@code toggle}, acting on {@code id} and repainting the
 * row named in {@code selector}), an achievement verb ({@code select} / {@code togglepin} /
 * {@code claim} / {@code claim_milestone}), a filter verb ({@code category} / {@code subfilter} /
 * {@code status} / {@code sort} / {@code search} / {@code clear_search} / {@code tag}), or
 * {@code ext} (a consumer-painted control; {@code id} carries the consumer's own token).
 *
 * <p>The FILTER state is stateless across events: every binding carries the full next state, the
 * live search-field text riding {@code @SearchInput} on every one so typed-but-unsubmitted text
 * survives any action. Row expansion and the selected achievement are per-instance UI memory
 * instead (the page threads them into the reopened instance), because a partial update cannot ask
 * the client what it currently shows.
 */
public class ObjectiveBookEventData {

    public String action;
    public String tab;
    public String id;
    public String category;
    public String subcategory;
    public String status;
    public String sort;
    public String search;
    public String tag;
    /** The row selector the click came from, echoed so a partial update can repaint that row. */
    public String selector;
    /** The turn-in objective a quest row's Hand in button names. */
    public String objectiveId;
    /** The points threshold a milestone claim names. */
    public String threshold;
    /** The live dropdown value a {@code ValueChanged} binding captured ({@code @DropdownValue}). */
    public String dropdownValue;
    /** The live search-field value captured at click time ({@code @SearchInput}). */
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
                    .append(new KeyedCodec<>("Subcategory", Codec.STRING),
                            (data, value, info) -> data.subcategory = value,
                            (data, info) -> data.subcategory)
                    .add()
                    .append(new KeyedCodec<>("Status", Codec.STRING),
                            (data, value, info) -> data.status = value,
                            (data, info) -> data.status)
                    .add()
                    .append(new KeyedCodec<>("Sort", Codec.STRING),
                            (data, value, info) -> data.sort = value,
                            (data, info) -> data.sort)
                    .add()
                    .append(new KeyedCodec<>("Search", Codec.STRING),
                            (data, value, info) -> data.search = value,
                            (data, info) -> data.search)
                    .add()
                    .append(new KeyedCodec<>("Tag", Codec.STRING),
                            (data, value, info) -> data.tag = value,
                            (data, info) -> data.tag)
                    .add()
                    .append(new KeyedCodec<>("Selector", Codec.STRING),
                            (data, value, info) -> data.selector = value,
                            (data, info) -> data.selector)
                    .add()
                    .append(new KeyedCodec<>("ObjectiveId", Codec.STRING),
                            (data, value, info) -> data.objectiveId = value,
                            (data, info) -> data.objectiveId)
                    .add()
                    .append(new KeyedCodec<>("Threshold", Codec.STRING),
                            (data, value, info) -> data.threshold = value,
                            (data, info) -> data.threshold)
                    .add()
                    // A binding appends these under an @-directive ("@SearchInput"), and the client
                    // echoes the live value back under that SAME @-prefixed key: a page declaring
                    // only the bare name hears nothing at all, which reads as a dropdown snapping
                    // back to its default the instant it is used. Both spellings are declared onto
                    // the ONE field each, so the page is right whichever key arrives.
                    .append(new KeyedCodec<>("DropdownValue", Codec.STRING),
                            (data, value, info) -> data.dropdownValue = value,
                            (data, info) -> data.dropdownValue)
                    .add()
                    .append(new KeyedCodec<>("@DropdownValue", Codec.STRING),
                            (data, value, info) -> data.dropdownValue = value,
                            (data, info) -> data.dropdownValue)
                    .add()
                    .append(new KeyedCodec<>("@SearchInput", Codec.STRING),
                            (data, value, info) -> data.searchInput = value,
                            (data, info) -> data.searchInput)
                    .add()
                    .append(new KeyedCodec<>("SearchInput", Codec.STRING),
                            (data, value, info) -> data.searchInput = value,
                            (data, info) -> data.searchInput)
                    .add()
                    .build();
}
