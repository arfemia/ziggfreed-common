package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.result.RewardChipRenderer;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What the Claims page shows, decided where the page only paints it: the newest Claim first, one row each (its
 * title, how many rewards it holds, its first item's picture or a wallet-only Claim's wallet), the one being read
 * (its rewards as the library's reward chips, its difficulty, Claim and Claim All), and what an empty tab says.
 * Nothing here touches a store.
 */
public final class ClaimsView {

    /** The list's one section. */
    static final String SECTION = "waiting";

    /** The reading page's one block. */
    static final String BLOCK_REWARDS = "rewards";

    /** The page's own ids for the two presses, on the action bar's primary and secondary buttons. */
    public static final String ACTION_CLAIM = "claim";
    public static final String ACTION_CLAIM_ALL = "claim_all";

    private ClaimsView() {
    }

    /** {@code claims} (as the store lists them, oldest first) newest first. */
    @Nonnull
    public static List<Claim> newestFirst(@Nonnull List<Claim> claims) {
        List<Claim> out = new ArrayList<>(claims);
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return List.copyOf(out);
    }

    /** The Claim named {@code selectedId} (any case), else the first; null when there are none. */
    @Nullable
    public static Claim pick(@Nonnull List<Claim> claims, @Nullable String selectedId) {
        if (claims.isEmpty()) {
            return null;
        }
        for (Claim claim : claims) {
            if (claim.is(selectedId)) {
                return claim;
            }
        }
        return claims.get(0);
    }

    // ==================== the list ====================

    /** One section, a row per Claim in the order given. */
    @Nonnull
    public static LedgerModel ledger(@Nonnull List<Claim> claims, @Nonnull ClaimsText text) {
        List<LedgerRow> rows = new ArrayList<>(claims.size());
        for (Claim claim : claims) {
            rows.add(new LedgerRow(claim.id(), title(claim, text), text.line("row.rewards", claim.rewards().size()),
                    picture(claim), Tone.COLLECT, null, null, null, Mark.NONE, false));
        }
        return LedgerModel.of(List.of(new LedgerSection(SECTION, text.line("page.section"), rows, true)));
    }

    // ==================== the reading page ====================

    /**
     * The reading page of {@code claim}: its picture and title, its difficulty as a pill, every reward as a chip
     * line, Claim (gold) and Claim All, and the line saying a full bag keeps the rest here.
     */
    @Nonnull
    public static DetailView detail(@Nonnull Claim claim, @Nonnull ClaimsText text) {
        List<DetailLine> lines = new ArrayList<>(claim.rewards().size());
        for (InstanceReward reward : claim.rewards()) {
            lines.add(DetailLine.reward(chip(reward), null));
        }
        List<Pill> badges = claim.difficulty() == null ? List.of()
                : List.of(Pill.of(label(claim.difficulty()), Tone.NEUTRAL));
        List<DetailAction> actions = List.of(
                new DetailAction(ActionSlot.PRIMARY, text.line("action.claim"), ActionLook.COLLECT, ACTION_CLAIM,
                        null, true, null),
                new DetailAction(ActionSlot.SECONDARY, text.line("action.claim_all"), ActionLook.NORMAL,
                        ACTION_CLAIM_ALL, null, true, null));
        return new DetailView(picture(claim), title(claim, text), null, null, badges, null, null, null,
                text.line("detail.lead"),
                List.of(new DetailBlock(BLOCK_REWARDS, text.line("detail.rewards"),
                        text.line("row.rewards", claim.rewards().size()), lines)),
                actions, text.line("detail.hint"));
    }

    /** What the tab shows with nothing waiting. */
    @Nonnull
    public static EmptyState empty(@Nonnull ClaimsText text) {
        return new EmptyState(Picture.item(ClaimsDestinations.ICON), text.line("empty.title"), text.line("empty.line"),
                null);
    }

    /** A Claim's title: its own key with its arguments, else the tab's word for a reward. */
    @Nonnull
    public static Message title(@Nonnull Claim claim, @Nonnull ClaimsText text) {
        Message own = ownTitle(claim);
        return own == null ? text.line("untitled") : own;
    }

    /** A Claim's own title, its key with its arguments; null when it names none. */
    @Nullable
    public static Message ownTitle(@Nonnull Claim claim) {
        return claim.titleKey() == null ? null : ContentKeys.tr(claim.titleKey(), claim.titleArgs().toArray());
    }

    /**
     * One reward as the library's reward chip, read through {@link RewardChipRenderer#toChip}: its own label, the
     * reward it was deferred from at its own (possibly scaled) quantity, an item's name, else the generic line.
     * Never its id, which for a command row is the console line that pays it. Public so every surface that shows
     * a Claim's rewards (the Claims tab, a dungeon's results screen) draws them alike.
     */
    @Nonnull
    public static RewardChip chip(@Nonnull InstanceReward reward) {
        try {
            return RewardChipRenderer.toChip(reward, (key, quantity) -> ContentKeys.tr(key, quantity));
        } catch (Throwable t) {
            return RewardChip.text(RewardChipRenderer.genericLabel(reward.quantity()));
        }
    }

    /**
     * The first item reward's own picture; for a Claim of wallets alone, its first chip's (the wallet's own, as
     * the reading page draws it); else the tab's chest.
     */
    @Nonnull
    static Picture picture(@Nonnull Claim claim) {
        List<InstanceReward> rewards = claim.rewards();
        for (InstanceReward reward : rewards) {
            String icon = reward.iconItemId() != null ? reward.iconItemId() : reward.isItem() ? reward.id() : null;
            if (icon != null && !icon.isBlank()) {
                return Picture.item(icon);
            }
        }
        if (!rewards.isEmpty() && rewards.stream().allMatch(RewardChipRenderer::isWallet)) {
            String icon = chip(rewards.get(0)).iconItemId();
            if (icon != null && !icon.isBlank()) {
                return Picture.item(icon);
            }
        }
        return Picture.item(ClaimsDestinations.ICON);
    }

    /** A difficulty: its lang key when the catalogue ships it, else the label as written. */
    @Nonnull
    private static Message label(@Nonnull String difficulty) {
        return ContentKeys.known(difficulty) ? ContentKeys.tr(difficulty) : Msg.raw(difficulty);
    }
}
