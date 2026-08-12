package com.ziggfreed.common.factor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.codec.TagMatch;
import com.ziggfreed.common.entity.HeldItemUtil;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

/**
 * The PORTABLE {@code hytale:} factor standard library: straight reads of NATIVE engine data about
 * the context's own subject, meaningful in any mod with no further setup. Register them into your
 * own {@link FactorRegistry} once at setup with {@link #registerInto}.
 *
 * <p><b>The namespace names the vocabulary's OWNER, not whoever registered it.</b> Every id here
 * addresses data the ENGINE owns - a stat channel's value, an item's tool spec, an item's tags -
 * so two mods converging on {@code hytale:tool_quality} is agreement rather than a collision, and
 * an author can tell from the id alone that the factor is portable. A factor that only makes sense
 * inside one mod's own machinery belongs under that mod's own namespace instead.
 *
 * <p><b>Every one of them answers {@code null} rather than {@code 0} when it cannot answer</b>
 * (no subject, nothing held, an unregistered stat channel), which is what lets a gate authored
 * against them fail CLOSED - see {@link FactorProvider} and {@link FactorCondition#accepts}.
 *
 * <p>The ids, and what each {@code Param} addresses:
 * <table>
 *   <caption>The portable factor ids</caption>
 *   <tr><th>Id</th><th>Param</th><th>Value</th></tr>
 *   <tr><td>{@code hytale:stat}</td><td>a registered {@code EntityStatType} id</td>
 *       <td>the subject's EFFECTIVE (folded max) value for that channel</td></tr>
 *   <tr><td>{@code hytale:tool_power}</td><td>a native {@code GatherType} name (omit for the best
 *       of any type)</td><td>the held tool's gather power</td></tr>
 *   <tr><td>{@code hytale:tool_tier}</td><td>a native {@code GatherType} name (omit for the best
 *       of any type)</td><td>the held tool's per-type harvest-TIER GATE ({@code ItemToolSpec
 *       .Quality}) - a different number from {@code tool_quality} below</td></tr>
 *   <tr><td>{@code hytale:tool_durability_percent}</td><td>ignored</td><td>0..100 of the held stack</td></tr>
 *   <tr><td>{@code hytale:tool_quality}</td><td>ignored</td><td>the held item's quality tier value</td></tr>
 *   <tr><td>{@code hytale:tool_item_level}</td><td>ignored</td><td>the held item's native item level</td></tr>
 *   <tr><td>{@code hytale:held_tag}</td><td>{@code family:value}, or a bare value</td>
 *       <td>1 when the held item carries it, else 0</td></tr>
 *   <tr><td>{@code hytale:held_item}</td><td>an item id</td><td>1 on a match, else 0</td></tr>
 * </table>
 *
 * <p>The FOUR tool-shaped axes are deliberately four, and none subsumes another. {@code tool_power}
 * is the FUNCTIONAL read but SATURATES across a family's upper tiers, so it cannot separate the top
 * rungs; {@code tool_tier} is the per-job GATE the engine itself enforces before a spec's power
 * counts at all (a spec below a block's required tier never damages it, whatever its power reads);
 * {@code tool_quality} is the authored number that ORDERS rarity tiers (so a pack shipping its own
 * tier participates for free) but cannot separate two tools inside one tier;
 * {@code tool_item_level} separates same-tier tools but does NOT track rarity, so it belongs as a
 * small tiebreaker rather than a leading term. Weighting them is the author's call.
 *
 * <p><b>{@code tool_power} and {@code tool_tier} are the only two that take a {@code Param}, and
 * that is the engine's shape rather than a choice made here.</b> A tool's gather powers AND its
 * harvest-tier gates are each one native {@code ItemToolSpec} field PER {@code GatherType}, so
 * neither question is meaningful until an author says which job is being asked about; rarity, item
 * level and durability are each authored once for the whole item (or, for durability, carried on
 * the stack), so there is nothing inside them to address and a {@code Param} on those three is
 * simply ignored rather than narrowing anything.
 *
 * <p>A consumer whose own evaluation site resolves these differently (a work session holding a
 * tool snapshot rather than reading the live hand) registers its OWN provider under the SAME id in
 * its OWN registry - same vocabulary, context-appropriate resolution.
 */
public final class HytaleFactors {

    /** {@code hytale:stat} - the subject's effective value for the {@code EntityStatType} named by Param. */
    public static final String STAT = "hytale:stat";
    /** {@code hytale:tool_power} - the held tool's gather power for the GatherType named by Param. */
    public static final String TOOL_POWER = "hytale:tool_power";
    /**
     * {@code hytale:tool_tier} - the held tool's harvest-TIER GATE ({@code ItemToolSpec.Quality})
     * for the GatherType named by Param. A different number from {@link #TOOL_QUALITY}.
     */
    public static final String TOOL_TIER = "hytale:tool_tier";
    /** {@code hytale:tool_durability_percent} - the held stack's remaining durability, 0..100. */
    public static final String TOOL_DURABILITY_PERCENT = "hytale:tool_durability_percent";
    /** {@code hytale:tool_quality} - the held item's native quality-tier ordering value. */
    public static final String TOOL_QUALITY = "hytale:tool_quality";
    /** {@code hytale:tool_item_level} - the held item's native item level. */
    public static final String TOOL_ITEM_LEVEL = "hytale:tool_item_level";
    /** {@code hytale:held_tag} - 1 when the held item carries the tag named by Param, else 0. */
    public static final String HELD_TAG = "hytale:held_tag";
    /** {@code hytale:held_item} - 1 when the held item id equals Param, else 0. */
    public static final String HELD_ITEM = "hytale:held_item";

    private static final Double YES = 1.0;
    private static final Double NO = 0.0;

    /**
     * Stat channel ids already reported as unregistered, so a typo'd {@code Param} is named ONCE
     * rather than on every evaluation. A miss is never cached by {@link StatIndexCache}, so a
     * channel registered later still resolves normally; only the log line is latched.
     */
    private static final Set<String> WARNED_UNKNOWN_STAT = ConcurrentHashMap.newKeySet();

    private HytaleFactors() {
    }

    /**
     * Register every id above into {@code registry}, attributed to {@code owner}. Idempotent -
     * re-registering the same provider instance is silent - and safe to call from any consumer
     * that wants the portable vocabulary in its own registry.
     */
    public static void registerInto(@Nonnull FactorRegistry registry, @Nullable String owner) {
        registry.register(STAT, owner, HytaleFactors::resolveStat);
        registry.register(TOOL_POWER, owner, HytaleFactors::resolveToolPower);
        registry.register(TOOL_TIER, owner, HytaleFactors::resolveToolTier);
        registry.register(TOOL_DURABILITY_PERCENT, owner, HytaleFactors::resolveToolDurabilityPercent);
        registry.register(TOOL_QUALITY, owner, HytaleFactors::resolveToolQuality);
        registry.register(TOOL_ITEM_LEVEL, owner, HytaleFactors::resolveToolItemLevel);
        registry.register(HELD_TAG, owner, HytaleFactors::resolveHeldTag);
        registry.register(HELD_ITEM, owner, HytaleFactors::resolveHeldItem);
    }

    // ==================== providers ====================

    /**
     * The subject's EFFECTIVE value for the native stat channel named by {@code Param} - the
     * folded max (base plus every live modifier), never the current value, so a formula reads the
     * player's capability rather than how hurt they are right now. Null when there is no live
     * subject, no {@code Param}, or nothing registered under that channel id. That last case is
     * the only one that is an authoring mistake rather than a normal evaluation shape, so it is
     * named in the log once per channel id.
     */
    @Nullable
    static Double resolveStat(@Nonnull FactorContext ctx) {
        String statId = trimmed(ctx.param());
        if (statId == null || !ctx.hasLiveSubject()) {
            return null;
        }
        int index = StatIndexCache.resolve(statId);
        if (index == StatIndexCache.UNRESOLVED) {
            if (WARNED_UNKNOWN_STAT.add(statId)) {
                SafeLog.warn("[factor] " + STAT + " - no stat channel registered under '" + statId
                        + "', conditions and weights on it fail closed"
                        + " (register the channel before any content referencing it decodes)");
            }
            return null;
        }
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (store == null || subject == null) {
            return null;
        }
        EntityStatMap stats = store.getComponent(subject, EntityStatsModule.get().getEntityStatMapComponentType());
        if (stats == null) {
            return null;
        }
        EntityStatValue value = stats.get(index);
        return value == null ? null : (double) value.getMax();
    }

    /**
     * The held tool's gather power, ADDRESSED by {@code Param}.
     *
     * <p>A tool does not have "a power": the native {@code ItemToolSpec} is one entry per
     * {@code GatherType}, and a real tool carries a whole spread of them (a hatchet's authored
     * {@code Woods} power sits beside token powers for soils, rocks, and every ore tier). So
     * {@code Param} names WHICH gather type is being asked about - {@code "Woods"},
     * {@code "Rocks"}, {@code "OreMithril"} - matched case-insensitively, and only that spec counts.
     *
     * <p>With NO {@code Param} (absent or blank) it answers the AGGREGATE form instead: the BEST
     * power the tool has for any gather type, the portable "how good a tool is this at all" read.
     * Use it when the site does not care what the tool is FOR; name a gather type the moment it
     * does, because the aggregate of a pickaxe and the aggregate of a hatchet are the same kind of
     * number and say nothing about chopping.
     *
     * <p>Null when nothing is held, the held item is not a tool, or a NAMED gather type has no spec
     * on it - a tool that cannot do the job at all is not the same as one that does it badly, so a
     * formula term contributes nothing and a gate on it stays shut rather than reading a
     * fabricated {@code 0} as a real, very low power.
     */
    @Nullable
    static Double resolveToolPower(@Nonnull FactorContext ctx) {
        Item item = HeldItemUtil.heldItem(ctx.store(), ctx.subject());
        return HeldItemUtil.toolPowerFor(HeldItemUtil.toolPowersOf(item), ctx.param());
    }

    /**
     * The held tool's harvest-TIER GATE, ADDRESSED by {@code Param} - the native
     * {@code ItemToolSpec.Quality} integer {@code BlockHarvestUtils} compares against a block's
     * required {@code Gathering.Breaking.Quality} before the spec's {@code Power} counts at all. A
     * spec below the required tier never damages that block, whatever its {@code tool_power} reads,
     * so this is the GATE a formula or condition checks to ask "can this tool even do the job", where
     * {@code tool_power} answers "how well".
     *
     * <p><b>A different number from {@link #resolveToolQuality} ({@code hytale:tool_quality})</b>:
     * that id reads the held ITEM's own rarity tier, one value authored once for the whole item;
     * this id reads a per-{@code GatherType} field on the tool's own spec array. Two tools of the
     * same rarity quality can gate very differently by job, and this is the only id that says so.
     *
     * <p>Same {@code Param} contract as {@link #resolveToolPower}: a named gather type
     * (case-insensitive) selects that spec's tier alone; absent/blank asks for the AGGREGATE - the
     * BEST tier the tool reaches for any type. Null when nothing is held, the held item is not a
     * tool, or a NAMED gather type has no spec on it at all - failing to answer, not answering
     * {@code 0}, because "cannot do this job" must not read as "gated at the lowest tier".
     */
    @Nullable
    static Double resolveToolTier(@Nonnull FactorContext ctx) {
        Item item = HeldItemUtil.heldItem(ctx.store(), ctx.subject());
        return HeldItemUtil.toolTierFor(HeldItemUtil.toolTiersOf(item), ctx.param());
    }

    /**
     * The held stack's remaining durability as a percent in {@code [0, 100]}; an item that tracks
     * no durability reads 100 (it can never be worn). Null when nothing is held.
     *
     * <p><b>{@code Param} is ignored.</b> Durability is one number on the STACK, not per gather
     * type, so there is nothing to address within it.
     */
    @Nullable
    static Double resolveToolDurabilityPercent(@Nonnull FactorContext ctx) {
        return HeldItemUtil.durabilityPercentOf(HeldItemUtil.heldStack(ctx.store(), ctx.subject()));
    }

    /**
     * The held item's native quality-tier ordering value. Null when nothing is held.
     *
     * <p><b>{@code Param} is ignored.</b> This is the item's own RARITY - its {@code Quality} field
     * names one {@code ItemQuality} asset for the whole item - so it is a single value with nothing
     * to address within it.
     */
    @Nullable
    static Double resolveToolQuality(@Nonnull FactorContext ctx) {
        return HeldItemUtil.qualityValueOf(HeldItemUtil.heldItem(ctx.store(), ctx.subject()));
    }

    /**
     * The held item's native item level. Null when nothing is held.
     *
     * <p><b>{@code Param} is ignored.</b> {@code ItemLevel} is one integer authored on the item, not
     * per gather type.
     */
    @Nullable
    static Double resolveToolItemLevel(@Nonnull FactorContext ctx) {
        return HeldItemUtil.itemLevelOf(HeldItemUtil.heldItem(ctx.store(), ctx.subject()));
    }

    /**
     * {@code 1} when the held item carries the tag named by {@code Param}, else {@code 0}. Write
     * the {@code Param} as {@code "<family>:<value>"} to pin the family ({@code "Family:Hatchet"}),
     * or as a bare value to accept it under ANY family. Matching is case-insensitive through the
     * shared tag matcher. Null when nothing is held or no {@code Param} was authored - so a gate
     * on a tag is shut when the hand is empty, rather than reading as a definite "no".
     */
    @Nullable
    static Double resolveHeldTag(@Nonnull FactorContext ctx) {
        String tag = trimmed(ctx.param());
        String itemId = HeldItemUtil.heldItemId(ctx.store(), ctx.subject());
        if (tag == null || itemId == null) {
            return null;
        }
        Map<String, String[]> actual = HeldItemUtil.rawTagsOf(itemId);
        if (actual.isEmpty()) {
            return NO;
        }
        int colon = tag.indexOf(':');
        if (colon > 0 && colon < tag.length() - 1) {
            String family = tag.substring(0, colon).trim();
            String value = tag.substring(colon + 1).trim();
            return matchesFamily(actual, family, value) ? YES : NO;
        }
        for (String family : actual.keySet()) {
            if (matchesFamily(actual, family, tag)) {
                return YES;
            }
        }
        return NO;
    }

    /**
     * {@code 1} when the held item's id equals {@code Param} (case-insensitive), else {@code 0}.
     * Null when nothing is held or no {@code Param} was authored.
     */
    @Nullable
    static Double resolveHeldItem(@Nonnull FactorContext ctx) {
        String wanted = trimmed(ctx.param());
        String itemId = HeldItemUtil.heldItemId(ctx.store(), ctx.subject());
        if (wanted == null || itemId == null) {
            return null;
        }
        return wanted.equalsIgnoreCase(itemId) ? YES : NO;
    }

    // ==================== helpers ====================

    /**
     * One family/value probe through the shared {@code TagMatch} matcher, so the tag semantics a
     * consumer's own authored tag leaves use are the same ones this factor answers with. The
     * FAMILY key is resolved case-insensitively first, because the matcher itself keys the map
     * exactly while an author writing a factor {@code Param} by hand has no reason to know the
     * asset's own casing.
     */
    private static boolean matchesFamily(@Nonnull Map<String, String[]> actual,
            @Nonnull String family, @Nonnull String value) {
        if (family.isBlank() || value.isBlank()) {
            return false;
        }
        for (String key : actual.keySet()) {
            if (key != null && key.equalsIgnoreCase(family)
                    && TagMatch.matches(Map.of(key, new String[]{value}), actual)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
