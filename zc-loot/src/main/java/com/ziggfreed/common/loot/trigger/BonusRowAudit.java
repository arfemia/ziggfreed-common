package com.ziggfreed.common.loot.trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.LootableValidator;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.match.NamePattern;
import com.ziggfreed.common.validation.Finding;

/**
 * Reads a bonus table the way an author would want it read: it hunts the mistakes that produce
 * SILENCE, because a find that hands over nothing is the one nobody reports.
 *
 * <p>Every table audits through here with its own {@link Codes}: a row naming no moment, a pattern
 * the name grammar cannot mean, a chance that can never rise off zero, a factor nothing answers, a
 * deliberate hole (a note) and two rows pinning down one name (a note). The loot half goes through
 * the shared loot validator with the row's moment's carried collectors, so a {@code Moment_Item}
 * outside a {@code PickupItem} row warns {@code PASS_ONLY_REWARD_KIND}; {@code lootFold} lets a
 * consumer stamp those findings as its own.
 */
public final class BonusRowAudit {

    /** The domain and codes one table's findings are stamped with. */
    public record Codes(@Nonnull String domain, @Nonnull String noMoment, @Nonnull String badPattern,
            @Nonnull String impossibleChance, @Nonnull String unknownFactor, @Nonnull String noLoot,
            @Nonnull String overlappingPattern) {
    }

    /** The domain the library's own rows are audited under. */
    public static final String DOMAIN = "bonus_rows";
    public static final String NO_MOMENT = "BONUS_ROW_NO_MOMENT";
    public static final String BAD_PATTERN = "BONUS_ROW_BAD_PATTERN";
    public static final String IMPOSSIBLE_CHANCE = "BONUS_ROW_IMPOSSIBLE_CHANCE";
    public static final String UNKNOWN_FACTOR = "BONUS_ROW_UNKNOWN_FACTOR";
    public static final String NO_LOOT = "BONUS_ROW_NO_LOOT";
    public static final String OVERLAPPING_PATTERN = "BONUS_ROW_OVERLAPPING_PATTERN";

    /** The library's own rows' codes. */
    public static final Codes CODES = new Codes(DOMAIN, NO_MOMENT, BAD_PATTERN, IMPOSSIBLE_CHANCE,
            UNKNOWN_FACTOR, NO_LOOT, OVERLAPPING_PATTERN);

    private BonusRowAudit() {
    }

    /** The library's own rows, against the given vocabularies; a null one skips its own check. */
    @Nonnull
    public static List<Finding> auditAll(@Nullable RewardKindRegistry kinds, @Nullable FactorRegistry factors) {
        BonusRowConfig config = BonusRowConfig.getInstance();
        return audit(config.table(), config.momentlessRows(), CODES, kinds, factors, UnaryOperator.identity());
    }

    /** Any table, stamped with {@code codes}; its loot findings pass through {@code lootFold}. */
    @Nonnull
    public static <E extends BonusEntry> List<Finding> audit(@Nonnull BonusTable<E> table,
            @Nonnull Map<String, String> momentless, @Nonnull Codes codes, @Nullable RewardKindRegistry kinds,
            @Nullable FactorRegistry factors, @Nonnull UnaryOperator<List<Finding>> lootFold) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> silent : momentless.entrySet()) {
            String named = silent.getValue();
            findings.add(Finding.error(codes.domain(), codes.noMoment(),
                    (named.isEmpty()
                            ? "This row names no moment, so nothing ever reaches it."
                            : "This row's When.Kind is '" + named + "', which is no moment a bonus row "
                                    + "fires in, so nothing ever reaches it.")
                            + " Write one of: " + BonusMoment.tokens() + ".",
                    silent.getKey()));
        }
        for (BonusMoment moment : BonusMoment.values()) {
            auditMoment(moment, table.patterned(moment), codes, kinds, factors, lootFold, findings);
        }
        return findings;
    }

    private static <E extends BonusEntry> void auditMoment(@Nonnull BonusMoment moment,
            @Nonnull List<Map.Entry<NamePattern, E>> rows, @Nonnull Codes codes,
            @Nullable RewardKindRegistry kinds, @Nullable FactorRegistry factors,
            @Nonnull UnaryOperator<List<Finding>> lootFold, @Nonnull List<Finding> findings) {
        // Two rows pinning down the same run of characters both apply somewhere, and each wins where
        // it is the more specific; worth seeing even though neither is wrong.
        Map<String, String> firstByCore = new LinkedHashMap<>();
        for (Map.Entry<NamePattern, E> row : rows) {
            NamePattern pattern = row.getKey();
            E entry = row.getValue();
            String id = entry.sourceId();
            if (hasInteriorWildcard(pattern.raw)) {
                findings.add(Finding.warning(codes.domain(), codes.badPattern(),
                        "The pattern '" + pattern.raw + "' has a * in the middle, which the name grammar "
                                + "does not read as a wildcard. Only a leading and/or trailing * is one, so "
                                + "this matches the literal text instead.", id));
            }
            if (!pattern.isDefaultRule()) {
                String previous = firstByCore.putIfAbsent(pattern.core(), id);
                if (previous != null && !previous.equals(id)) {
                    findings.add(Finding.info(codes.domain(), codes.overlappingPattern(),
                            "This row and '" + previous + "' pin down the same name in the same moment, so "
                                    + "which one applies depends on how each is anchored. Write one row "
                                    + "unless they are meant to cover different names.", id));
                }
            }
            auditChance(entry.chance(), id, codes, factors, findings);
            if (entry.handsNothingOver()) {
                findings.add(Finding.info(codes.domain(), codes.noLoot(),
                        "This row hands nothing over, so it is a deliberate hole: it stops a broader "
                                + "pattern covering the same name. Set Enabled false instead to let the "
                                + "broader pattern take over again.", id));
                continue;
            }
            // The moment says which collectors its pass carries, so a collecting kind written inline
            // pays only where that pass carries its collector. A named table stays on the silent
            // table path: the same table is rolled at other sites.
            findings.addAll(lootFold.apply(LootableValidator.auditRef(entry.loot(), id, kinds, moment.carries())));
        }
    }

    private static void auditChance(@Nullable FactorFormula chance, @Nonnull String id, @Nonnull Codes codes,
            @Nullable FactorRegistry factors, @Nonnull List<Finding> findings) {
        if (chance == null || chance.isEmpty()) {
            return;
        }
        if (chance.hasNoTerms()) {
            double base = chance.baseOrZero();
            FactorFormula.Clamp clamp = chance.getClamp();
            double effective = clamp == null ? base : clamp.apply(base);
            if (effective <= 0.0) {
                findings.add(Finding.error(codes.domain(), codes.impossibleChance(),
                        "The chance works out to " + effective + " percent with no factors to raise it, so "
                                + "this row's moment can never fire.", id));
            }
            return;
        }
        if (factors == null) {
            return;
        }
        for (FactorFormula.Term term : chance.termsOrEmpty()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String factorId = term.getFactor();
            if (!factors.isRegistered(factorId)) {
                findings.add(Finding.warning(codes.domain(), codes.unknownFactor(),
                        "The chance reads '" + factorId + "', which nothing on this server answers, so that "
                                + "term adds nothing. Install the mod that owns it, or remove the term and "
                                + "fold its value into Base.", id));
            }
        }
    }

    /** True when a {@code *} appears anywhere other than the very start or the very end. */
    private static boolean hasInteriorWildcard(@Nonnull String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        return trimmed.substring(1, trimmed.length() - 1).indexOf('*') >= 0;
    }
}
