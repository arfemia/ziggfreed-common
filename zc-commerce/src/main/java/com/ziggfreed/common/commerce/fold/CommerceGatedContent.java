package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardSlotAsset;
import com.ziggfreed.common.commerce.page.CommerceLabels;
import com.ziggfreed.common.commerce.page.CommerceText;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * What commerce keeps behind a requirement, for {@link GatedContent}: every switched-on shop offer whose own
 * {@code Requires} asks for something (named by its title, pictured by its icon or else its first reward's
 * picture, placed at its storefront), and every board slot whose {@code Requires} opens one more posting of a
 * band (named by the band, placed at its board). The owner's switches decide what is listed, never a season's
 * feature, so a reputation rank still says what it opens while the stall is shut for the year. Registered by
 * {@link CommerceDefaults#install}, so a server running the library alone has it.
 */
public final class CommerceGatedContent {

    /** The id this source registers under. */
    public static final String ID = "ziggfreedcommon:commerce";

    /** "An extra {0} contract": a gated board slot, its band nested. */
    static final String EXTRA_SLOT_KEY = "ziggfreedcommon.commerce.board.slot.extra";

    private CommerceGatedContent() {
    }

    /** Every gated offer and board slot as the catalogues stand. */
    @Nonnull
    public static List<GatedContent.Entry> entries() {
        List<GatedContent.Entry> out = new ArrayList<>();
        for (ShopEntryOffer offer : AssetShopCatalog.getInstance().offers()) {
            GatedContent.Entry entry = offer(offer);
            if (entry != null) {
                out.add(entry);
            }
        }
        BoardConfig boards = BoardConfig.getInstance();
        for (String id : boards.ids()) {
            BoardAsset board = boards.resolve(id);
            if (board != null && board.isEnabled()) {
                slots(board, out);
            }
        }
        return out;
    }

    @Nullable
    static GatedContent.Entry offer(@Nonnull ShopEntryOffer offer) {
        ShopEntryAsset asset = offer.asset();
        GateSpec lock = asset.lockRequires();
        if (!asset.isEnabled() || asset.isAbstract() || lock == null || lock.isEmpty()) {
            return null;
        }
        StorefrontAsset storefront = asset.getShop() == null ? null
                : ShopConfig.getInstance().resolve(asset.getShop());
        if (storefront != null && !storefront.isEnabled()) {
            return null;
        }
        List<RewardChip> chips = RewardChips.chipsFor(offer.rewards(), null);
        RewardChip first = chips.isEmpty() ? null : chips.get(0);
        Message name = CommerceText.title(asset.getText(), CommerceText.RAW_ARGS,
                first == null ? Msg.raw(offer.offerId()) : first.label());
        String icon = asset.getIcon() != null ? asset.getIcon() : first == null ? null : first.iconItemId();
        // The picture is the item itself only when it is the first reward's own item handed over.
        boolean item = icon != null && first != null && first.showsItem() && icon.equals(first.iconItemId());
        Message place = storefront == null ? null
                : CommerceText.title(storefront.getText(), CommerceText.RAW_ARGS, Msg.raw(storefront.getId()));
        return new GatedContent.Entry(lock, icon, item, name, place);
    }

    /** Each slot of {@code board} that opens one more posting of its band to the players who meet it. */
    static void slots(@Nonnull BoardAsset board, @Nonnull List<GatedContent.Entry> out) {
        Message place = CommerceText.title(board.getText(), CommerceText.RAW_ARGS, Msg.raw(board.getId()));
        for (BoardSlotAsset slot : board.slotsOrEmpty()) {
            GateSpec lock = slot == null ? null : slot.getRequires();
            String band = slot == null ? null : slot.label();
            if (lock == null || lock.isEmpty() || band == null || band.isBlank()) {
                continue;
            }
            Message name = Msg.key(EXTRA_SLOT_KEY, CommerceLabels.grade(board, band, CommerceText.RAW_ARGS));
            out.add(new GatedContent.Entry(lock, board.getIcon(), false, name, place));
        }
    }
}
