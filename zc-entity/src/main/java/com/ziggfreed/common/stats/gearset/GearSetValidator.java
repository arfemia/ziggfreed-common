package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.entity.HeldItemUtil;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The content audit over the folded gear sets, filed under the {@code gear_set} domain: what an
 * author got wrong in a set file, said while the file is still open. A disabled set is skipped.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the thin engine walk
 * (asks the live item, stat-channel and effect stores) and {@link #audit(Collection, Predicate,
 * Predicate, Predicate, ToDoubleFunction)} is the pure core a test drives with fakes.
 *
 * <p>The codes, each a stable machine token a consumer may filter on:
 * <ul>
 *   <li>ERROR {@link #EMPTY_MEMBERS}, {@link #NO_BONUSES}, {@link #TIER_WITHOUT_CONDITION},
 *       {@link #BAD_PIECE_COUNT} (a minimum at or below zero, {@code Pieces} or {@code Armor} above
 *       the member count, {@code Held} or {@code Utility} above one);</li>
 *   <li>WARN {@link #UNKNOWN_SET_MEMBER}, {@link #UNKNOWN_STAT_ID}, {@link #DUPLICATE_TIER},
 *       {@link #MULTIPLICATIVE_ON_BASE_ZERO} (the token a consumer's item audit already uses for the
 *       same arithmetic, so one filter covers both), {@link #UNKNOWN_SET_EFFECT},
 *       {@link #TOO_FEW_MEMBERS};</li>
 *   <li>INFO {@link #UNNAMED_SET}.</li>
 * </ul>
 */
public final class GearSetValidator {

    public static final String DOMAIN = "gear_set";

    public static final String EMPTY_MEMBERS = "EMPTY_MEMBERS";
    public static final String NO_BONUSES = "NO_BONUSES";
    public static final String TIER_WITHOUT_CONDITION = "TIER_WITHOUT_CONDITION";
    public static final String BAD_PIECE_COUNT = "BAD_PIECE_COUNT";
    public static final String UNKNOWN_SET_MEMBER = "UNKNOWN_SET_MEMBER";
    public static final String UNKNOWN_STAT_ID = "UNKNOWN_STAT_ID";
    public static final String DUPLICATE_TIER = "DUPLICATE_TIER";
    public static final String MULTIPLICATIVE_ON_BASE_ZERO = "MULTIPLICATIVE_ON_BASE_ZERO";
    public static final String UNKNOWN_SET_EFFECT = "UNKNOWN_SET_EFFECT";
    public static final String TOO_FEW_MEMBERS = "TOO_FEW_MEMBERS";
    public static final String UNNAMED_SET = "UNNAMED_SET";

    /** A set of one member is a set of nothing: the bonus is the item's own stats with extra steps. */
    static final int FEWEST_SENSIBLE_MEMBERS = 2;

    private GearSetValidator() {
    }

    /** The engine walk over every folded set, against the live item, stat and effect stores. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(GearSetConfig.getInstance().all().values(),
                    loadedItemIds(),
                    id -> StatIndexCache.resolve(id) >= 0,
                    GearSetValidator::effectKnown,
                    GearSetValidator::baseMaxOf);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] the gear-set audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param itemKnown   whether an item id names a loaded item, matched without regard to case the
     *                    way the engine matches a member at runtime
     * @param statKnown   whether a stat channel id is registered
     * @param effectKnown whether an effect id names a loaded {@code EntityEffect}
     * @param baseMax     a registered channel's base maximum, {@code NaN} when it cannot be read
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<GearSetAsset> sets, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> effectKnown,
            @Nonnull ToDoubleFunction<String> baseMax) {
        List<Finding> out = new ArrayList<>();
        for (GearSetAsset set : sets) {
            if (set == null || !set.isEnabled()) {
                continue;
            }
            auditOne(set, itemKnown, statKnown, effectKnown, baseMax, out);
        }
        return out;
    }

    private static void auditOne(@Nonnull GearSetAsset set, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> effectKnown,
            @Nonnull ToDoubleFunction<String> baseMax, @Nonnull List<Finding> out) {
        String id = set.getId() == null ? "" : set.getId();
        Set<String> members = set.memberIds();
        List<GearSetAsset.Tier> tiers = set.tiers();

        if (set.titleKey() == null) {
            out.add(Finding.info(DOMAIN, UNNAMED_SET, "gear set '" + id + "' has no Text.TitleKey, so its "
                    + "notice names it by its id rather than a translated name", id));
        }
        if (members.isEmpty()) {
            out.add(Finding.error(DOMAIN, EMPTY_MEMBERS, "gear set '" + id + "' lists no Members, so no item "
                    + "can ever count toward it and no tier can ever apply", id));
        } else if (members.size() < FEWEST_SENSIBLE_MEMBERS) {
            out.add(Finding.warning(DOMAIN, TOO_FEW_MEMBERS, "gear set '" + id + "' has " + members.size()
                    + " distinct member; a set bonus for one item is that item's own stats with extra "
                    + "steps, so author the stats on the item instead", id));
        }
        for (String member : authored(set.getMembers())) {
            if (!itemKnown.test(member)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_SET_MEMBER, "gear set '" + id + "' names the member '"
                        + member + "', which is no loaded item, so wearing it can never count", id));
            }
        }
        if (tiers.isEmpty()) {
            out.add(Finding.error(DOMAIN, NO_BONUSES, "gear set '" + id + "' authors no Bonuses, so completing "
                    + "it pays nothing", id));
        }
        Map<GearSetAsset.Condition, Integer> seen = new HashMap<>();
        for (int i = 0; i < tiers.size(); i++) {
            auditTier(id, i, tiers.get(i), members.size(), statKnown, effectKnown, baseMax, seen, out);
        }
    }

    private static void auditTier(@Nonnull String id, int index, @Nonnull GearSetAsset.Tier tier, int memberCount,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> effectKnown,
            @Nonnull ToDoubleFunction<String> baseMax, @Nonnull Map<GearSetAsset.Condition, Integer> seen,
            @Nonnull List<Finding> out) {
        String where = "gear set '" + id + "' tier " + index;
        if (!tier.hasCondition()) {
            out.add(Finding.error(DOMAIN, TIER_WITHOUT_CONDITION, where + " authors none of Pieces, Armor, "
                    + "Held or Utility, so it can never apply", id));
        } else {
            Integer earlier = seen.putIfAbsent(tier.condition(), index);
            if (earlier != null) {
                out.add(Finding.warning(DOMAIN, DUPLICATE_TIER, where + " asks exactly what tier " + earlier
                        + " asks, so both come on together; fold them into one tier", id));
            }
        }
        checkCount(where, "Pieces", tier.getPieces(), memberCount, id, out);
        checkCount(where, "Armor", tier.getArmor(), memberCount, id, out);
        checkCount(where, "Held", tier.getHeld(), 1, id, out);
        checkCount(where, "Utility", tier.getUtility(), 1, id, out);

        String effect = tier.effectId();
        if (effect != null && !effectKnown.test(effect)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_SET_EFFECT, where + " names the Effect '" + effect
                    + "', which is no loaded EntityEffect, so the set's look never shows", id));
        }
        for (Map.Entry<String, StatModifierSpec[]> entry : tier.statModifiers().entrySet()) {
            auditStat(where, entry.getKey(), entry.getValue(), statKnown, baseMax, id, out);
        }
    }

    private static void checkCount(@Nonnull String where, @Nonnull String leaf, @Nullable Integer minimum, int ceiling,
            @Nonnull String id, @Nonnull List<Finding> out) {
        if (minimum == null) {
            return;
        }
        if (minimum <= 0) {
            out.add(Finding.error(DOMAIN, BAD_PIECE_COUNT, where + " authors " + leaf + " " + minimum
                    + "; a minimum has to be at least one, or leave the leaf out", id));
        } else if (minimum > ceiling) {
            out.add(Finding.error(DOMAIN, BAD_PIECE_COUNT, where + " authors " + leaf + " " + minimum
                    + ", more than the " + ceiling + " it could ever count, so it can never apply", id));
        }
    }

    private static void auditStat(@Nonnull String where, @Nullable String statId, @Nullable StatModifierSpec[] specs,
            @Nonnull Predicate<String> statKnown, @Nonnull ToDoubleFunction<String> baseMax, @Nonnull String id,
            @Nonnull List<Finding> out) {
        if (statId == null || statId.isBlank()) {
            return;
        }
        String stat = statId.trim();
        if (!statKnown.test(stat)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_STAT_ID, where + " authors StatModifiers." + stat
                    + ", which is no registered stat channel, so that line is skipped", id));
            return;
        }
        if (specs == null || !anyMultiplicative(specs)) {
            return;
        }
        double base;
        try {
            base = baseMax.applyAsDouble(stat);
        } catch (Throwable t) {
            return;
        }
        if (base == 0.0) {
            out.add(Finding.warning(DOMAIN, MULTIPLICATIVE_ON_BASE_ZERO, where + " authors a Multiplicative "
                    + "modifier on " + stat + ", whose base is 0: additive applies first, multiplicative "
                    + "second, so 0 * x = 0 and the entry is silently inert. Author Additive instead", id));
        }
    }

    private static boolean anyMultiplicative(@Nonnull StatModifierSpec[] specs) {
        for (StatModifierSpec spec : specs) {
            if (spec != null && spec.isMultiplicative()) {
                return true;
            }
        }
        return false;
    }

    /** The authored member ids, trimmed, blanks dropped, duplicates kept (each is checked once as typed). */
    @Nonnull
    private static List<String> authored(@Nullable String[] members) {
        List<String> out = new ArrayList<>();
        if (members == null) {
            return out;
        }
        for (String member : members) {
            if (member != null && !member.isBlank()) {
                out.add(member.trim());
            }
        }
        return out;
    }

    /** The engine's loaded items, asked {@link #itemKnownIgnoringCase without regard to case}. */
    @Nonnull
    private static Predicate<String> loadedItemIds() {
        return itemKnownIgnoringCase(id -> HeldItemUtil.itemAsset(id) != null,
                () -> Item.getAssetMap().getAssetMap().keySet());
    }

    /**
     * Whether an item id names a loaded item, without regard to case: members match that way at
     * runtime, so a member spelled in another case is not unknown. The exact lookup answers first;
     * only a miss folds every loaded id, once per predicate (one audit). A read that throws answers
     * true, since cannot tell is not "missing". Pure over its two lookups.
     */
    @Nonnull
    static Predicate<String> itemKnownIgnoringCase(@Nonnull Predicate<String> exact,
            @Nonnull Supplier<Collection<String>> loadedIds) {
        AtomicReference<Set<String>> folded = new AtomicReference<>();
        return id -> {
            if (exact.test(id)) {
                return true;
            }
            try {
                Set<String> loaded = folded.updateAndGet(known -> known != null ? known : lowerCased(loadedIds.get()));
                return loaded.contains(id.toLowerCase(Locale.ROOT));
            } catch (Throwable t) {
                return true;
            }
        };
    }

    @Nonnull
    private static Set<String> lowerCased(@Nonnull Collection<String> ids) {
        Set<String> out = new HashSet<>();
        for (String id : ids) {
            if (id != null) {
                out.add(id.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean effectKnown(@Nonnull String effectId) {
        try {
            return EntityEffect.getAssetMap().getIndex(effectId) != Integer.MIN_VALUE;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    private static double baseMaxOf(@Nonnull String statId) {
        try {
            EntityStatType type = EntityStatType.getAssetMap().getAsset(statId);
            return type == null ? Double.NaN : type.getMax();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }
}
