package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.result.RewardChipRenderer;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.DropRarity;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimKinds;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.icon.ItemQualityLook;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.ContributedLedger;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerContributions;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What the Claims page shows, decided where the page only paints it: the newest Claim first, one row each (its
 * title, how many rewards it holds, its first item's picture or a wallet-only Claim's wallet), then the sections
 * other modules list on the tab ({@link #contributed}), the one being read (its rewards as the library's reward
 * chips, its difficulty, Claim and Claim All), what an empty tab says, the rows of a press's toast
 * ({@link #landedLines}), and a reward chest's one page ({@link #chestDetail}). Nothing here touches a store.
 */
public final class ClaimsView {

    /** The list's one section. */
    static final String SECTION = "waiting";

    /** The reading page's one block. */
    static final String BLOCK_REWARDS = "rewards";

    /** The page's own ids for the two presses, on the action bar's primary and secondary buttons. */
    public static final String ACTION_CLAIM = "claim";
    public static final String ACTION_CLAIM_ALL = "claim_all";

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
        return ledger(claims, text, null);
    }

    /**
     * The Claims' section (a row per Claim in the order given, left out when there is none) and after it every
     * section {@code contributed} lists, named by its source.
     */
    @Nonnull
    public static LedgerModel ledger(@Nonnull List<Claim> claims, @Nonnull ClaimsText text,
            @Nullable ContributedLedger contributed) {
        List<LedgerSection> sections = new ArrayList<>();
        if (!claims.isEmpty() || contributed == null || contributed.isEmpty()) {
            List<LedgerRow> rows = new ArrayList<>(claims.size());
            for (Claim claim : claims) {
                rows.add(new LedgerRow(claim.id(), title(claim, text),
                        text.line("row.rewards", claim.rewards().size()), picture(claim), Tone.COLLECT, null, null,
                        null, Mark.NONE, false));
            }
            sections.add(new LedgerSection(SECTION, text.line("page.section"), rows, true));
        }
        if (contributed != null) {
            sections.addAll(contributed.sections());
        }
        return LedgerModel.of(sections);
    }

    /**
     * What other modules list on the tab for the viewer ({@code LedgerContributions.CLAIMS}: a track's choices not
     * made yet), read once for one build; empty with nobody behind the context.
     */
    @Nonnull
    public static ContributedLedger contributed(@Nullable LedgerContext ctx) {
        return ContributedLedger.read(LedgerContributions.CLAIMS, ctx, CONTRIBUTED_TAG);
    }

    /** What a failing contributed source's line names it by. */
    private static final String CONTRIBUTED_TAG = "[claims] the Claims tab's";

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

    // ==================== a reward chest's page ====================

    /**
     * A reward chest's one page ({@link RewardChestPage}), over {@code claims} in the order given: the chest's picture,
     * "Your share" and how many cards it shows, the difficulty of the first Claim that names one as a pill, the
     * chest's own line ({@code lead}), and one list of cards across every Claim ({@link #chestCards}, a
     * {@link DetailBlock#cards} block): what is not an item first (a currency, experience, reputation; alike ones of
     * one payout kind summed into one card, its own line carrying the amount), then the items, best quality first.
     * Every card names where it came from (its Claim's payout kind, {@link ClaimKinds}) and reads its rarity one way,
     * gold or item: the one its drop's chance reads as ({@link DropRarity}, Common included), else for an item its
     * own, else none. Every card is a click whose select id names its first reward ({@link Taken#selectId}); the gold
     * Take All takes everything, over a hint saying so.
     */
    @Nonnull
    static DetailView chestDetail(@Nonnull List<Claim> claims, @Nonnull ClaimsText text, @Nonnull Message lead) {
        List<ChestCard> cards = chestCards(claims);
        List<DetailLine> lines = new ArrayList<>(cards.size());
        for (ChestCard card : cards) {
            lines.add(card.line());
        }
        String difficulty = null;
        for (Claim claim : claims) {
            if (difficulty == null) {
                difficulty = claim.difficulty();
            }
        }
        List<Pill> badges = difficulty == null ? List.of() : List.of(Pill.of(label(difficulty), Tone.NEUTRAL));
        List<DetailAction> actions = List.of(new DetailAction(ActionSlot.PRIMARY, text.line("spoils.action.take_all"),
                ActionLook.COLLECT, RewardChestActions.TAKE_ALL, null, true, null));
        return new DetailView(Picture.item(ClaimsDestinations.ICON), text.line("spoils.title"),
                text.line("row.rewards", lines.size()), null, badges, null, null, null, lead,
                List.of(new DetailBlock(BLOCK_REWARDS, text.line("detail.rewards"), null, lines, true)), actions,
                text.line("spoils.page.hint"));
    }

    /**
     * One reward of a Claim as a reward chest's card names it: the Claim's id and the reward's key within it
     * ({@link ClaimGrants#rewardKey}), what a card's click sends ({@code Claim} and {@code Reward}) and what
     * {@link ClaimGrants#claimReward} takes.
     */
    record Taken(@Nonnull String claim, @Nonnull String reward) {

        /** Between the two in a select id; neither a Claim id nor a reward key holds it. */
        private static final char SEPARATOR = '\n';

        /** The card's select id, held on the server only (the binding sends the two apart). */
        @Nonnull
        String selectId() {
            return claim + SEPARATOR + reward;
        }

        /** The Claim and reward a card's select id names, or null for one that names none. */
        @Nullable
        static Taken of(@Nullable String selectId) {
            int at = selectId == null ? -1 : selectId.lastIndexOf(SEPARATOR);
            if (at <= 0 || at == selectId.length() - 1) {
                return null;
            }
            return new Taken(selectId.substring(0, at), selectId.substring(at + 1));
        }
    }

    /**
     * One card of a reward chest's page: the reward it shows, every reward its click takes ({@code takes}, in the
     * order the Claims hold them), and the payout kind of the Claims it came from ({@link ClaimKinds}; null for a
     * source no kind names). A run of alike rewards of one kind that are not items shows as one at their summed
     * amount; an item is one card per stack.
     */
    record ChestCard(@Nonnull InstanceReward shown, @Nonnull List<Taken> takes, @Nullable String kind) {

        ChestCard {
            takes = List.copyOf(takes);
        }

        /** This card with {@code reward} added: its amount on the shown one, its take after the others. */
        @Nonnull
        ChestCard plus(@Nonnull InstanceReward reward, @Nonnull Taken take) {
            List<Taken> all = new ArrayList<>(takes);
            all.add(take);
            long sum = (long) shown.quantity() + reward.quantity();
            return new ChestCard(shown.withQuantity((int) Math.min(sum, Integer.MAX_VALUE)), all, kind);
        }

        /**
         * The card as a line: the reward's chip, its own line carrying the amount ("+50 Sweets"), an item's picture
         * keeping its own tooltip and any other picture drawn plain with the chip's words on hover (a borrowed item's
         * tooltip would name the wrong thing), the word for where it came from as its tag (none for no kind), the
         * first take as its select id, and the quality its drop's chance reads as ({@link #cardQuality}).
         */
        @Nonnull
        DetailLine line() {
            RewardChip chip = chip(shown);
            Picture picture = !chip.hasIcon() ? Picture.NONE
                    : shown.isItem() ? Picture.tooltipItem(chip.iconItemId()) : Picture.item(chip.iconItemId());
            Pill from = kind == null ? null : Pill.of(ContentKeys.tr(kind), Tone.NEUTRAL);
            return new DetailLine(picture, chip.label(), null, from, Tick.NONE, takes.get(0).selectId(), false,
                    shown.isItem() ? null : chip.tooltip(), cardQuality(shown));
        }
    }

    /** An item card, ranked once for the sort: its quality's rank and its drop's chance. */
    private record RankedCard(@Nonnull ChestCard card, int rank, double chance) {
    }

    /**
     * Every card a reward chest's page shows for {@code claims} (oldest first): what is not an item first, in the
     * order the Claims hold it, alike rewards ({@link #alike}) from Claims of one payout kind ({@link ClaimKinds})
     * summed into the first one's card, so a boss's Sweets and a dungeon clear's are two cards; then each item stack
     * its own card, best quality first (the quality its drop's chance reads as, {@link DropRarity}, else the item's
     * own; an item this server does not ship last), the rarer drop first within a quality (a stack with no chance
     * after those with one), Claim order kept after that.
     */
    @Nonnull
    static List<ChestCard> chestCards(@Nonnull List<Claim> claims) {
        List<ChestCard> plain = new ArrayList<>();
        Map<String, Integer> byAlike = new HashMap<>();
        List<ChestCard> items = new ArrayList<>();
        for (Claim claim : claims) {
            String kind = ClaimKinds.kindOf(claim);
            for (InstanceReward reward : claim.rewards()) {
                Taken take = new Taken(claim.id(), ClaimGrants.rewardKey(reward));
                if (reward.isItem()) {
                    items.add(new ChestCard(reward, List.of(take), kind));
                    continue;
                }
                String alike = alike(reward);
                String sum = alike == null ? null : Objects.toString(kind, "") + '\n' + alike;
                Integer at = sum == null ? null : byAlike.get(sum);
                if (at != null) {
                    plain.set(at, plain.get(at).plus(reward, take));
                    continue;
                }
                if (sum != null) {
                    byAlike.put(sum, plain.size());
                }
                plain.add(new ChestCard(reward, List.of(take), kind));
            }
        }
        // Each card ranked once, then a stable sort, so stacks of one quality and one chance keep the Claims' order.
        List<RankedCard> ranked = new ArrayList<>(items.size());
        for (ChestCard card : items) {
            Double chance = card.shown().dropChance();
            ranked.add(new RankedCard(card, rankOf(card.shown()), chance == null ? Double.MAX_VALUE : chance));
        }
        ranked.sort(Comparator.comparingInt(RankedCard::rank).reversed().thenComparingDouble(RankedCard::chance));
        List<ChestCard> all = new ArrayList<>(plain.size() + ranked.size());
        all.addAll(plain);
        for (RankedCard card : ranked) {
            all.add(card.card());
        }
        return all;
    }

    /**
     * The quality a reward's card shows ({@link DetailLine#quality}), the one reading every card takes, gold or item:
     * the one its drop's chance reads as ({@link DropRarity}, Common included); null for a reward with no chance,
     * whose card shows an item's own quality and nothing for anything else.
     */
    @Nullable
    static String cardQuality(@Nonnull InstanceReward reward) {
        return DropRarity.qualityOf(reward.dropChance());
    }

    /**
     * The rarest reward in {@code claims} as its drop's chance reads ({@link DropRarity}: the band of the least chance
     * any reward carries), what the chest's opening sound is picked by; null when no reward carries a chance.
     */
    @Nullable
    static String chestRarity(@Nonnull List<Claim> claims) {
        Double least = null;
        for (Claim claim : claims) {
            for (InstanceReward reward : claim.rewards()) {
                Double chance = reward.dropChance();
                if (chance != null && Double.isFinite(chance) && (least == null || chance < least)) {
                    least = chance;
                }
            }
        }
        return DropRarity.qualityOf(least);
    }

    /**
     * An item card's rank, best first: the rank of the quality its card shows ({@link #cardQuality}, else the item's
     * own), {@link ItemQualityLook#UNKNOWN_RANK} for an item this server does not ship, whose card is the plain one.
     */
    private static int rankOf(@Nonnull InstanceReward reward) {
        ItemQualityLook own = ItemQualityLook.of(iconOf(reward));
        if (own == null) {
            return ItemQualityLook.UNKNOWN_RANK;
        }
        ItemQualityLook named = ItemQualityLook.ofQuality(cardQuality(reward));
        return named != null ? named.rank() : own.rank();
    }

    /**
     * What a click on the card naming {@code claim}'s reward {@code reward} takes, read on {@code claims} as they
     * stand now: every reward of that card ({@link #chestCards}), or that one alone when no card names it (a stale
     * page, whose take then finds nothing).
     */
    @Nonnull
    static List<Taken> takesOf(@Nonnull List<Claim> claims, @Nonnull String claim, @Nonnull String reward) {
        Taken clicked = new Taken(claim, reward);
        for (ChestCard card : chestCards(claims)) {
            if (card.takes().contains(clicked)) {
                return card.takes();
            }
        }
        return List.of(clicked);
    }

    /**
     * What makes two rewards that are not items one card: alike in kind, id, display key, icon, the reward a
     * deferred row stands for, all but its amount, and the rarity its card names ({@link #cardQuality}); null for one
     * whose shown amount does not follow its quantity (a command line that pays what it says, a kept reward with no
     * amount to read), which keeps a card of its own.
     */
    @Nullable
    private static String alike(@Nonnull InstanceReward reward) {
        if (reward.isItem() || !reward.scalesWithQuantity()) {
            return null;
        }
        RewardSpec spec = reward.spec();
        String stands = "";
        if (spec != null) {
            String amount = DeferredRewards.quantityParam(spec);
            if (amount == null || spec.longParam(amount, 0L) <= 0L) {
                return null;
            }
            Map<String, String> params = new TreeMap<>(spec.params());
            params.remove(amount);
            stands = spec.kind().toLowerCase(Locale.ROOT) + params;
        }
        return reward.kind().name() + '\n' + reward.id() + '\n' + Objects.toString(reward.displayKey(), "") + '\n'
                + Objects.toString(reward.iconItemId(), "") + '\n' + stands + '\n'
                + Objects.toString(cardQuality(reward), "");
    }

    /** The item a reward's card pictures: its own icon item when it names one, else an item's own id. */
    @Nullable
    private static String iconOf(@Nonnull InstanceReward reward) {
        String icon = reward.iconItemId();
        return icon != null && !icon.isBlank() ? icon : reward.isItem() ? reward.id() : null;
    }

    /**
     * What a reward chest's page shows with nothing of the player's left in it: that they took everything, once the
     * page handed something over ({@code took}), else that nothing in it is theirs.
     */
    @Nonnull
    static EmptyState chestEmpty(@Nonnull ClaimsText text, boolean took) {
        return new EmptyState(Picture.item(ClaimsDestinations.ICON),
                text.line(took ? "spoils.all_taken" : "spoils.empty"), null, null);
    }

    /**
     * The one toast a press raises over its page: everything handed over ({@code all}: everything that was waiting),
     * a full bag keeping the rest, or part of it not payable on this server, over a row for each reward it handed over
     * at what really landed ({@link #landedLines}). Unranked, as the page's own answer to the click, so it always shows
     * and a ranked notice the press raised first waits to come back after it. Null for a press that took nothing (a
     * stale page), whose reopen shows the truth.
     */
    @Nullable
    static ToastSpec pressToast(@Nonnull ClaimGrants.Result result, @Nonnull ClaimsText text, boolean all) {
        return pressToast(result, text, all, false);
    }

    /**
     * {@link #pressToast(ClaimGrants.Result, ClaimsText, boolean)}, or for a summed card's take ({@code summed}) the
     * same toast whose rows add alike rewards into one, with no heading per Claim ({@link #summedLines}): the card
     * named no Claim's title, so neither does its toast.
     */
    @Nullable
    static ToastSpec pressToast(@Nonnull ClaimGrants.Result result, @Nonnull ClaimsText text, boolean all,
            boolean summed) {
        return pressToast(result, text, all, summed, text.line("toast.kept"));
    }

    /**
     * {@link #pressToast(ClaimGrants.Result, ClaimsText, boolean)} for one Claim pressed away from the Claims tab (a
     * dungeon's results screen), where what a full bag keeps does not wait on the page it was pressed on:
     * {@code kept} is the line saying where it waits. Public so that surface tells its press as the tab does.
     */
    @Nullable
    public static ToastSpec pressToast(@Nonnull ClaimGrants.Result result, @Nonnull ClaimsText text,
            @Nonnull Message kept) {
        return pressToast(result, text, false, false, kept);
    }

    /** The one press toast the overloads above word, {@code kept} the line a press a full bag kept part of reads. */
    @Nullable
    private static ToastSpec pressToast(@Nonnull ClaimGrants.Result result, @Nonnull ClaimsText text, boolean all,
            boolean summed, @Nonnull Message kept) {
        if (result.nothing()) {
            return null;
        }
        List<ToastLine> landed = summed ? summedLines(result.collected()) : landedLines(result.collected());
        if (result.anyKept()) {
            return ToastSpec.of(ToastKind.WARNING, kept).withLines(landed);
        }
        if (result.failed() > 0) {
            return ToastSpec.of(ToastKind.WARNING, text.line("toast.failed")).withLines(landed);
        }
        return ToastSpec.of(ToastKind.REWARD, text.line(all ? "toast.claimed_all" : "toast.claimed"))
                .withLines(landed)
                .withSound(ToastSounds.RECEIPT);
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

    /**
     * The rows of a summed card's toast: what its rewards really landed as, alike ones added into one row in the
     * order they first landed, and no heading per Claim. A kind's receipts add up by their amount when alike in kind
     * and every parameter but the amount (a reputation's gain after the worn bonus and the Cap); a row that paid by
     * itself adds up at its own quantity when alike as the card's rewards are ({@link #alike}). Anything else keeps a
     * row of its own; a receipt listing nothing adds none.
     */
    @Nonnull
    static List<ToastLine> summedLines(@Nonnull List<ClaimGrants.Collected> collected) {
        Map<String, Object> sums = new LinkedHashMap<>();
        int own = 0;
        for (ClaimGrants.Collected each : collected) {
            for (InstanceRewardGranter.Landed landed : each.landed()) {
                if (landed.receipted()) {
                    for (RewardSpec spec : landed.receipt()) {
                        String alike = alikeReceipt(spec);
                        String slot = alike == null ? "own " + own++ : "receipt " + alike;
                        Object held = sums.get(slot);
                        sums.put(slot, held instanceof RewardSpec sum ? summedReceipt(sum, spec) : spec);
                    }
                } else {
                    InstanceReward row = landed.row();
                    String alike = alike(row);
                    String slot = alike == null ? "own " + own++ : "row " + alike;
                    Object held = sums.get(slot);
                    if (held instanceof InstanceReward sum) {
                        long total = (long) sum.quantity() + row.quantity();
                        sums.put(slot, sum.withQuantity((int) Math.min(total, Integer.MAX_VALUE)));
                    } else {
                        sums.put(slot, row);
                    }
                }
            }
        }
        List<ToastLine> lines = new ArrayList<>(sums.size());
        for (Object sum : sums.values()) {
            lines.add(sum instanceof RewardSpec spec ? receiptRow(spec)
                    : RewardToastLines.row(chip((InstanceReward) sum)));
        }
        return lines;
    }

    /** What makes two receipts one row: kind, which parameter is the amount, and every other parameter; null for none. */
    @Nullable
    private static String alikeReceipt(@Nonnull RewardSpec spec) {
        String amount = DeferredRewards.quantityParam(spec);
        if (amount == null) {
            return null;
        }
        Map<String, String> params = new TreeMap<>(spec.params());
        params.remove(amount);
        return spec.kind().toLowerCase(Locale.ROOT) + '\n' + amount + '\n' + params;
    }

    /** {@code sum} with {@code more}'s amount added to its own (the two alike, {@link #alikeReceipt}). */
    @Nonnull
    private static RewardSpec summedReceipt(@Nonnull RewardSpec sum, @Nonnull RewardSpec more) {
        String amount = DeferredRewards.quantityParam(sum);
        return amount == null ? sum
                : sum.with(amount, Long.toString(sum.longParam(amount, 0L) + more.longParam(amount, 0L)));
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
