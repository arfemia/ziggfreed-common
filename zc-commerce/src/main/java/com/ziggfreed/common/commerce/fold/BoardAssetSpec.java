package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BoardSpec;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardSlotAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * One authored board, as the board engine sees it.
 *
 * <p>The join between an authored {@link BoardAsset} and the {@link BoardSpec} seam, for the same
 * reason {@link ShopEntryOffer} exists: the authoring layer may not import an engine type, so the
 * asset cannot answer the seam itself.
 *
 * <p>A VIEW rather than a copy - {@link #asset()} keeps what a draw does not ask about (the title,
 * the icon, the ordering, the worlds it exists in) - with the four rotating values folded once, at
 * construction, and rebuilt whenever the board layer is.
 */
public final class BoardAssetSpec implements BoardSpec {

    private final BoardAsset asset;
    private final RotationSpec rotation;
    private final SelectionSpec selection;
    private final List<PoolSlot> slots;
    private final List<GateSpec> slotGates;
    @Nullable private final RerollSpec reroll;

    private BoardAssetSpec(@Nonnull BoardAsset asset) {
        this.asset = asset;
        String id = asset.getId() == null ? "" : asset.getId();
        this.rotation = CommerceFold.rotation(asset.getRotation(), id);
        this.selection = CommerceFold.selection(asset.getSelection());
        this.slots = CommerceFold.slots(asset.slotsOrEmpty());
        this.slotGates = slotGates(asset.slotsOrEmpty());
        this.reroll = CommerceFold.reroll(asset.getReroll(), id);
    }

    /** The engine view of {@code asset}. */
    @Nonnull
    public static BoardAssetSpec of(@Nonnull BoardAsset asset) {
        return new BoardAssetSpec(asset);
    }

    /** What the author wrote, for everything a draw does not ask about. */
    @Nonnull
    public BoardAsset asset() {
        return asset;
    }

    @Override
    @Nonnull
    public String boardId() {
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
    @Nonnull
    public Map<String, GateSpec> acceptRequires() {
        return asset.acceptRequires();
    }

    @Override
    @Nullable
    public GateSpec slotRequires(int slotIndex) {
        return slotIndex >= 0 && slotIndex < slotGates.size() ? slotGates.get(slotIndex) : null;
    }

    @Override
    @Nullable
    public String gradeColor(@Nonnull String band) {
        String authored = asset.gradeColor(band);
        // Clamped the way every authored data accent is: kept when it reads at 3:1 on a row, else the
        // shared accent.
        return authored == null ? null : ZigTokens.clampAccent(authored);
    }

    @Override
    @Nullable
    public GateSpec requires() {
        // The lock only: a feature that decides whether the board exists is never an accept reason.
        return asset.lockRequires();
    }

    @Override
    public boolean enabled() {
        // Live: switched off, or hidden by a feature that reads off, and the engine refuses every accept.
        return asset.isAvailable();
    }

    @Override
    @Nonnull
    public Collection<String> currencies() {
        return asset.currencyIds();
    }

    @Override
    public String toString() {
        return "BoardAssetSpec[" + boardId() + "]";
    }

    /** Each authored slot's gate, null slots skipped exactly as {@code CommerceFold.slots} skips them. */
    @Nonnull
    private static List<GateSpec> slotGates(@Nonnull BoardSlotAsset[] authored) {
        List<GateSpec> out = new ArrayList<>(authored.length);
        for (BoardSlotAsset slot : authored) {
            if (slot != null) {
                out.add(slot.getRequires());
            }
        }
        // Not List.copyOf: an open slot's gate is null, and the list keeps it in place.
        return Collections.unmodifiableList(out);
    }
}
