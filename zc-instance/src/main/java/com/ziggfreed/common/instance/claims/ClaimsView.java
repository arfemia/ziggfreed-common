package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.result.RewardChipRenderer;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
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
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastLine;

/**
 * What the Claims page shows, decided where the page only paints it: the newest Claim first, one row each (its
 * title, how many rewards it holds, its first item's picture or a wallet-only Claim's wallet), the one being read
 * (its rewards as the library's reward chips, its difficulty, Claim and Claim All), what an empty tab says, and the
 * rows of a press's toast ({@link #landedLines}). Nothing here touches a store.
 */
public final class ClaimsView {

    /** The list's one section. */
    static final String SECTION = "waiting";

    /** The reading page's one block. */
    static final String BLOCK_REWARDS = "rewards";

    /** The page's own ids for the two presses, on the action bar's primary and secondary buttons. */
    public static final String ACTION_CLAIM = "claim";
    public static final String ACTION_CLAIM_ALL = "claim_all";

    /** The chest's first screen's one button once only items are left: open the chest's item window. */
    public static final String ACTION_ITEMS = "items";

    /**
     * The kind a press's toast files its own entries under ({@link #landedLines}): a Claim's heading and a row that
     * paid by itself. Never paid and never read as a reward; only its place in the list counts.
     */
    private static final String TOAST_ENTRY = "Claims_Toast_Entry";

    /** The last row of a toast whose rewards ran past its rows, {@code {0}} how many more: zc-presentation's line. */
    private static final String TOAST_MORE_KEY = "ziggfreedcommon.feedback.rewards.more";

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
        return detail(claim, text, false);
    }

    /**
     * {@link #detail(Claim, ClaimsText)}, or on a spoils chest ({@code spoils}) the same page whose secondary button
     * is Take All, taking only the fight's Claims, and whose lines say a Claim left behind waits under Claims.
     */
    @Nonnull
    public static DetailView detail(@Nonnull Claim claim, @Nonnull ClaimsText text, boolean spoils) {
        List<DetailLine> lines = new ArrayList<>(claim.rewards().size());
        for (InstanceReward reward : claim.rewards()) {
            lines.add(DetailLine.reward(chip(reward), null));
        }
        List<Pill> badges = claim.difficulty() == null ? List.of()
                : List.of(Pill.of(label(claim.difficulty()), Tone.NEUTRAL));
        List<DetailAction> actions = List.of(
                new DetailAction(ActionSlot.PRIMARY, text.line("action.claim"), ActionLook.COLLECT, ACTION_CLAIM,
                        null, true, null),
                new DetailAction(ActionSlot.SECONDARY, text.line(spoils ? "spoils.action.take_all" : "action.claim_all"),
                        ActionLook.NORMAL, ACTION_CLAIM_ALL, null, true, null));
        return new DetailView(picture(claim), title(claim, text), null, null, badges, null, null, null,
                text.line(spoils ? "spoils.detail.lead" : "detail.lead"),
                List.of(new DetailBlock(BLOCK_REWARDS, text.line("detail.rewards"),
                        text.line("row.rewards", claim.rewards().size()), lines)),
                actions, text.line(spoils ? "spoils.detail.hint" : "detail.hint"));
    }

    /** What the tab shows with nothing waiting. */
    @Nonnull
    public static EmptyState empty(@Nonnull ClaimsText text) {
        return new EmptyState(Picture.item(ClaimsDestinations.ICON), text.line("empty.title"), text.line("empty.line"),
                null);
    }

    /** What a spoils chest shows a player with nothing from its fight waiting: one line saying so. */
    @Nonnull
    public static EmptyState spoilsEmpty(@Nonnull ClaimsText text) {
        return new EmptyState(Picture.item(ClaimsDestinations.ICON), text.line("spoils.empty"), null, null);
    }

    /**
     * What a chest's first screen shows once only items are left: a line saying the rest is in the chest, and the
     * button that opens its item window ({@link #ACTION_ITEMS}).
     */
    @Nonnull
    public static EmptyState spoilsItemsLeft(@Nonnull ClaimsText text) {
        return new EmptyState(Picture.item(ClaimsDestinations.ICON), text.line("spoils.items_left"), null,
                new DetailAction(ActionSlot.PRIMARY, text.line("spoils.action.items"), ActionLook.COLLECT,
                        ACTION_ITEMS, null, true, null));
    }

    /** A Claim's title: its own key with its arguments, else the tab's word for a reward. */
    @Nonnull
    public static Message title(@Nonnull Claim claim, @Nonnull ClaimsText text) {
        Message own = ownTitle(claim);
        return own == null ? text.line("untitled") : own;
    }

    /**
     * A Claim's own title, its key with its arguments ({@link Claim#titleParams}: a key or an item argument read in
     * the reader's own language); null when it names none.
     */
    @Nullable
    public static Message ownTitle(@Nonnull Claim claim) {
        return claim.titleKey() == null ? null : ContentKeys.tr(claim.titleKey(), claim.titleParams());
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

    // ==================== a press's toast ====================

    /**
     * The rows of a press's toast: every reward it handed over, at what really landed. A row its kind paid reads that
     * kind's receipt through the shared reward reading ({@link RewardChips#chipFor(RewardSpec)}), so a reputation
     * shows the gain that moved after the worn gear and the Cap ("+60 Old Jack's Favor" for an authored 50), and a
     * receipt listing nothing (a gain the Cap cut to nothing) adds no row. A row that paid by itself (an item into
     * the bag, a currency through the sink, a line run) reads as its chip on the page did ({@link #chip}), at its own
     * quantity. Several Claims each take a section under their own title through {@link RewardToastLines}' one
     * sectioning step, a Claim alone reads flat, and a Claim with no title of its own lists its rows plain among the
     * first; past the toast's rows the last one says how many more.
     */
    @Nonnull
    public static List<ToastLine> landedLines(@Nonnull List<ClaimGrants.Collected> collected) {
        List<RewardSpec> rows = new ArrayList<>();
        Map<RewardSpec, ToastLine> own = new IdentityHashMap<>();
        for (ClaimGrants.Collected each : collected) {
            RewardSpec heading = heading(each.claim(), own);
            for (InstanceRewardGranter.Landed landed : each.landed()) {
                if (landed.receipted()) {
                    for (RewardSpec spec : landed.receipt()) {
                        rows.add(spec.withOrigin(heading));
                    }
                } else {
                    RewardSpec entry = RewardSpec.of(TOAST_ENTRY).withOrigin(heading);
                    own.put(entry, RewardToastLines.row(chip(landed.row())));
                    rows.add(entry);
                }
            }
        }
        return RewardToastLines.linesBy(rows, spec -> own.containsKey(spec) ? own.get(spec) : receiptRow(spec),
                dropped -> Msg.key(TOAST_MORE_KEY, dropped));
    }

    /** The section heading entry for {@code claim}, its own title, or null when it has none (its rows read plain). */
    @Nullable
    private static RewardSpec heading(@Nonnull Claim claim, @Nonnull Map<RewardSpec, ToastLine> own) {
        Message title = ownTitle(claim);
        if (title == null) {
            return null;
        }
        RewardSpec heading = RewardSpec.of(TOAST_ENTRY);
        own.put(heading, ToastLine.text(title));
        return heading;
    }

    /**
     * One receipt row as a toast row: the shared reading at what landed, else the generic line at that amount, so a
     * reward that landed always shows.
     */
    @Nonnull
    private static ToastLine receiptRow(@Nonnull RewardSpec spec) {
        RewardChip chip;
        try {
            chip = RewardChips.chipFor(spec);
        } catch (Throwable t) {
            chip = null;
        }
        if (chip != null) {
            return RewardToastLines.row(chip);
        }
        return ToastLine.text(RewardChipRenderer.genericLabel((int) Math.min(RewardChips.amountOf(spec),
                Integer.MAX_VALUE)));
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
