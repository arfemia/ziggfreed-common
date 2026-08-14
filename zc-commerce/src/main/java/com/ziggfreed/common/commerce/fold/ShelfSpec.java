package com.ziggfreed.common.commerce.fold;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;
import com.ziggfreed.common.shop.ShopShelf;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;

/**
 * One authored rotating shelf, as the purchase engine sees it.
 *
 * <p>The join between an authored {@link ShopPoolAsset} and the {@link ShopShelf} seam, for the same
 * reason {@link ShopEntryOffer} and {@link BoardAssetSpec} exist: the authoring layer may not import
 * an engine type, so the asset cannot answer the seam itself.
 *
 * <p>A VIEW rather than a copy - {@link #asset()} keeps what a draw does not ask about (the title,
 * the storefront it stands in, the order it reads in) - with the four rotating values folded once,
 * at construction, and rebuilt whenever the shelf layer is.
 */
public final class ShelfSpec implements ShopShelf {

    private final ShopPoolAsset asset;
    private final RotationSpec rotation;
    private final SelectionSpec selection;
    private final List<PoolSlot> slots;
    @Nullable private final RerollSpec reroll;

    private ShelfSpec(@Nonnull ShopPoolAsset asset) {
        this.asset = asset;
        String id = asset.getId() == null ? "" : asset.getId();
        this.rotation = CommerceFold.rotation(asset.getRotation(), id);
        this.selection = CommerceFold.selection(asset.getSelection());
        this.slots = CommerceFold.slots(asset.slotsOrEmpty());
        this.reroll = CommerceFold.reroll(asset.getReroll(), id);
    }

    /** The engine view of {@code asset}. */
    @Nonnull
    public static ShelfSpec of(@Nonnull ShopPoolAsset asset) {
        return new ShelfSpec(asset);
    }

    /** What the author wrote, for everything a draw does not ask about. */
    @Nonnull
    public ShopPoolAsset asset() {
        return asset;
    }

    @Override
    @Nonnull
    public String shelfId() {
        return asset.getId() == null ? "" : asset.getId();
    }

    @Override
    @Nonnull
    public RotationSpec rotation() {
        return rotation;
    }

    @Override
    @Nonnull
    public SelectionSpec selection() {
        return selection;
    }

    @Override
    @Nonnull
    public List<PoolSlot> slots() {
        return slots;
    }

    @Override
    @Nullable
    public RerollSpec reroll() {
        return reroll;
    }

    @Override
    public boolean enabled() {
        return asset.isEnabled();
    }

    @Override
    public String toString() {
        return "ShelfSpec[" + shelfId() + "]";
    }
}
