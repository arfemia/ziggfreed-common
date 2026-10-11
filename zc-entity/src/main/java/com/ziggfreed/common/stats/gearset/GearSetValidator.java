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
import java.util.function.Function;
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
 *       the member count, {@code Held} or {@code Utility} above one), {@link #JOINS_A_LAYER};</li>
 *   <li>WARN {@link #UNKNOWN_SET_MEMBER}, {@link #UNKNOWN_STAT_ID}, {@link #DUPLICATE_TIER},
 *       {@link #MULTIPLICATIVE_ON_BASE_ZERO} (the token a consumer's item audit already uses for the
 *       same arithmetic, so one filter covers both), {@link #UNKNOWN_SET_EFFECT},
 *       {@link #TOO_FEW_MEMBERS}, {@link #UNKNOWN_JOIN_TARGET}, {@link #JOIN_EFFECT_CLASH},
 *       {@link #UNKNOWN_SET_BADGE}, {@link #BADGE_WITHOUT_ICON}, {@link #BADGE_NOT_INFINITE},
 *       {@link #BADGE_ID_CLASH}, {@link #JOIN_BADGE_CLASH}, {@link #LAYER_INHERITS_TARGET_TIERS};</li>
 *   <li>INFO {@link #UNNAMED_SET}.</li>
 * </ul>
 *
 * <p>Each file is audited as written, so a finding names the file that has to change, against the
 * set as it applies ({@link GearSetJoins}): a minimum is weighed against the joined members. A layer
 * (a file with {@code Joins}) takes its name, members and bonuses from its set, so the set-level codes
 * ({@link #UNNAMED_SET}, {@link #EMPTY_MEMBERS}, {@link #TOO_FEW_MEMBERS}, {@link #NO_BONUSES}) are
 * never its own; a layer the fold dropped is named once and checked no further.
 */
public final class GearSetValidator {

    public static final String DOMAIN = "gear_set";

    /** The label the gear-set audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[gearset] audit";

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
    public static final String UNKNOWN_JOIN_TARGET = "UNKNOWN_JOIN_TARGET";
    public static final String JOINS_A_LAYER = "JOINS_A_LAYER";
    public static final String JOIN_EFFECT_CLASH = "JOIN_EFFECT_CLASH";
    public static final String UNKNOWN_SET_BADGE = "UNKNOWN_SET_BADGE";
    public static final String BADGE_WITHOUT_ICON = "BADGE_WITHOUT_ICON";
    public static final String BADGE_NOT_INFINITE = "BADGE_NOT_INFINITE";
    public static final String BADGE_ID_CLASH = "BADGE_ID_CLASH";
    public static final String JOIN_BADGE_CLASH = "JOIN_BADGE_CLASH";
    public static final String LAYER_INHERITS_TARGET_TIERS = "LAYER_INHERITS_TARGET_TIERS";

    /** A set of one member is a set of nothing: the bonus is the item's own stats with extra steps. */
    static final int FEWEST_SENSIBLE_MEMBERS = 2;

    /**
     * What the audit reads off a loaded effect a {@code Badge} names: whether it carries a buff-bar
     * icon (its own or its Parent's) and whether it is {@code Infinite}.
     */
    public record EffectFacts(boolean icon, boolean infinite) {
    }

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
                    GearSetValidator::effectFactsOf,
                    GearSetValidator::baseMaxOf);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] the gear-set audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core, with no effect facts to read: a badge's icon and {@code Infinite} go unchecked.
     *
     * @see #audit(Collection, Predicate, Predicate, Predicate, Function, ToDoubleFunction)
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<GearSetAsset> sets, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> effectKnown,
            @Nonnull ToDoubleFunction<String> baseMax) {
        return audit(sets, itemKnown, statKnown, effectKnown, id -> null, baseMax);
    }

    /**
     * The pure core.
     *
     * @param itemKnown   whether an item id names a loaded item, matched without regard to case the
     *                    way the engine matches a member at runtime
     * @param statKnown   whether a stat channel id is registered
     * @param effectKnown whether an effect id names a loaded {@code EntityEffect}
     * @param effectFacts what a loaded effect carries ({@link EffectFacts}), null when it cannot tell
     * @param baseMax     a registered channel's base maximum, {@code NaN} when it cannot be read
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<GearSetAsset> sets, @Nonnull Predicate<String> itemKnown,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> effectKnown,
            @Nonnull Function<String, EffectFacts> effectFacts, @Nonnull ToDoubleFunction<String> baseMax) {
        List<Finding> out = new ArrayList<>();
        GearSetJoins.Folded folded = GearSetJoins.fold(sets);
        Map<String, GearSetAsset> joined = new HashMap<>();
        for (GearSetAsset set : folded.sets()) {
            joined.put(GearSetKeys.setId(set.getId()), set);
        }
        Map<String, GearSetJoins.Dropped> dropped = new HashMap<>();
        for (GearSetJoins.Dropped drop : folded.dropped()) {
            dropped.put(GearSetKeys.setId(drop.layerId()), drop);
        }
        Map<String, GearSetAsset> asWritten = new HashMap<>();
        for (GearSetAsset set : sets) {
            if (set != null && set.getId() != null) {
                asWritten.put(GearSetKeys.setId(set.getId()), set);
            }
        }
        Badges badges = new Badges(effectKnown, effectFacts);
        for (GearSetAsset set : sets) {
            if (set == null || set.getId() == null || !set.isEnabled()
                    || dropped.containsKey(GearSetKeys.setId(set.getId()))) {
                continue;
            }
            badges.note(appliesAs(set), set.tiers());
        }
        for (GearSetAsset set : sets) {
            if (set == null || set.getId() == null || !set.isEnabled()) {
                continue;
            }
            GearSetJoins.Dropped drop = dropped.get(GearSetKeys.setId(set.getId()));
            if (drop != null) {
                out.add(droppedLayer(drop));
                continue;
            }
            checkInheritedTiers(set, asWritten, out);
            auditOne(set, joined.getOrDefault(GearSetKeys.setId(appliesAs(set)), set), itemKnown, statKnown,
                    effectKnown, baseMax, badges, out);
        }
        return out;
    }

    /** The id of the set a file applies as: its own, or for a layer the set it joins. */
    @Nonnull
    private static String appliesAs(@Nonnull GearSetAsset set) {
        return set.isLayer() ? String.valueOf(set.joinsId()) : set.getId();
    }

    /**
     * A layer whose {@code Bonuses} are its own set's, inherited through {@code Parent} (a layer that
     * names its set as its Parent and writes no Bonuses): the fold adds the set's tiers to themselves,
     * so every stat they give is doubled.
     */
    private static void checkInheritedTiers(@Nonnull GearSetAsset set, @Nonnull Map<String, GearSetAsset> asWritten,
            @Nonnull List<Finding> out) {
        if (!set.isLayer()) {
            return;
        }
        GearSetAsset target = asWritten.get(GearSetKeys.setId(String.valueOf(set.joinsId())));
        if (target != null && target != set && set.inheritsBonusesOf(target)) {
            out.add(Finding.warning(DOMAIN, LAYER_INHERITS_TARGET_TIERS, "gear set '" + set.getId() + "' joins '"
                    + target.getId() + "' and inherits its Bonuses through Parent without writing its own, so the "
                    + "fold adds those tiers to themselves and doubles every stat they give; write only the tiers "
                    + "this file adds, or drop the Parent", set.getId()));
        }
    }

    /** The one finding for a layer the fold dropped: its set is not loaded (WARNING) or is a layer itself (ERROR). */
    @Nonnull
    private static Finding droppedLayer(@Nonnull GearSetJoins.Dropped drop) {
        String where = "gear set '" + drop.layerId() + "' joins '" + drop.target() + "'";
        if (drop.reason() == GearSetJoins.Reason.MISSING_TARGET) {
            return Finding.warning(DOMAIN, UNKNOWN_JOIN_TARGET, where + ", which is no loaded gear set (its "
                    + "mod or pack may not be installed here), so it adds nothing", drop.layerId());
        }
        return Finding.error(DOMAIN, JOINS_A_LAYER, where + ", which is a layer itself (it joins a set, or is "
                + "this file); a layer joins a set that stands on its own, so this one adds nothing until it "
                + "names that set", drop.layerId());
    }

    /**
     * One file as written, against {@code whole}, the set as it applies (the file's own joined set,
     * or for a layer the joined set it joins).
     */
    private static void auditOne(@Nonnull GearSetAsset set, @Nonnull GearSetAsset whole,
            @Nonnull Predicate<String> itemKnown, @Nonnull Predicate<String> statKnown,
            @Nonnull Predicate<String> effectKnown, @Nonnull ToDoubleFunction<String> baseMax,
            @Nonnull Badges badges, @Nonnull List<Finding> out) {
        String id = set.getId() == null ? "" : set.getId();
        Set<String> members = whole.memberIds();
        List<GearSetAsset.Tier> tiers = set.tiers();

        if (!set.isLayer()) {
            if (set.titleKey() == null) {
                out.add(Finding.info(DOMAIN, UNNAMED_SET, "gear set '" + id + "' has no Text.TitleKey, so its "
                        + "notice names it by its id rather than a translated name", id));
            }
            if (members.isEmpty()) {
                out.add(Finding.error(DOMAIN, EMPTY_MEMBERS, "gear set '" + id + "' lists no Members, so no "
                        + "item can ever count toward it and no tier can ever apply", id));
            } else if (members.size() < FEWEST_SENSIBLE_MEMBERS) {
                out.add(Finding.warning(DOMAIN, TOO_FEW_MEMBERS, "gear set '" + id + "' has " + members.size()
                        + " distinct member; a set bonus for one item is that item's own stats with extra "
                        + "steps, so author the stats on the item instead", id));
            }
        }
        for (String member : authored(set.getMembers())) {
            if (!itemKnown.test(member)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_SET_MEMBER, "gear set '" + id + "' names the member '"
                        + member + "', which is no loaded item, so wearing it can never count", id));
            }
        }
        if (!set.isLayer() && whole.tiers().isEmpty()) {
            out.add(Finding.error(DOMAIN, NO_BONUSES, "gear set '" + id + "' authors no Bonuses, so completing "
                    + "it pays nothing", id));
        }
        Map<GearSetAsset.Condition, Integer> seen = new HashMap<>();
        for (int i = 0; i < tiers.size(); i++) {
            auditTier(id, i, tiers.get(i), members.size(), statKnown, effectKnown, baseMax, seen, out);
            badges.check(id, i, tiers.get(i), whole.getId(), out);
            if (set.isLayer()) {
                checkJoinedEffect(id, i, tiers.get(i), whole, out);
                checkJoinedBadge(id, i, tiers.get(i), whole, out);
            }
        }
    }

    /**
     * A layer's tier folded into a tier of its set that already holds another look: the set's look
     * stays and this one never shows ({@link GearSetJoins}).
     */
    private static void checkJoinedEffect(@Nonnull String id, int index, @Nonnull GearSetAsset.Tier tier,
            @Nonnull GearSetAsset whole, @Nonnull List<Finding> out) {
        String effect = tier.effectId();
        int at = effect == null ? -1 : GearSetJoins.matchOf(whole.tiers(), tier);
        String held = at < 0 ? null : whole.tiers().get(at).effectId();
        if (held != null && !held.equalsIgnoreCase(effect)) {
            out.add(Finding.warning(DOMAIN, JOIN_EFFECT_CLASH, "gear set '" + id + "' tier " + index
                    + " names the Effect '" + effect + "', but the tier of '" + whole.getId() + "' it joins "
                    + "already holds '" + held + "', so this look never shows; drop it, or ask for a "
                    + "different count so the tier stands on its own", id));
        }
    }

    /**
     * A layer's tier folded into a tier of its set that already names another badge: the set's badge
     * stays and this one never shows ({@link GearSetJoins}).
     */
    private static void checkJoinedBadge(@Nonnull String id, int index, @Nonnull GearSetAsset.Tier tier,
            @Nonnull GearSetAsset whole, @Nonnull List<Finding> out) {
        String badge = tier.badgeId();
        int at = badge == null ? -1 : GearSetJoins.matchOf(whole.tiers(), tier);
        String held = at < 0 ? null : whole.tiers().get(at).badgeId();
        if (held != null && !held.equalsIgnoreCase(badge)) {
            out.add(Finding.warning(DOMAIN, JOIN_BADGE_CLASH, "gear set '" + id + "' tier " + index
                    + " names the Badge '" + badge + "', but the tier of '" + whole.getId() + "' it joins "
                    + "already names '" + held + "', so this tile never shows; drop it, or ask for a "
                    + "different count so the tier stands on its own", id));
        }
    }

    /**
     * The badge checks: what every enabled file's tiers name (noted first, over the whole store, so a
     * clash is found whichever file comes first), then each badge weighed against it and against the
     * effect it names.
     */
    private static final class Badges {

        private final Predicate<String> effectKnown;
        private final Function<String, EffectFacts> effectFacts;
        /** Each badge id, lower-cased, to the folded ids of the sets naming it. */
        private final Map<String, Set<String>> owners = new HashMap<>();
        /** Every tier's Effect id, lower-cased. */
        private final Set<String> looks = new HashSet<>();

        Badges(@Nonnull Predicate<String> effectKnown, @Nonnull Function<String, EffectFacts> effectFacts) {
            this.effectKnown = effectKnown;
            this.effectFacts = effectFacts;
        }

        void note(@Nonnull String setId, @Nonnull List<GearSetAsset.Tier> tiers) {
            for (GearSetAsset.Tier tier : tiers) {
                String badge = tier.badgeId();
                if (badge != null) {
                    owners.computeIfAbsent(badge.toLowerCase(Locale.ROOT), k -> new HashSet<>())
                            .add(GearSetKeys.setId(setId));
                }
                String look = tier.effectId();
                if (look != null) {
                    looks.add(look.toLowerCase(Locale.ROOT));
                }
            }
        }

        /** One tier of file {@code id}, which applies as set {@code setId}. */
        void check(@Nonnull String id, int index, @Nonnull GearSetAsset.Tier tier, @Nonnull String setId,
                @Nonnull List<Finding> out) {
            String badge = tier.badgeId();
            if (badge == null) {
                return;
            }
            String where = "gear set '" + id + "' tier " + index + " names the Badge '" + badge + "'";
            String key = badge.toLowerCase(Locale.ROOT);
            if (GearSetBadges.isDefaultTile(badge)) {
                out.add(Finding.warning(DOMAIN, BADGE_ID_CLASH, where + ", one of the library's default set "
                        + "tiles, which the engine seats for sets naming no badge, so two sets may end up on one "
                        + "tile; give the set a badge effect of its own", id));
            } else if (looks.contains(key)) {
                out.add(Finding.warning(DOMAIN, BADGE_ID_CLASH, where + ", which a tier also names as its "
                        + "Effect; a badge must be dedicated to its set's tile, so give it an effect of its own",
                        id));
            } else {
                for (String other : owners.getOrDefault(key, Set.of())) {
                    if (!other.equals(GearSetKeys.setId(setId))) {
                        out.add(Finding.warning(DOMAIN, BADGE_ID_CLASH, where + ", which the gear set '" + other
                                + "' names as its Badge too, so a player wearing both sees one tile for two "
                                + "sets; give each set a badge of its own", id));
                        break;
                    }
                }
            }
            if (!effectKnown.test(badge)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_SET_BADGE, where + ", which is no loaded EntityEffect "
                        + "(its mod or pack may not be installed here), so the set shows no tile while that "
                        + "tier leads", id));
                return;
            }
            EffectFacts facts;
            try {
                facts = effectFacts.apply(badge);
            } catch (Throwable t) {
                facts = null;
            }
            if (facts == null) {
                return;
            }
            if (!facts.icon()) {
                out.add(Finding.warning(DOMAIN, BADGE_WITHOUT_ICON, where + ", which carries no "
                        + "StatusEffectIcon, so it shows no tile on the buff bar; give the effect an icon", id));
            }
            if (!facts.infinite()) {
                out.add(Finding.warning(DOMAIN, BADGE_NOT_INFINITE, where + ", which is not Infinite, so the "
                        + "tile runs out while the set is still worn and comes back only at the next change; "
                        + "author it Infinite", id));
            }
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

    /**
     * Whether {@code effectId} names a loaded {@code EntityEffect}, asked of the engine's asset map
     * (which matches an id ignoring case); true when it cannot tell, since that is not "missing".
     * The audit and the recompute's look record both ask this.
     */
    static boolean effectKnown(@Nonnull String effectId) {
        try {
            return EntityEffect.getAssetMap().getIndex(effectId) != Integer.MIN_VALUE;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /**
     * What the loaded {@code EntityEffect} {@code effectId} carries, its Parent's leaves resolved by
     * the engine's own load; null when it is not loaded or cannot be read.
     */
    @Nullable
    private static EffectFacts effectFactsOf(@Nonnull String effectId) {
        try {
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EntityEffect effect = index == Integer.MIN_VALUE ? null : EntityEffect.getAssetMap().getAsset(index);
            if (effect == null) {
                return null;
            }
            String icon = effect.getStatusEffectIcon();
            return new EffectFacts(icon != null && !icon.isBlank(), effect.isInfinite());
        } catch (Throwable t) {
            return null;
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
