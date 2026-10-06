package com.ziggfreed.common.objectives.book;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What one of the Objective Book's bindings sends back: the action, its payload, and the whole book state
 * ({@link BookState#event}, decoded by {@link BookState#decode}).
 *
 * <p>{@code action} is one of {@link BookActions#ALL}. The state keys are {@code Tab}, {@code View},
 * {@code Category}, {@code Status}, {@code Sort}, {@code Search}, {@code Tag}, {@code Selected} and
 * {@code OpenSections}; the payload keys are {@code Id} (the row an action names, or a consumer's own token on
 * {@code ext}), {@code Section} with {@code Open} (a section toggle), {@code ObjectiveId}, {@code Threshold},
 * the live {@code @DropdownValue} and the live {@code @SearchInput}, which rides every binding so text typed
 * and not yet searched survives any click. A rail click carries {@code menu} and no action, and is the shared
 * menu's. Every key the pre-redesign book's bindings sent is still declared ({@code Subcategory} and
 * {@code Selector} among them), so an event from one decodes.
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
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the book's own. */
    public String menu;
    /** The view the binding's state was on ({@link BookState#view}). */
    public String view;
    /** The selected row in the binding's state ({@link BookState#selectedId}). */
    public String selected;
    /** The sections opened or closed by hand in the binding's state, comma-joined ({@link BookState#openSections}). */
    public String openSections;
    /** A section toggle's section id. */
    public String section;
    /** A section toggle's ask: {@code "true"} opens the section, {@code "false"} closes it. */
    public String open;

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
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, value, info) -> data.menu = value,
                            (data, info) -> data.menu)
                    .add()
                    .append(new KeyedCodec<>(BookState.KEY_VIEW, Codec.STRING),
                            (data, value, info) -> data.view = value,
                            (data, info) -> data.view)
                    .add()
                    .append(new KeyedCodec<>(BookState.KEY_SELECTED, Codec.STRING),
                            (data, value, info) -> data.selected = value,
                            (data, info) -> data.selected)
                    .add()
                    .append(new KeyedCodec<>(BookState.KEY_OPEN_SECTIONS, Codec.STRING),
                            (data, value, info) -> data.openSections = value,
                            (data, info) -> data.openSections)
                    .add()
                    .append(new KeyedCodec<>(BookState.KEY_SECTION, Codec.STRING),
                            (data, value, info) -> data.section = value,
                            (data, info) -> data.section)
                    .add()
                    .append(new KeyedCodec<>(BookState.KEY_OPEN, Codec.STRING),
                            (data, value, info) -> data.open = value,
                            (data, info) -> data.open)
                    .add()
                    .build();
}
