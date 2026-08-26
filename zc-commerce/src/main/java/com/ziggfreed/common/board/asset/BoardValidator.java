package com.ziggfreed.common.board.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.shop.asset.ShopValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WhereValidator;

/**
 * Audits folded board content for the mistakes that produce NO error at runtime: a contract no board
 * can post, a slot no contract can fill, a band gated on something nothing answers, a reward nothing
 * pays out. Every one of them ships as a board that quietly does not post what its author read.
 *
 * <p>Findings are shared {@link Finding} values under domain {@code board}. Gate findings come from
 * the SHARED {@link GateValidator} and world-targeting findings from the shared
 * {@link WhereValidator}, so a lock or a selector reads here exactly as it does on a quest.
 *
 * <p><b>Every unknown id is a WARNING, never an error</b> - the standing library rule. A thing that
 * is impossible whatever anybody installs - a board with no contracts, a slot no band can fill - is
 * an error.
 */
public final class BoardValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "board";

    /** What one piece of this content is CALLED in a message written for the author. */
    private static final String NOUN = "contract";

    private BoardValidator() {
    }

    /**
     * Audit every board and every contract against each other and against the vocabularies a caller
     * can answer for.
     *
     * <p>Every vocabulary is optional: pass null for one and the checks that depend on it are
     * skipped rather than reporting everything as unknown.
     *
     * @param boards        the boards any layer defines, keyed by id
     * @param bounties      the loaded contracts, keyed by id (skeletons included, and skipped)
     * @param currencies    answers "does this wallet exist?", or null to skip
     * @param rewardKinds   answers "does anything pay this reward kind out?", or null to skip
     * @param objectiveKinds answers "does anything ever fire this kind of moment?", or null to skip
     * @param gateKinds     the registered {@code Requires.Custom} vocabulary, or null to skip
     * @param knownFactors  answers "does anything provide this factor id?", or null to skip
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull Map<String, BoardAsset> boards,
            @Nonnull Map<String, BountyAsset> bounties,
            @Nullable ShopValidator.CurrencyProbe currencies, @Nullable Predicate<String> rewardKinds,
            @Nullable Predicate<String> objectiveKinds, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownFactors) {

        List<Finding> out = new ArrayList<>();

        // Which contracts each board could ever post, counted by band.
        Map<String, Map<String, Integer>> byBoardAndBand = new LinkedHashMap<>();
        Map<String, Integer> byBoard = new LinkedHashMap<>();
        for (Map.Entry<String, BountyAsset> entry : bounties.entrySet()) {
            BountyAsset bounty = entry.getValue();
            if (bounty == null || bounty.isAbstract() || !bounty.isEnabled()) {
                continue;
            }
            for (BountyAsset.BoardMembership membership : bounty.boardMemberships()) {
                String boardId = membership.getBoard();
                if (boardId == null) {
                    continue;
                }
                byBoard.merge(boardId, 1, Integer::sum);
                String band = membership.getDifficulty();
                if (band != null) {
                    byBoardAndBand.computeIfAbsent(boardId, key -> new LinkedHashMap<>())
                            .merge(band, 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<String, BoardAsset> entry : boards.entrySet()) {
            if (entry.getValue() != null) {
                validateBoard(entry.getKey(), entry.getValue(), byBoard, byBoardAndBand, currencies,
                        gateKinds, knownFactors, out);
            }
        }
        for (Map.Entry<String, BountyAsset> entry : bounties.entrySet()) {
            if (entry.getValue() != null) {
                validateBounty(entry.getKey(), entry.getValue(), boards, rewardKinds, objectiveKinds,
                        gateKinds, knownFactors, out);
            }
        }
        return out;
    }

    // ==================== boards ====================

    private static void validateBoard(@Nonnull String id, @Nonnull BoardAsset board,
            @Nonnull Map<String, Integer> byBoard, @Nonnull Map<String, Map<String, Integer>> byBand,
            @Nullable ShopValidator.CurrencyProbe currencies, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownFactors, @Nonnull List<Finding> out) {

        int eligible = byBoard.getOrDefault(id, 0);
        if (eligible == 0) {
            out.add(Finding.error(DOMAIN, "EMPTY_BOARD",
                    "no contract names this board under Boards, so it posts nothing at all and a player who "
                            + "opens it sees an empty notice", id));
        }
        Map<String, Integer> bands = byBand.getOrDefault(id, Map.of());

        int required = 0;
        Set<String> slotBands = new LinkedHashSet<>();
        for (BoardSlotAsset slot : board.slotsOrEmpty()) {
            if (slot == null) {
                continue;
            }
            Integer authoredCount = slot.getCount();
            if (authoredCount != null && authoredCount < 1) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_SLOT_COUNT",
                        "a slot asks for " + authoredCount + " contracts, which is read as 1; drop the slot "
                                + "when you mean it to post nothing", id));
            }
            if (!slot.isOptional()) {
                required += slot.countOrOne();
            }
            String band = slot.label();
            if (band == null) {
                continue;
            }
            slotBands.add(band);
            int available = bands.getOrDefault(band, 0);
            if (available == 0) {
                out.add(Finding.error(DOMAIN, "UNFILLABLE_SLOT",
                        "a slot posts the band '" + slot.getDifficulty() + "', which no contract on this board "
                                + "carries under Boards.Difficulty; the slot can never be filled" + (
                                slot.isOptional() ? " and is quietly skipped every rotation"
                                        : " and leaves a visible gap"), id));
            } else if (available < slot.countOrOne()) {
                out.add(Finding.warning(DOMAIN, "OVERSUBSCRIBED_BOARD",
                        "a slot wants " + slot.countOrOne() + " DISTINCT contracts of band '"
                                + slot.getDifficulty() + "' but only " + available + " exist, so the board comes "
                                + "up short every rotation; write more at that band or lower the count", id));
            }
        }
        if (required > 0 && eligible > 0 && eligible < required) {
            out.add(Finding.warning(DOMAIN, "OVERSUBSCRIBED_BOARD",
                    "the slots want " + required + " distinct contracts but only " + eligible
                            + " name this board, so it comes up short every rotation", id));
        }

        for (String band : board.grades().keySet()) {
            if (!slotBands.isEmpty() && !slotBands.contains(band)) {
                out.add(Finding.warning(DOMAIN, "NAME_FOR_UNPOSTED_BAND",
                        "Grades names the band '" + band + "', which none of this board's slots ever posts, so "
                                + "the word is never read; check the spelling against the slots", id));
            }
        }

        for (Map.Entry<String, GateSpec> gate : board.acceptRequires().entrySet()) {
            String band = gate.getKey();
            if (!slotBands.isEmpty() && !slotBands.contains(band)) {
                out.add(Finding.warning(DOMAIN, "GATE_ON_UNPOSTED_BAND",
                        "AcceptRequires gates the band '" + band + "', which none of this board's slots ever "
                                + "posts, so the gate is never consulted; check the spelling against the slots",
                        id));
            }
            out.addAll(GateValidator.validate(gate.getValue(), DOMAIN, id + ".AcceptRequires." + band,
                    NOUN, gateKinds, knownFactors, null));
        }

        RotationAsset rotation = board.getRotation();
        if (rotation != null) {
            validateRotation(rotation, id, out);
        }
        SelectionAsset selection = board.getSelection();
        if (selection != null && selection.getType() != null && !isKnownSelection(selection.getType())) {
            out.add(Finding.warning(DOMAIN, "UNKNOWN_SELECTION",
                    "Selection.Type is '" + selection.getType() + "', which nothing registered; the board "
                            + "cannot draw until whichever mod owns that strategy is installed", id));
        }

        if (currencies != null) {
            for (String currencyId : board.currencyIds()) {
                if (!currencies.defines(currencyId)) {
                    out.add(Finding.warning(DOMAIN, "UNKNOWN_CURRENCY",
                            "the header lists the wallet '" + currencyId + "', which nothing defines; it shows "
                                    + "as nothing until whichever pack owns it is installed", id));
                }
            }
        }
        RerollAsset reroll = board.getReroll();
        if (reroll != null) {
            validateReroll(reroll, id, currencies, out);
        }
        if (board.getWhere() != null) {
            out.addAll(stamp(WhereValidator.validateSelector(board.getWhere(), id + ".Where"), id));
        }
        out.addAll(GateValidator.validate(board.getRequires(), DOMAIN, id, "board",
                gateKinds, knownFactors, null));
    }

    // ==================== contracts ====================

    private static void validateBounty(@Nonnull String id, @Nonnull BountyAsset bounty,
            @Nonnull Map<String, BoardAsset> boards, @Nullable Predicate<String> rewardKinds,
            @Nullable Predicate<String> objectiveKinds, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownFactors, @Nonnull List<Finding> out) {

        if (bounty.isAbstract()) {
            return;
        }

        List<BountyAsset.BoardMembership> memberships = bounty.boardMemberships();
        if (memberships.isEmpty()) {
            out.add(Finding.warning(DOMAIN, "ORPHANED_BOUNTY",
                    "no Boards entry names a board, so this contract is never posted anywhere. Add one, or "
                            + "mark the file Abstract if it exists only to be inherited from", id));
        }
        Set<String> seenBoards = new LinkedHashSet<>();
        for (BountyAsset.BoardMembership membership : memberships) {
            String boardId = membership.getBoard();
            if (boardId == null) {
                continue;
            }
            if (!seenBoards.add(boardId)) {
                out.add(Finding.warning(DOMAIN, "DUPLICATE_BOARD_MEMBERSHIP",
                        "the contract names the board '" + boardId + "' twice; only the first entry is read, so "
                                + "the second says nothing", id));
            }
            BoardAsset board = boards.get(boardId);
            if (board == null) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_BOARD",
                        "Boards names '" + boardId + "', which nothing defines, so this contract is never "
                                + "posted; it comes back on its own if the pack owning that board is installed",
                        id));
                continue;
            }
            String band = membership.getDifficulty();
            if (band == null && board.slotsOrEmpty().length > 0) {
                out.add(Finding.warning(DOMAIN, "MEMBERSHIP_WITHOUT_DIFFICULTY",
                        "the entry for board '" + boardId + "' names no Difficulty, but every one of that "
                                + "board's slots posts a named band, so this contract can never be drawn onto "
                                + "it", id));
            }
            Double weight = membership.getWeight();
            if (weight != null && weight <= 0.0) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_WEIGHT",
                        "the entry for board '" + boardId + "' has Weight " + weight + ", which would make the "
                                + "contract unpostable; it is read as 1. Take it off the board with Enabled "
                                + "instead", id));
            }
        }

        Map<String, QuestObjectiveAsset> objectives = bounty.objectivesOrEmpty();
        if (objectives.isEmpty()) {
            out.add(Finding.error(DOMAIN, "NO_OBJECTIVES",
                    "the contract asks for no work, so it counts as finished the instant it is taken", id));
        }
        for (Map.Entry<String, QuestObjectiveAsset> step : objectives.entrySet()) {
            QuestObjectiveAsset objective = step.getValue();
            if (objective == null || objective.isBlank()) {
                out.add(Finding.error(DOMAIN, "MISSING_KIND",
                        "step '" + step.getKey() + "' names no Kind, so nothing can ever progress it", id));
                continue;
            }
            String kind = objective.getKind();
            if (objectiveKinds != null && kind != null && !objectiveKinds.test(kind)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_KIND",
                        "step '" + step.getKey() + "' listens for '" + kind + "', which nothing registered; it "
                                + "can never progress until whichever mod fires it is installed", id));
            }
            Long amount = objective.getAmount();
            if (amount != null && amount <= 0L) {
                out.add(Finding.warning(DOMAIN, "NON_POSITIVE_AMOUNT",
                        "step '" + step.getKey() + "' asks for " + amount + ", so it is already done the moment "
                                + "the contract is taken", id));
            }
        }

        ContentRewardsAsset rewards = bounty.getRewards();
        if (rewards == null || rewards.isEmpty()) {
            out.add(Finding.warning(DOMAIN, "EMPTY_REWARDS",
                    "the contract pays nothing, so a player does the work for no return", id));
        }
        if (rewards != null) {
            validateRewardEntries(rewards.autoEntries(), "Rewards.Auto", rewardKinds, id, out);
            validateRewardEntries(rewards.claimEntries(), "Rewards.Claim", rewardKinds, id, out);
        }

        out.addAll(GateValidator.validate(bounty.getRequires(), DOMAIN, id, NOUN,
                gateKinds, knownFactors, null));
    }

    /** One reward bucket's entries: a blank one is an error, an unknown kind a warning. */
    private static void validateRewardEntries(@Nonnull RewardEntryAsset[] rewards, @Nonnull String where,
            @Nullable Predicate<String> rewardKinds, @Nonnull String id, @Nonnull List<Finding> out) {
        for (RewardEntryAsset reward : rewards) {
            if (reward == null || reward.isBlank()) {
                out.add(Finding.error(DOMAIN, "BLANK_REWARD",
                        "a " + where + " entry names no Kind, so it can never pay anything out", id));
                continue;
            }
            String kind = reward.getKind();
            if (rewardKinds != null && kind != null && !rewardKinds.test(kind)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_REWARD_KIND",
                        where + " names '" + kind + "', which has no handler registered, so finishing "
                                + "this pays out nothing for it", id));
            }
        }
    }

    // ==================== shared pieces ====================

    private static void validateRotation(@Nonnull RotationAsset rotation, @Nonnull String id,
            @Nonnull List<Finding> out) {

        if (rotation.hasBothCadences()) {
            out.add(Finding.error(DOMAIN, "BOTH_PERIOD_AND_EVERY",
                    "Rotation authors BOTH Period and Every; they are two ways of saying when the postings "
                            + "change and only one of them can apply. Keep the one you meant", id));
        }
        if (rotation.hasUnknownPeriod()) {
            out.add(Finding.error(DOMAIN, "UNKNOWN_PERIOD",
                    "Rotation.Period is '" + rotation.getPeriod() + "', which is neither "
                            + RotationAsset.PERIOD_DAILY + " nor " + RotationAsset.PERIOD_WEEKLY
                            + "; write a span under Every for any other cadence", id));
        }
        if (rotation.parsedWeekday() == null) {
            out.add(Finding.error(DOMAIN, "UNKNOWN_WEEKDAY",
                    "Rotation.Weekday is '" + rotation.getWeekday() + "', which is not a day name; the "
                            + "postings change on Monday instead", id));
        } else if (rotation.getWeekday() != null && !rotation.isWeekly()) {
            out.add(Finding.warning(DOMAIN, "WEEKDAY_WITHOUT_WEEKLY",
                    "Rotation.Weekday does nothing unless Period is " + RotationAsset.PERIOD_WEEKLY
                            + "; either drop it or make the cadence weekly", id));
        }
        Integer offset = rotation.getOffsetMinutes();
        if (offset != null && offset < 0) {
            out.add(Finding.warning(DOMAIN, "NEGATIVE_OFFSET",
                    "Rotation.OffsetMinutes is " + offset + ", which is read as none; a rollover cannot happen "
                            + "before its own boundary", id));
        }
    }

    private static void validateReroll(@Nonnull RerollAsset reroll, @Nonnull String id,
            @Nullable ShopValidator.CurrencyProbe currencies, @Nonnull List<Finding> out) {

        CostAsset cost = reroll.getCost();
        if (cost != null && currencies != null) {
            for (String currencyId : cost.currencyAmounts().keySet()) {
                if (!currencies.defines(currencyId)) {
                    out.add(Finding.warning(DOMAIN, "MISSING_REROLL_CURRENCY",
                            "the reroll is priced in the wallet '" + currencyId + "', which nothing defines; "
                                    + "nobody can hold it, so no player can ever reroll here", id));
                }
            }
        }
        if ((cost == null || cost.isFree()) && reroll.maxPerPeriod() <= 0) {
            out.add(Finding.warning(DOMAIN, "UNLIMITED_FREE_REROLL",
                    "a Reroll block is authored with no price and no MaxPerPeriod, so a player may reroll for "
                            + "ever until they get the contract they want, which makes the posting itself "
                            + "pointless; author a price, a limit, or both", id));
        }
    }

    /** Is {@code type} one of the strategies this library seeds? */
    private static boolean isKnownSelection(@Nonnull String type) {
        return SelectionAsset.TYPE_WEIGHTED_RANDOM.equalsIgnoreCase(type)
                || SelectionAsset.TYPE_ALL.equalsIgnoreCase(type);
    }

    /** Re-file another validator's findings under this domain and this content id. */
    @Nonnull
    private static List<Finding> stamp(@Nonnull List<Finding> findings, @Nonnull String sourceId) {
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            out.add(new Finding(finding.severity(), finding.code(), finding.message(), sourceId, DOMAIN));
        }
        return out;
    }
}
