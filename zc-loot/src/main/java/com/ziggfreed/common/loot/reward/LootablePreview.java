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
 * How a {@code Lootable} reward reads ({@link LootableAsset}'s class note): as the table it pays, by default.
 * The reward's line reads the table's name (its {@code Text.TitleKey}, else its plain {@code Text.DisplayName},
 * else its id made readable, {@link #readableId}), and hovering it shows that name, then {@code Text.FlavorKey}
 * when written, then what the table pays unless {@code ListContents} is false. A table set to
 * {@code PlainBundle}, or a name no table on this server answers, answers null, so its reward reads as the kind
 * file's generic bundle.
 *
 * <p>The words come from the table's own file ({@link LootableConfig#resolveAuthored}); the contents from the
 * table that actually pays ({@link LootableConfig#resolve}, contributions and roll sources included).
 * Contributed through {@link RewardChips#contributeDescriber} from the wiring root, so it answers on a server
 * running this library alone, on every surface that paints a reward chip.
 *
 * <p><b>The contents</b> are decided from the table alone, asking no player anything ({@link #plan}): what
 * always comes, in authored order; then each grant whose roll has a plain percent chance, with that percent
 * ({@link #shownPercent}: at most one decimal); then what only sometimes comes, whatever a condition, a
 * factor, a ladder or a pool decides. Each reads as its own reward line would. A table inside it is named,
 * never opened. Up to two closing lines follow, in this order: one saying there is more when a grant here
 * cannot be named (a native drop list, a console line that hands over no item, a kind nothing names), and one
 * saying a few more rare finds are left unsaid when a roll that can fire sets {@code HideInPreview}, which
 * leaves that roll and its ladder out. A table paid inside another one follows the same rule wherever its own
 * contents are listed; inside the other table's list it is one named line, which the knob on the roll that
 * pays it hides like any other, and its own rows add nothing there.
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

    /**
     * What a {@code Lootable} reward's table says about itself: its name and its hover, or null for any other
     * reward, a name no table answers, or a table set to {@code PlainBundle}.
     */
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
        if (authored == null || authored.readsAsPlainBundle()) {
            return null;
        }
        ContentTextAsset text = authored.getText();
        Message name = nameOf(text, tableId);
        if (LISTING.get()) {
            // Read inside another table's contents: only its line shows there, never its hover.
            return new RewardChips.Described(name, List.of());
        }
        List<Message> hover = new ArrayList<>();
        String flavorKey = text == null ? null : text.getFlavorKey();
        if (flavorKey != null && !flavorKey.isBlank()) {
            hover.add(ContentKeys.tr(flavorKey));
        }
        if (authored.listsContents()) {
            hover.addAll(contents(tables.resolve(tableId)));
        }
        return new RewardChips.Described(name, hover);
    }

    /**
     * The table's own name: its {@code TitleKey}, else its plain {@code DisplayName}, else {@code tableId} made
     * readable ({@link #readableId}), so a table that authors nothing still reads as itself.
     */
    @Nonnull
    private static Message nameOf(@Nullable ContentTextAsset text, @Nonnull String tableId) {
        String key = text == null ? null : text.getTitleKey();
        if (key != null && !key.isBlank()) {
            return ContentKeys.tr(key);
        }
        String plain = text == null ? null : text.getDisplayName();
        if (plain != null && !plain.isBlank()) {
            return Msg.raw(plain);
        }
        return Msg.raw(readableId(tableId));
    }

    /**
     * A table id as words, the name of a table that authors none: each run of letters and digits between
     * underscores, hyphens, spaces or a lower-to-upper case step is one word, its first letter upper-cased and
     * the rest kept as written ({@code Forest_Finds}, {@code forest_finds} and {@code ForestFinds} all read
     * "Forest Finds"). Spelt from the id as the reward writes it, which keeps its casing; the stored id is
     * lower-cased.
     */
    @Nonnull
    static String readableId(@Nonnull String tableId) {
        StringBuilder out = new StringBuilder(tableId.length() + 4);
        boolean wordStart = true;
        char previous = ' ';
        for (int i = 0; i < tableId.length(); i++) {
            char c = tableId.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                wordStart = true;
                previous = c;
                continue;
            }
            if (Character.isUpperCase(c) && Character.isLowerCase(previous)) {
                wordStart = true;
            }
            if (wordStart && !out.isEmpty()) {
                out.append(' ');
            }
            out.append(wordStart ? Character.toUpperCase(c) : c);
            wordStart = false;
            previous = c;
        }
        return out.isEmpty() ? tableId : out.toString();
    }

    // ==================== the contents ====================

    /**
     * The hover lines listing what {@code table} pays, in {@link #plan} order, then the line saying there is more
     * (a grant nothing names), then the line saying a few more rare finds are left unsaid (a hidden roll); empty
     * for no table.
     */
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
        if (plan.hidden()) {
            lines.add(Msg.tr(PREFIX, "preview.hidden"));
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
        return Msg.join(Msg.tr(PREFIX, "preview.chance", shownPercent(listed.odds().percent())), Msg.raw(" "),
                label);
    }

    /**
     * A chance as the hover binds it, a typed number for the key's {@code {0, number}}: rounded to one decimal
     * (92.3077 reads 92.3), a whole number bound whole so it shows no decimal (5.0 reads 5), and a chance above
     * 0 that rounds to 0 shown as 0.1, so a rare chance never reads as none.
     */
    @Nonnull
    static Number shownPercent(double percent) {
        double rounded = Math.round(percent * 10.0) / 10.0;
        if (rounded <= 0.0 && percent > 0.0) {
            rounded = 0.1;
        }
        if (rounded == Math.rint(rounded)) {
            return (long) rounded;
        }
        return rounded;
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

    /**
     * Every grant {@code table} may hand over, in hover order, whether anything was left unnamed
     * ({@code more}), and whether a roll that can fire was left out on purpose ({@code hidden}).
     */
    record Plan(@Nonnull List<Listed> listed, boolean more, boolean hidden) {
    }

    /**
     * What {@code table} may hand over, decided from the table alone: what always comes, then each grant
     * at a plain percent (authored order), then what sometimes comes. A roll that can never fire is left
     * out; a roll that sets {@code HideInPreview} is left out with its ladder and sets {@code hidden};
     * {@code more} is true when a grant here cannot be named (a native drop list, a console line that
     * hands over no item).
     */
    @Nonnull
    static Plan plan(@Nonnull LootableAsset table) {
        Collector out = new Collector();
        boolean hidden = false;
        for (Roll roll : table.rollsOrEmpty()) {
            if (roll == null) {
                continue;
            }
            Odds odds = oddsOf(roll);
            if (odds.never()) {
                continue;
            }
            if (roll.hidesInPreview()) {
                hidden = true;
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
        return new Plan(listed, out.more, hidden);
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
