package com.ziggfreed.common.commerce.asset;

/**
 * The in-game Asset Editor pick-list ids the commerce codecs declare, so an author picks a currency
 * or a storefront from a list instead of retyping an id.
 *
 * <p><b>A dropdown is authoring convenience, never validation.</b> Hand-written JSON never passes
 * through the editor, so every one of these fields keeps its validator check and a free-typed id
 * still works.
 *
 * <p>The values behind each id are served by the wiring root through
 * {@code asset/EditorDataSets}, which is the only place that can see both the live stores and the
 * editor's request event. Only name a dataset this library actually serves: an unserved id renders
 * an EMPTY pick list, which is worse for an author than a plain text field.
 */
public final class CommerceEditorDataSets {

    /** Every currency id any layer defines. */
    public static final String CURRENCIES = "ziggfreedcommon:currencies";

    /** Every storefront id any layer defines. */
    public static final String SHOPS = "ziggfreedcommon:shops";

    /** Every rotating-shelf id any layer defines. */
    public static final String SHOP_POOLS = "ziggfreedcommon:shop_pools";

    /** Every board id any layer defines. */
    public static final String BOARDS = "ziggfreedcommon:boards";

    /** Every registered selection-strategy id. */
    public static final String SELECTION_TYPES = "ziggfreedcommon:selection_types";

    private CommerceEditorDataSets() {
    }
}
