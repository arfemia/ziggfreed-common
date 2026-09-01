package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.fold.AssetBoardCatalog;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.runtime.ProgressionIconSource;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * What a step about this module's own vocabulary looks like: a WALLET drawn with its own icon, a
 * BOARD drawn with the picture it is listed under wherever boards appear side by side.
 *
 * <p>Both have to be answered here rather than by the generic reading, because neither id is an item
 * id and the engines that list steps have no edge to this module - a wallet backed by no item at all
 * still has an icon, and a board is not a thing a player can hold. So the KIND says what its target
 * names ({@code TargetNames.Currency} / {@code TargetNames.Board} on its file) and this answers the
 * picture, which is the same division the reward chips already use: the kind carries the FACT, the
 * module that owns the vocabulary carries the READING.
 *
 * <p>An id no layer defines answers null and the step falls through to its kind's own fallback
 * picture, because drawing something for a wallet or a board that does not exist on this server is
 * exactly the wrong-picture promise the ladder exists to avoid.
 */
public final class CommerceStepIcons {

    private CommerceStepIcons() {
    }

    /** The reading; the wiring root registers it once at setup. */
    @Nonnull
    public static ProgressionIconSource source() {
        return CommerceStepIcons::iconFor;
    }

    @Nullable
    private static IconSpec iconFor(@Nonnull String contentId, @Nonnull ObjectiveDef objective) {
        String target = objective.target();
        if (target == null || target.isBlank()) {
            return null;
        }
        ObjectiveKind kind = ProgressionRuntime.objectiveKinds().kind(objective.kind());
        if (kind == null) {
            return null;
        }
        String id = target.trim();
        if (kind.targetsCurrency()) {
            return itemOrNull(walletIcon(id));
        }
        if (kind.targetsBoard()) {
            return itemOrNull(boardIcon(id));
        }
        return null;
    }

    @Nullable
    private static String walletIcon(@Nonnull String currencyId) {
        CurrencyDef def = CommerceDefaults.currencyEngine().catalog().get(currencyId);
        return def == null ? null : CurrencyText.iconOf(def);
    }

    @Nullable
    private static String boardIcon(@Nonnull String boardId) {
        BoardAssetSpec spec = AssetBoardCatalog.getInstance().board(boardId);
        return spec == null || spec.asset() == null ? null : spec.asset().getIcon();
    }

    /**
     * An item id as a picture, but only one this server really ships. An authored icon may name
     * something no pack ended up installing, and drawing an id nothing answers to paints the
     * unknown-item picture, which reads as a promise of a thing that does not exist.
     */
    @Nullable
    private static IconSpec itemOrNull(@Nullable String itemId) {
        return ItemIds.exists(itemId) ? IconSpec.ofItem(itemId) : null;
    }
}
