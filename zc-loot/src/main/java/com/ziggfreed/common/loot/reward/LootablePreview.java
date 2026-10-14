package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootPool;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.RollEvaluator;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * How a {@code Lootable} reward reads when its table says (opt-in, {@link LootableAsset}'s class note): the
 * table file's {@code Text.TitleKey} names the reward's line, and hovering it shows {@code Text.FlavorKey}
 * and, with {@code ListContents}, what the table pays. A table authoring neither answers null, so its reward
 * reads as the kind file's generic bundle exactly as before.
 *
 * <p>The words come from the table's own file ({@link LootableConfig#resolveAuthored}); the contents from the
 * table that actually pays ({@link LootableConfig#resolve}, contributions and roll sources included).
 * Contributed through {@link RewardChips#contributeDescriber} from the wiring root, so it answers on a server
 * running this library alone, on every surface that paints a reward chip.
 *
 * <p><b>The contents</b> are decided from the table alone, asking no player anything ({@link #plan}): what
 * always comes, in authored order; then each grant whose roll has a plain percent chance, with that percent;
 * then what only sometimes comes, whatever a condition, a factor, a ladder or a pool decides. Each reads as
 * its own reward line would. A grant nothing here can name (a native drop list, a console line that hands
 * over no item, a kind nothing names) is left out, and one closing line says there is more.
 */
public final class LootablePreview {

    /** This module's lang prefix ({@code ziggfreedcommon.loot.lang}). */
    private static final String PREFIX = "ziggfreedcommon.loot.";

    /** Set while a table's contents are read, so a table inside it is named but never opened. */
    private static final ThreadLocal<Boolean> LISTING = ThreadLocal.withInitial(() -> false);

    private LootablePreview() {
    }

    /** The describer the wiring root contributes ({@link RewardChips#contributeDescriber}). */
    @Nonnull
    public static RewardChips.Describer describer() {
        return LootablePreview::describe;
    }

    /** What a {@code Lootable} reward's table says about itself, or null for any other reward or a silent table. */
    @Nullable
    static RewardChips.Described describe(@Nonnull RewardSpec spec) {
        if (!LootRewardKinds.KIND_LOOTABLE.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        String tableId = LootRewardKinds.tableIdOf(spec);
        if (tableId.isEmpty()) {
            return null;
        }
        LootableConfig tables = LootableConfig.getInstance();
        LootableAsset authored = tables.resolveAuthored(tableId);
        if (authored == null) {
            return null;
        }
        ContentTextAsset text = authored.getText();
        Message name = nameOf(text);
        if (LISTING.get()) {
            // Read inside another table's contents: only its line shows there, never its hover.
            return name == null ? null : new RewardChips.Described(name, List.of());
        }
        List<Message> hover = new ArrayList<>();
        String flavorKey = text == null ? null : text.getFlavorKey();
        if (flavorKey != null && !flavorKey.isBlank()) {
            hover.add(ContentKeys.tr(flavorKey));
        }
        if (authored.listsContents()) {
            hover.addAll(contents(tables.resolve(tableId)));
        }
        return name == null && hover.isEmpty() ? null : new RewardChips.Described(name, hover);
    }

    /** The table's own name: its {@code TitleKey}, else its plain {@code DisplayName}, else null. */
    @Nullable
    private static Message nameOf(@Nullable ContentTextAsset text) {
        if (text == null) {
            return null;
        }
        String key = text.getTitleKey();
        if (key != null && !key.isBlank()) {
            return ContentKeys.tr(key);
        }
        String plain = text.getDisplayName();
        return plain != null && !plain.isBlank() ? Msg.raw(plain) : null;
    }

    // ==================== the contents ====================

    /** The hover lines listing what {@code table} pays, in {@link #plan} order; empty for no table. */
    @Nonnull
    static List<Message> contents(@Nullable LootableAsset table) {
        if (table == null) {
            return List.of();
        }
        Plan plan = plan(table);
        boolean more = plan.more();
        List<Message> lines = new ArrayList<>();
        LISTING.set(true);
        try {
            for (Listed listed : plan.listed()) {
                Message label = labelOf(listed.grant());
                if (label == null) {
                    more = true;
                    continue;
                }
                lines.add(lineOf(listed, label));
            }
        } finally {
            LISTING.set(false);
        }
        if (more) {
            lines.add(Msg.tr(PREFIX, "preview.more"));
        }
        return lines;
    }

    /** One listed grant as a hover line: its label, after its percent or the word for "sometimes". */
    @Nonnull
    private static Message lineOf(@Nonnull Listed listed, @Nonnull Message label) {
        if (listed.odds().sometimes()) {
            return Msg.join(Msg.tr(PREFIX, "preview.sometimes"), Msg.raw(" "), label);
        }
        if (listed.odds().percent() >= Roll.MAX_CHANCE_PERCENT) {
            return label;
        }
        return Msg.join(Msg.tr(PREFIX, "preview.chance", listed.odds().percent()), Msg.raw(" "), label);
    }

    /** How a grant reads: a reward as its own chip's line, an item as a reward line for it; null when nothing names it. */
    @Nullable
    private static Message labelOf(@Nonnull Grant grant) {
        if (grant.reward() != null) {
            RewardChip chip = RewardChips.chipFor(grant.reward());
            return chip == null ? null : chip.label();
        }
        return RewardChips.itemLabel(grant.itemId(), grant.count(), grant.countMax());
    }

    // ==================== the plan (strings and numbers only) ====================

    /** One thing a table may hand over: a reward read as its own chip, or an item and how many. */
    record Grant(@Nullable RewardSpec reward, @Nullable String itemId, int count, int countMax) {
    }

    /**
     * How surely a grant comes: {@code percent} out of 100 (100 always), or {@code sometimes} when
     * something this preview does not ask decides (a condition, a factor, a ladder, a pool).
     */
    record Odds(double percent, boolean sometimes) {

        static final Odds ALWAYS = new Odds(Roll.MAX_CHANCE_PERCENT, false);
        static final Odds SOMETIMES = new Odds(Roll.MIN_CHANCE_PERCENT, true);

        boolean never() {
            return !sometimes && percent <= Roll.MIN_CHANCE_PERCENT;
        }

        boolean always() {
            return !sometimes && percent >= Roll.MAX_CHANCE_PERCENT;
        }
    }

    /** One grant and its odds. */
    record Listed(@Nonnull Grant grant, @Nonnull Odds odds) {
    }

    /** Every grant {@code table} may hand over, in hover order, and whether anything was left unnamed. */
    record Plan(@Nonnull List<Listed> listed, boolean more) {
    }

    /**
     * What {@code table} may hand over, decided from the table alone: what always comes, then each grant
     * at a plain percent (authored order), then what sometimes comes. A roll that can never fire is left
     * out; {@code more} is true when a grant here cannot be named (a native drop list, a console line that
     * hands over no item).
     */
    @Nonnull
    static Plan plan(@Nonnull LootableAsset table) {
        Collector out = new Collector();
        for (Roll roll : table.rollsOrEmpty()) {
            if (roll == null) {
                continue;
            }
            Odds odds = oddsOf(roll);
            if (odds.never()) {
                continue;
            }
            out.addAll(roll.getGrants(), odds);
            addLadder(roll.getLadder(), odds, out);
        }
        LootPool pool = table.getPool();
        if (pool != null && pool.getEntries() != null) {
            for (LootPool.Entry entry : pool.getEntries()) {
                if (entry != null) {
                    out.addAll(entry.getGrants(), Odds.SOMETIMES);
                }
            }
        }
        List<Listed> listed = new ArrayList<>(out.always);
        listed.addAll(out.chances);
        listed.addAll(out.sometimes);
        return new Plan(listed, out.more);
    }

    /**
     * A roll's odds with no player asked: always with no chance; its percent when the chance is a plain
     * one (a base, held by its clamp and 0..100); sometimes when a condition or a factor term decides.
     */
    @Nonnull
    static Odds oddsOf(@Nonnull Roll roll) {
        FactorCondition[] conditions = roll.getConditions();
        if (conditions != null) {
            for (FactorCondition condition : conditions) {
                if (condition != null) {
                    return Odds.SOMETIMES;
                }
            }
        }
        FactorFormula chance = roll.getChance();
        if (chance == null) {
            return Odds.ALWAYS;
        }
        if (!chance.hasNoTerms()) {
            return Odds.SOMETIMES;
        }
        return new Odds(RollEvaluator.effectiveChancePercent(chance, FactorLookup.none()), false);
    }

    /**
     * A ladder's floors: with no factor to sum, the value is 0 and the highest floor at or under 0 pays
     * at the roll's odds (the last written of a tie, as the ladder reads); with factors, every floor is a
     * sometimes.
     */
    private static void addLadder(@Nullable Roll.Ladder ladder, @Nonnull Odds odds, @Nonnull Collector out) {
        if (ladder == null || ladder.getFloors() == null) {
            return;
        }
        boolean constant = true;
        FactorFormula.Term[] terms = ladder.getFactors();
        if (terms != null) {
            for (FactorFormula.Term term : terms) {
                if (term != null && !term.isBlank()) {
                    constant = false;
                    break;
                }
            }
        }
        if (!constant) {
            for (Roll.Ladder.Floor floor : ladder.getFloors()) {
                if (floor != null) {
                    out.addAll(floor.getGrants(), Odds.SOMETIMES);
                }
            }
            return;
        }
        Roll.Ladder.Floor reached = null;
        for (Roll.Ladder.Floor floor : ladder.getFloors()) {
            if (floor != null && floor.effectiveMin() <= 0.0
                    && (reached == null || floor.effectiveMin() >= reached.effectiveMin())) {
                reached = floor;
            }
        }
        if (reached != null) {
            out.addAll(reached.getGrants(), odds);
        }
    }

    /** The plan being built: three runs in hover order, and whether a grant was left unnamed. */
    private static final class Collector {

        final List<Listed> always = new ArrayList<>();
        final List<Listed> chances = new ArrayList<>();
        final List<Listed> sometimes = new ArrayList<>();
        boolean more;

        void addAll(@Nullable LootGrants grants, @Nonnull Odds odds) {
            if (grants == null) {
                return;
            }
            for (LootGrants.Item item : grants.itemsOrEmpty()) {
                add(new Grant(null, item.getItem(), item.effectiveCount(), item.effectiveCountMax()), odds);
            }
            for (RewardSpec reward : grants.rewardSpecs()) {
                add(new Grant(reward, null, 0, 0), odds);
            }
            if (grants.getCommands() != null) {
                for (String line : grants.getCommands()) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    CommandRunner.Give give = CommandRunner.readGive(line);
                    if (give == null) {
                        more = true;
                    } else {
                        int count = Math.max(1, give.quantity());
                        add(new Grant(null, give.itemId(), count, count), odds);
                    }
                }
            }
            if (grants.getDropLists() != null) {
                for (String dropList : grants.getDropLists()) {
                    if (dropList != null && !dropList.isBlank()) {
                        more = true;
                    }
                }
            }
        }

        private void add(@Nonnull Grant grant, @Nonnull Odds odds) {
            Listed listed = new Listed(grant, odds);
            if (odds.sometimes()) {
                sometimes.add(listed);
            } else if (odds.always()) {
                always.add(listed);
            } else {
                chances.add(listed);
            }
        }
    }
}
