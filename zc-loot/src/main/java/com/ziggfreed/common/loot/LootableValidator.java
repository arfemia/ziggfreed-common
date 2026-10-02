package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.reward.CollectingRewardKind;
import com.ziggfreed.common.loot.reward.MomentItems;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.validation.Finding;

/**
 * Reads authored loot the way an author would want it read: it looks for the mistakes that produce
 * SILENCE - a roll that can never fire, a tier that can never be reached, a table id nothing answers
 * to - because those are the ones nobody notices until a player asks where their reward went.
 *
 * <p>Severity says what it means. An ERROR is content that cannot work as written. A WARNING is
 * content that works but almost certainly does not do what was intended, and an unknown id is always
 * a warning rather than an error: the mod that would answer it may simply not be installed on the
 * machine running the check.
 *
 * <p>A {@code ContributesTo} naming a table nobody ships is the one unknown id reported a tier lower
 * still, as a NOTE. Enriching a table another mod owns is what that leaf is for, so a pack shipped
 * for servers that may or may not run that other mod is behaving exactly as designed when its
 * contribution finds nothing to attach to. It is worth saying once, where an author looking for a
 * typo will read it, and worth staying out of the way otherwise.
 *
 * <h2>A site's inline rolls and the collectors its pass carries</h2>
 *
 * <p>A reward kind that collects onto a pass ({@link CollectingRewardKind}) pays only where the pass
 * rolling it carries that kind's collector. A TABLE cannot know where it will be rolled, so the
 * table audit never reports one. A site can: it knows its own pass, so it hands
 * {@link #auditRef(LootRef, String, RewardKindRegistry, Set)} the collector types that pass carries,
 * and every collecting kind in that site's INLINE rolls whose collector none of them satisfies is
 * reported under {@link #PASS_ONLY_REWARD_KIND}. An empty set is a site whose pass carries nothing;
 * null is no site at all, and stays as silent as a table.
 *
 * <p>Only that carried-collector check is site-dependent, so only it stays silent in a table. A check
 * that holds whatever site rolls the reward is made wherever the reward is authored, a table and a
 * pool included: a {@code Moment_Item} Count that cannot pay ({@link #MOMENT_COUNT_INVALID}) or that
 * is above the copies one pass hands over ({@link #MOMENT_COUNT_ABOVE_CEILING}).
 */
public final class LootableValidator {

    /** The domain every finding here is stamped with. */
    public static final String DOMAIN = "lootable";

    // Codes, stable so an owner can grep a boot log for one.
    public static final String EMPTY_TABLE = "LOOT_EMPTY_TABLE";
    public static final String NO_ROLL_CONTENT = "LOOT_NO_ROLL_CONTENT";
    public static final String UNKNOWN_TABLE = "LOOT_UNKNOWN_TABLE";
    public static final String BLANK_TABLE_REF = "LOOT_BLANK_TABLE_REF";
    public static final String BLANK_CONDITION = "LOOT_BLANK_CONDITION";
    public static final String INVERTED_BOUNDS = "LOOT_INVERTED_BOUNDS";
    public static final String IMPOSSIBLE_CHANCE = "LOOT_IMPOSSIBLE_CHANCE";
    public static final String CERTAIN_CHANCE = "LOOT_CERTAIN_CHANCE";
    public static final String INVERTED_CLAMP = "LOOT_INVERTED_CLAMP";
    public static final String LADDER_NO_FLOORS = "LOOT_LADDER_NO_FLOORS";
    public static final String LADDER_NO_FACTORS = "LOOT_LADDER_NO_FACTORS";
    public static final String DUPLICATE_FLOOR = "LOOT_DUPLICATE_FLOOR";
    public static final String UNREACHABLE_FLOOR = "LOOT_UNREACHABLE_FLOOR";
    public static final String EMPTY_FLOOR = "LOOT_EMPTY_FLOOR";
    public static final String BLANK_ITEM = "LOOT_BLANK_ITEM";
    public static final String NON_POSITIVE_COUNT = "LOOT_NON_POSITIVE_COUNT";
    public static final String INVERTED_COUNT_RANGE = "LOOT_INVERTED_COUNT_RANGE";
    public static final String BLANK_DROP_LIST = "LOOT_BLANK_DROP_LIST";
    public static final String BLANK_COMMAND = "LOOT_BLANK_COMMAND";
    public static final String BLANK_REWARD_KIND = "LOOT_BLANK_REWARD_KIND";
    public static final String UNKNOWN_REWARD_KIND = "LOOT_UNKNOWN_REWARD_KIND";
    public static final String POOL_NO_ENTRIES = "LOOT_POOL_NO_ENTRIES";
    public static final String POOL_NO_PICKS = "LOOT_POOL_NO_PICKS";
    public static final String POOL_ENTRY_NEVER_PICKED = "LOOT_POOL_ENTRY_NEVER_PICKED";
    public static final String POOL_ENTRY_EMPTY = "LOOT_POOL_ENTRY_EMPTY";
    public static final String UNKNOWN_CONTRIBUTION_TARGET = "LOOT_UNKNOWN_CONTRIBUTION_TARGET";
    public static final String SELF_CONTRIBUTION = "LOOT_SELF_CONTRIBUTION";
    public static final String CONTRIBUTED_PICKS_IGNORED = "LOOT_CONTRIBUTED_PICKS_IGNORED";
    /** A collecting kind in a site's inline roll whose collector that site's pass never carries. */
    public static final String PASS_ONLY_REWARD_KIND = CollectingRewardKind.SITE_CODE;
    /** A {@code Moment_Item} reward whose Count is above the most copies one pass hands over. */
    public static final String MOMENT_COUNT_ABOVE_CEILING = "LOOT_MOMENT_COUNT_ABOVE_CEILING";
    /** A {@code Moment_Item} reward whose Count is not a finite number above zero, so it fails at payout. */
    public static final String MOMENT_COUNT_INVALID = "LOOT_MOMENT_COUNT_INVALID";

    private LootableValidator() {
    }

    /**
     * Audit every loaded lootable table AS AUTHORED - each file's own findings against its own id,
     * so a contributor's mistake is reported where the author would go and fix it rather than a
     * second time under the table it enriches.
     */
    @Nonnull
    public static List<Finding> auditAll(@Nullable RewardKindRegistry kinds) {
        List<Finding> findings = new ArrayList<>();
        LootableConfig config = LootableConfig.getInstance();
        config.all().forEach((id, table) -> {
            Roll[] rolls = table.getRolls();
            LootPool pool = table.getPool();
            if ((rolls == null || rolls.length == 0) && pool == null) {
                findings.add(Finding.warning(DOMAIN, EMPTY_TABLE,
                        "This table has no rolls and no pool, so anything referencing it gets nothing.", id));
            }
            if (rolls != null) {
                for (int i = 0; i < rolls.length; i++) {
                    findings.addAll(auditRoll(rolls[i], id + " roll " + i, kinds));
                }
            }
            findings.addAll(auditPool(pool, id, kinds));
            findings.addAll(auditContribution(config, id, table));
        });
        for (String target : config.unresolvedContributionTargets()) {
            // A NOTE, not a warning: contributing to a table another mod ships is the whole point of
            // ContributesTo, so a contribution waiting for a mod this server does not run is content
            // working as designed. Only a typo makes it a mistake, which is why the line still names
            // the target and who is waiting on it.
            findings.add(Finding.info(DOMAIN, UNKNOWN_CONTRIBUTION_TARGET,
                    "No loot table named '" + target + "' is loaded, so "
                            + String.join(", ", config.contributorsOf(target))
                            + " adds nothing here. That is expected when the pack that ships '" + target
                            + "' is not installed; check the spelling if it was meant for a table you do have.",
                    target));
        }
        return findings;
    }

    /** Audit ONE pool: that it can be drawn at all, and that each entry can win something. */
    @Nonnull
    public static List<Finding> auditPool(@Nullable LootPool pool, @Nonnull String sourceId,
            @Nullable RewardKindRegistry kinds) {
        List<Finding> findings = new ArrayList<>();
        if (pool == null) {
            return findings;
        }
        LootPool.Entry[] entries = pool.getEntries();
        if (entries == null || entries.length == 0) {
            findings.add(Finding.warning(DOMAIN, POOL_NO_ENTRIES,
                    "The pool has no entries, so drawing from it hands over nothing. Author entries, or "
                            + "remove the whole Pool group.", sourceId));
            return findings;
        }
        FactorFormula picks = pool.getRolls();
        if (picks != null && picks.hasNoTerms()) {
            FactorFormula.Clamp clamp = picks.getClamp();
            double base = picks.baseOrZero();
            double effective = clamp == null ? base : clamp.apply(base);
            if (effective < 1.0) {
                findings.add(Finding.error(DOMAIN, POOL_NO_PICKS,
                        "The pool works out to " + effective + " picks with no factors to raise it, so it "
                                + "can never be drawn. Raise Base, or add a factor.", sourceId));
            }
        }
        for (int i = 0; i < entries.length; i++) {
            LootPool.Entry entry = entries[i];
            if (entry == null) {
                continue;
            }
            String entryId = sourceId + " pool entry " + i;
            if (entry.getWeight() != null && entry.effectiveWeight() <= 0.0) {
                findings.add(Finding.warning(DOMAIN, POOL_ENTRY_NEVER_PICKED,
                        "This entry has a weight of " + entry.getWeight() + ", so it never comes up while "
                                + "any other entry can.", entryId));
            }
            if (entry.isEmpty()) {
                findings.add(Finding.warning(DOMAIN, POOL_ENTRY_EMPTY,
                        "This entry hands over nothing, so drawing it wastes a pick.", entryId));
            }
            auditConditions(entry.getConditions(), entryId, findings);
            auditGrants(entry.getGrants(), entryId, findings, kinds, null);
        }
        return findings;
    }

    /** Audit ONE table's {@code ContributesTo} leaf against the tables actually loaded. */
    @Nonnull
    private static List<Finding> auditContribution(@Nonnull LootableConfig config, @Nonnull String id,
            @Nonnull LootableAsset table) {
        List<Finding> findings = new ArrayList<>();
        String target = table.getContributesTo();
        if (target == null || target.isBlank()) {
            return findings;
        }
        if (target.equalsIgnoreCase(id)) {
            findings.add(Finding.warning(DOMAIN, SELF_CONTRIBUTION,
                    "This table contributes to itself, which adds nothing. Remove ContributesTo, or point "
                            + "it at the table you meant to enrich.", id));
            return findings;
        }
        LootableAsset base = config.resolveAuthored(target);
        if (base != null && base.getPool() != null && table.getPool() != null
                && table.getPool().getRolls() != null) {
            findings.add(Finding.info(DOMAIN, CONTRIBUTED_PICKS_IGNORED,
                    "'" + target + "' already says how many picks its pool makes, so the Pool.Rolls here is "
                            + "not read. The entries below it are still added.", id));
        }
        return findings;
    }

    /**
     * Audit a {@link LootRef} at a consuming site: its referenced ids resolve, and its inline rolls
     * hold up. {@code sourceId} names the site so a finding points at the file that has to change.
     *
     * <p>Says nothing about where a collecting kind can pay; a site that knows what its pass carries
     * asks {@link #auditRef(LootRef, String, RewardKindRegistry, Set)}.
     */
    @Nonnull
    public static List<Finding> auditRef(@Nullable LootRef ref, @Nonnull String sourceId,
            @Nullable RewardKindRegistry kinds) {
        return auditRef(ref, sourceId, kinds, null);
    }

    /**
     * As {@link #auditRef(LootRef, String, RewardKindRegistry)}, for a site that knows which collector
     * types its pass layers on the subject: each collecting kind named in the ref's INLINE rolls (their
     * grants and their ladder floors' grants) whose collector no type in {@code carried} satisfies is
     * a {@link #PASS_ONLY_REWARD_KIND} warning, since there it would always count lost. A type
     * satisfies a collector when the collector is assignable from it, the same test the subject's
     * {@code handleAs} applies. The tables the ref names are not judged on it: the same table is
     * rolled at other sites.
     *
     * @param carried the collector types the site's pass carries; empty for a pass that carries none,
     *                null for no site (silent, as a table is)
     */
    @Nonnull
    public static List<Finding> auditRef(@Nullable LootRef ref, @Nonnull String sourceId,
            @Nullable RewardKindRegistry kinds, @Nullable Set<Class<?>> carried) {
        List<Finding> findings = new ArrayList<>();
        if (ref == null || ref.isEmpty()) {
            return findings;
        }
        String[] lootables = ref.getLootables();
        if (lootables != null) {
            for (String tableId : lootables) {
                if (tableId == null || tableId.isBlank()) {
                    findings.add(Finding.warning(DOMAIN, BLANK_TABLE_REF,
                            "A loot table reference is empty and does nothing.", sourceId));
                } else if (LootableConfig.getInstance().resolve(tableId) == null) {
                    findings.add(Finding.warning(DOMAIN, UNKNOWN_TABLE,
                            "No loot table named '" + tableId + "' is loaded, so it contributes nothing. "
                                    + "Check the spelling, or the pack that ships it.", sourceId));
                }
            }
        }
        Roll[] rolls = ref.getRolls();
        if (rolls != null) {
            for (int i = 0; i < rolls.length; i++) {
                findings.addAll(auditRoll(rolls[i], sourceId + " roll " + i, kinds, carried));
            }
        }
        return findings;
    }

    /** Audit ONE roll. Silent about where a collecting kind can pay, as a table's roll is. */
    @Nonnull
    public static List<Finding> auditRoll(@Nullable Roll roll, @Nonnull String sourceId,
            @Nullable RewardKindRegistry kinds) {
        return auditRoll(roll, sourceId, kinds, null);
    }

    /**
     * Audit ONE roll a site authors inline, flagging each collecting kind in its grants and its ladder
     * floors that no type in {@code carried} can collect (see
     * {@link #auditRef(LootRef, String, RewardKindRegistry, Set)}; null is silent).
     */
    @Nonnull
    public static List<Finding> auditRoll(@Nullable Roll roll, @Nonnull String sourceId,
            @Nullable RewardKindRegistry kinds, @Nullable Set<Class<?>> carried) {
        List<Finding> findings = new ArrayList<>();
        if (roll == null) {
            return findings;
        }
        auditConditions(roll.getConditions(), sourceId, findings);
        auditChance(roll.getChance(), sourceId, findings);
        auditLadder(roll.getLadder(), sourceId, findings, kinds, carried);
        auditGrants(roll.getGrants(), sourceId, findings, kinds, carried);

        boolean anyPayout = !isEmpty(roll.getGrants()) || hasFloorGrants(roll.getLadder());
        if (!anyPayout && (roll.getCue() == null || roll.getCue().isBlank())) {
            findings.add(Finding.warning(DOMAIN, NO_ROLL_CONTENT,
                    "This roll grants nothing and plays nothing, so firing it has no effect.", sourceId));
        }
        return findings;
    }

    // ==================== pieces ====================

    private static void auditConditions(@Nullable FactorCondition[] conditions, @Nonnull String sourceId,
            @Nonnull List<Finding> findings) {
        if (conditions == null) {
            return;
        }
        for (FactorCondition condition : conditions) {
            if (condition == null || condition.isBlank()) {
                findings.add(Finding.warning(DOMAIN, BLANK_CONDITION,
                        "A condition names no factor, so it is skipped and gates nothing.", sourceId));
                continue;
            }
            Double min = condition.getMin();
            Double max = condition.getMax();
            if (min != null && max != null && min > max) {
                findings.add(Finding.error(DOMAIN, INVERTED_BOUNDS,
                        "Condition on '" + condition.getFactor() + "' asks for at least " + min
                                + " and at most " + max + ", which nothing can satisfy.", sourceId));
            }
        }
    }

    private static void auditChance(@Nullable FactorFormula chance, @Nonnull String sourceId,
            @Nonnull List<Finding> findings) {
        if (chance == null) {
            return;
        }
        FactorFormula.Clamp clamp = chance.getClamp();
        if (clamp != null && clamp.isInverted()) {
            findings.add(Finding.error(DOMAIN, INVERTED_CLAMP,
                    "The chance clamp has its floor above its ceiling.", sourceId));
        }
        if (chance.hasNoTerms()) {
            double base = chance.baseOrZero();
            double effective = clamp == null ? base : clamp.apply(base);
            if (effective <= Roll.MIN_CHANCE_PERCENT) {
                findings.add(Finding.error(DOMAIN, IMPOSSIBLE_CHANCE,
                        "The chance works out to " + effective + " percent with no factors to raise it, "
                                + "so this roll can never fire.", sourceId));
            } else if (effective >= Roll.MAX_CHANCE_PERCENT) {
                findings.add(Finding.info(DOMAIN, CERTAIN_CHANCE,
                        "The chance is always 100 percent; the whole Chance group can be removed.", sourceId));
            }
        }
    }

    private static void auditLadder(@Nullable Roll.Ladder ladder, @Nonnull String sourceId,
            @Nonnull List<Finding> findings, @Nullable RewardKindRegistry kinds,
            @Nullable Set<Class<?>> carried) {
        if (ladder == null) {
            return;
        }
        Roll.Ladder.Floor[] floors = ladder.getFloors();
        if (floors == null || floors.length == 0) {
            findings.add(Finding.warning(DOMAIN, LADDER_NO_FLOORS,
                    "The ladder has no floors, so it can never pay anything out.", sourceId));
            return;
        }
        boolean anyTerm = false;
        if (ladder.getFactors() != null) {
            for (FactorFormula.Term term : ladder.getFactors()) {
                if (term != null && !term.isBlank()) {
                    anyTerm = true;
                    break;
                }
            }
        }
        Set<Double> seen = new HashSet<>();
        for (int i = 0; i < floors.length; i++) {
            Roll.Ladder.Floor floor = floors[i];
            if (floor == null) {
                continue;
            }
            String floorId = sourceId + " floor " + i;
            double min = floor.effectiveMin();
            if (!seen.add(min)) {
                findings.add(Finding.warning(DOMAIN, DUPLICATE_FLOOR,
                        "Two floors both start at " + min + "; only the last one written can ever pay out.",
                        floorId));
            }
            if (!anyTerm && min > 0.0) {
                findings.add(Finding.warning(DOMAIN, UNREACHABLE_FLOOR,
                        "The ladder sums no factors, so its value is always 0 and this floor at " + min
                                + " is out of reach. Add a factor, or lower it to 0.", floorId));
            }
            if (isEmpty(floor.getGrants()) && (floor.getCue() == null || floor.getCue().isBlank())) {
                findings.add(Finding.warning(DOMAIN, EMPTY_FLOOR,
                        "This floor grants nothing and plays nothing, so reaching it has no effect.", floorId));
            }
            auditGrants(floor.getGrants(), floorId, findings, kinds, carried);
        }
        if (!anyTerm) {
            findings.add(Finding.info(DOMAIN, LADDER_NO_FACTORS,
                    "The ladder sums no factors, so only a floor at 0 can ever be reached.", sourceId));
        }
    }

    private static void auditGrants(@Nullable LootGrants grants, @Nonnull String sourceId,
            @Nonnull List<Finding> findings, @Nullable RewardKindRegistry kinds,
            @Nullable Set<Class<?>> carried) {
        if (grants == null) {
            return;
        }
        if (grants.getItems() != null) {
            for (LootGrants.Item item : grants.getItems()) {
                if (item == null || item.isBlank()) {
                    findings.add(Finding.warning(DOMAIN, BLANK_ITEM,
                            "An item entry names no item and hands over nothing.", sourceId));
                } else if (item.getCount() != null && item.getCount() <= 0) {
                    findings.add(Finding.warning(DOMAIN, NON_POSITIVE_COUNT,
                            "Item '" + item.getItem() + "' asks for a count of " + item.getCount()
                                    + "; one is handed over instead. Remove the key for one.", sourceId));
                } else if (item.getCountMax() != null && item.getCountMax() < item.effectiveCount()) {
                    findings.add(Finding.warning(DOMAIN, INVERTED_COUNT_RANGE,
                            "Item '" + item.getItem() + "' has a CountMax of " + item.getCountMax()
                                    + " below its Count of " + item.effectiveCount()
                                    + ", so the quantity never varies. Raise CountMax, or remove it.",
                            sourceId));
                }
            }
        }
        reportBlanks(grants.getDropLists(), BLANK_DROP_LIST,
                "A drop list reference is empty and rolls nothing.", sourceId, findings);
        reportBlanks(grants.getCommands(), BLANK_COMMAND,
                "A command entry is empty and runs nothing.", sourceId, findings);

        // A registered kind that collects onto a pass (CollectingRewardKind) passes quietly in a
        // table on purpose: a table cannot know which pass will roll it. Only a site that names what
        // its pass carries (carried non-null) has it judged, in its own inline rolls. The Moment_Item
        // Count checks hold at every site, so they run here, tables included.
        if (grants.getRewards() != null) {
            for (LootGrants.Reward reward : grants.getRewards()) {
                if (reward == null || reward.isBlank()) {
                    findings.add(Finding.warning(DOMAIN, BLANK_REWARD_KIND,
                            "A reward entry names no kind and pays nothing out.", sourceId));
                    continue;
                }
                if (kinds != null && !kinds.isRegistered(reward.getKind())) {
                    findings.add(Finding.warning(DOMAIN, UNKNOWN_REWARD_KIND,
                            "Nothing on this server pays out reward kind '" + reward.getKind()
                                    + "', so it hands over nothing. Registered kinds: " + kinds.ids(),
                            sourceId));
                } else if (carried != null
                        && !carries(carried, CollectingRewardKind.collectorOf(kinds, reward.getKind()))) {
                    Finding finding = CollectingRewardKind.siteWarning(DOMAIN, kinds, reward.getKind(), "Rewards",
                            sourceId);
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
                auditMomentCount(reward, sourceId, findings);
            }
        }
    }

    /**
     * Whether some type in {@code carried} satisfies {@code collector} (assignable to it, the test the
     * subject's {@code handleAs} applies). A kind that collects nothing (null) needs no carrier.
     */
    private static boolean carries(@Nonnull Set<Class<?>> carried, @Nullable Class<?> collector) {
        if (collector == null) {
            return true;
        }
        for (Class<?> type : carried) {
            if (type != null && collector.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code Moment_Item} reward's written Count: one that cannot pay (not a finite number above
     * zero, which fails at payout) is {@link #MOMENT_COUNT_INVALID}, and one above
     * {@link MomentItems#MAX_COPIES} (the pass clamps the total, so the reward pays fewer copies than
     * written) is {@link #MOMENT_COUNT_ABOVE_CEILING}. Judged wherever it is authored, a table too:
     * neither depends on the site, unlike the carried-collector check, which a table cannot know and
     * so never reports. An unwritten Count is one copy and fine. Only this kind: another collecting
     * kind's Count means whatever its owner says it means.
     */
    private static void auditMomentCount(@Nonnull LootGrants.Reward reward, @Nonnull String sourceId,
            @Nonnull List<Finding> findings) {
        if (!MomentItems.KIND.equalsIgnoreCase(reward.getKind().trim())) {
            return;
        }
        RewardSpec spec = reward.toSpec();
        String written = spec == null ? null : spec.param(MomentItems.P_COUNT);
        if (written == null) {
            return;
        }
        double count = MomentItems.parseCount(written);
        if (!MomentItems.isPayable(count)) {
            findings.add(Finding.warning(DOMAIN, MOMENT_COUNT_INVALID,
                    "A " + MomentItems.KIND + " reward has a Count of '" + written + "', which is not a number"
                            + " of copies above zero, so it pays nothing and counts as a failed reward. Write a"
                            + " positive number, or remove the Count for one copy.", sourceId));
            return;
        }
        if (count > MomentItems.MAX_COPIES) {
            findings.add(Finding.warning(DOMAIN, MOMENT_COUNT_ABOVE_CEILING,
                    "A " + MomentItems.KIND + " reward asks for " + written + " copies, above the "
                            + MomentItems.MAX_COPIES + " one pass ever hands over, so it pays at most "
                            + MomentItems.MAX_COPIES + ". Lower the Count.", sourceId));
        }
    }

    private static void reportBlanks(@Nullable String[] values, @Nonnull String code,
            @Nonnull String message, @Nonnull String sourceId, @Nonnull List<Finding> findings) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                findings.add(Finding.warning(DOMAIN, code, message, sourceId));
            }
        }
    }

    private static boolean isEmpty(@Nullable LootGrants grants) {
        return grants == null || grants.isEmpty();
    }

    private static boolean hasFloorGrants(@Nullable Roll.Ladder ladder) {
        if (ladder == null || ladder.getFloors() == null) {
            return false;
        }
        for (Roll.Ladder.Floor floor : ladder.getFloors()) {
            if (floor != null && !isEmpty(floor.getGrants())) {
                return true;
            }
        }
        return false;
    }

    /** Every finding code this validator can emit, for a test that pins the vocabulary. */
    @Nonnull
    public static List<String> codes() {
        return List.of(EMPTY_TABLE, NO_ROLL_CONTENT, UNKNOWN_TABLE, BLANK_TABLE_REF, BLANK_CONDITION,
                INVERTED_BOUNDS, IMPOSSIBLE_CHANCE, CERTAIN_CHANCE, INVERTED_CLAMP, LADDER_NO_FLOORS,
                LADDER_NO_FACTORS, DUPLICATE_FLOOR, UNREACHABLE_FLOOR, EMPTY_FLOOR, BLANK_ITEM,
                NON_POSITIVE_COUNT, INVERTED_COUNT_RANGE, BLANK_DROP_LIST, BLANK_COMMAND, BLANK_REWARD_KIND,
                UNKNOWN_REWARD_KIND, POOL_NO_ENTRIES, POOL_NO_PICKS, POOL_ENTRY_NEVER_PICKED,
                POOL_ENTRY_EMPTY, UNKNOWN_CONTRIBUTION_TARGET, SELF_CONTRIBUTION,
                CONTRIBUTED_PICKS_IGNORED, PASS_ONLY_REWARD_KIND, MOMENT_COUNT_ABOVE_CEILING,
                MOMENT_COUNT_INVALID);
    }
}
