package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.instance.dungeon.omen.DungeonOmenAsset;
import com.ziggfreed.common.instance.dungeon.omen.DungeonOmenConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The boot audit's pass over the replay hooks, filed under the dungeon domain beside {@link DungeonValidator}'s: every
 * enabled dungeon's {@code Tier} and {@code Omens}, and every loaded omen. Every finding is a WARNING, since an id may
 * belong to a pack this server does not run:
 * <ul>
 *   <li>{@link #UNKNOWN_TIER_OPENS}: {@code Tier.Opens} names no loaded dungeon, so the tier never opens;</li>
 *   <li>{@link #TIER_LOOP}: following {@code Tier.Opens} down comes back to the tier, so its ladder has no bottom;</li>
 *   <li>{@link #UNKNOWN_DUNGEON_OMEN}: {@code Omens.Pool} names an omen no loaded file defines, which no week
 *       draws;</li>
 *   <li>{@link #NO_OMENS_TO_DRAW}: {@code Omens.Count} asks for omens and the pool holds none that is loaded;</li>
 *   <li>{@link #UNKNOWN_OMEN_EFFECT}: an omen names an effect no loaded file defines, which it skips;</li>
 *   <li>{@link #LASTING_PLAYER_OMEN}: an omen's {@code Players.Effects} names an effect with no end (Infinite), which
 *       a player who leaves while it is being renewed, or disconnects in the run, could keep;</li>
 *   <li>{@link #DAILY_WITHOUT_LAYOUT}: {@code Seed.Daily} on a dungeon with no {@code Area.Layout}, which pastes the
 *       same prefab every run, so the day's seed changes nothing there;</li>
 *   <li>the shared {@code UNKNOWN_TEXT_KEY} for an omen's {@code Text} keys.</li>
 * </ul>
 * {@link #audit()} is the thin engine walk and {@link #audit(Collection, Map, Predicate, Predicate, Predicate)} the
 * pure core.
 */
public final class DungeonReplayAudit {

    public static final String UNKNOWN_TIER_OPENS = "UNKNOWN_TIER_OPENS";
    public static final String TIER_LOOP = "TIER_LOOP";
    public static final String UNKNOWN_DUNGEON_OMEN = "UNKNOWN_DUNGEON_OMEN";
    public static final String NO_OMENS_TO_DRAW = "NO_OMENS_TO_DRAW";
    public static final String UNKNOWN_OMEN_EFFECT = "UNKNOWN_OMEN_EFFECT";
    public static final String LASTING_PLAYER_OMEN = "LASTING_PLAYER_OMEN";
    public static final String DAILY_WITHOUT_LAYOUT = "DAILY_WITHOUT_LAYOUT";

    /** How far down {@code Tier.Opens} is followed before it is called a loop. */
    private static final int MAX_DEPTH = 32;

    private DungeonReplayAudit() {
    }

    /** The engine walk: the folded dungeons and omens, the loaded effects and the shipped lang keys. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(DungeonConfig.getInstance().all().values(), DungeonOmenConfig.getInstance().all(),
                    effect -> EntityEffect.getAssetMap().getAsset(effect) != null,
                    effect -> {
                        EntityEffect loaded = EntityEffect.getAssetMap().getAsset(effect);
                        return loaded != null && loaded.isInfinite();
                    },
                    TextKeyAudit.liveCatalogue());
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the tiers and omens audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param dungeons    every folded dungeon (a switched-off one is skipped, but still opens a tier above it)
     * @param omens       every folded omen, by its lower-cased id
     * @param effectKnown    whether an id names a loaded entity effect
     * @param effectLasting  whether an id names a loaded entity effect with no end (Infinite)
     * @param textShipped    whether a lang key is shipped
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<DungeonAsset> dungeons,
            @Nonnull Map<String, DungeonOmenAsset> omens, @Nonnull Predicate<String> effectKnown,
            @Nonnull Predicate<String> effectLasting, @Nonnull Predicate<String> textShipped) {
        List<Finding> out = new ArrayList<>();
        Map<String, DungeonAsset> byId = new HashMap<>();
        for (DungeonAsset dungeon : dungeons) {
            if (dungeon != null && dungeon.getId() != null) {
                byId.put(fold(dungeon.getId()), dungeon);
            }
        }
        for (DungeonAsset dungeon : dungeons) {
            if (dungeon == null || dungeon.getId() == null || !dungeon.isEnabled()) {
                continue;
            }
            auditTier(dungeon, byId, out);
            auditOmens(dungeon, omens, out);
            if (dungeon.seedDaily() && !dungeon.generated()) {
                out.add(Finding.warning(DungeonValidator.DOMAIN, DAILY_WITHOUT_LAYOUT, "dungeon '" + dungeon.getId()
                        + "' sets Seed.Daily but names no Area.Layout, so every run pastes the same prefab and the day's "
                        + "seed changes nothing; give it a layout plan, or leave Seed out", dungeon.getId()));
            }
        }
        for (DungeonOmenAsset omen : omens.values()) {
            if (omen != null && omen.getId() != null) {
                auditOmen(omen, effectKnown, effectLasting, textShipped, out);
            }
        }
        return out;
    }

    private static void auditTier(@Nonnull DungeonAsset dungeon, @Nonnull Map<String, DungeonAsset> byId,
            @Nonnull List<Finding> out) {
        String id = dungeon.getId();
        String opens = dungeon.tierOpens();
        if (opens == null) {
            return;
        }
        if (!byId.containsKey(fold(opens))) {
            out.add(Finding.warning(DungeonValidator.DOMAIN, UNKNOWN_TIER_OPENS, "dungeon '" + id + "' is opened by "
                    + "clearing '" + opens + "' (Tier.Opens), which no loaded file defines (its pack may not be "
                    + "installed here), so nobody can open it", id));
            return;
        }
        Set<String> seen = new HashSet<>();
        String at = fold(id);
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (!seen.add(at)) {
                out.add(Finding.warning(DungeonValidator.DOMAIN, TIER_LOOP, "dungeon '" + id + "' follows Tier.Opens "
                        + "back round to a tier it already passed, so its ladder has no lowest tier; point the "
                        + "lowest tier's Opens at nothing", id));
                return;
            }
            DungeonAsset step = byId.get(at);
            String next = step == null ? null : step.tierOpens();
            if (next == null || !byId.containsKey(fold(next))) {
                return;
            }
            at = fold(next);
        }
    }

    private static void auditOmens(@Nonnull DungeonAsset dungeon, @Nonnull Map<String, DungeonOmenAsset> omens,
            @Nonnull List<Finding> out) {
        String id = dungeon.getId();
        List<String> pool = dungeon.omenPool();
        int known = 0;
        for (String omen : pool) {
            if (omens.containsKey(fold(omen))) {
                known++;
            } else {
                out.add(Finding.warning(DungeonValidator.DOMAIN, UNKNOWN_DUNGEON_OMEN, "dungeon '" + id + "' draws its "
                        + "omens from '" + omen + "' (Omens.Pool), which no loaded file defines under "
                        + "Server/ZiggfreedCommon/DungeonOmens/, so no week draws it", id));
            }
        }
        if (dungeon.omenCount() > 0 && (pool.isEmpty() ? omens.isEmpty() : known == 0)) {
            out.add(Finding.warning(DungeonValidator.DOMAIN, NO_OMENS_TO_DRAW, "dungeon '" + id + "' asks each run to "
                    + "meet " + dungeon.omenCount() + " omen(s) (Omens.Count), but no loaded omen is in its pool, so "
                    + "its runs meet none", id));
        }
    }

    private static void auditOmen(@Nonnull DungeonOmenAsset omen, @Nonnull Predicate<String> effectKnown,
            @Nonnull Predicate<String> effectLasting, @Nonnull Predicate<String> textShipped,
            @Nonnull List<Finding> out) {
        String id = omen.getId();
        String where = "omen '" + id + "'";
        check(where + " Mobs.Effects", omen.mobEffects(), effectKnown, id, out);
        check(where + " Players.Effects", omen.playerEffects(), effectKnown, id, out);
        for (String effect : omen.playerEffects()) {
            if (known(effectKnown, effect) && lasting(effectLasting, effect)) {
                out.add(Finding.warning(DungeonValidator.DOMAIN, LASTING_PLAYER_OMEN, where + " Players.Effects names "
                        + "the effect '" + effect + "', which has no end (Infinite): a player who leaves while it is "
                        + "being renewed, or disconnects in the run, could keep it. Give it a Duration of a few "
                        + "seconds; the run renews it every second", id));
            }
        }
        TextKeyAudit.check(out, DungeonValidator.DOMAIN, id, where + " Text.NameKey", omen.nameKey(), textShipped,
                "its name at the gate and on arrival shows as the raw key");
        TextKeyAudit.check(out, DungeonValidator.DOMAIN, id, where + " Text.DescriptionKey", omen.descriptionKey(),
                textShipped, "its line at the gate shows as the raw key");
    }

    private static void check(@Nonnull String where, @Nonnull List<String> effects,
            @Nonnull Predicate<String> effectKnown, @Nonnull String id, @Nonnull List<Finding> out) {
        for (String effect : effects) {
            if (!known(effectKnown, effect)) {
                out.add(Finding.warning(DungeonValidator.DOMAIN, UNKNOWN_OMEN_EFFECT, where + " names the effect '"
                        + effect + "', which no loaded file defines under Server/Entity/Effects/ (its pack may not "
                        + "be installed here), so the omen skips it", id));
            }
        }
    }

    private static boolean known(@Nonnull Predicate<String> test, @Nonnull String value) {
        try {
            return test.test(value);
        } catch (Throwable t) {
            return true;
        }
    }

    /** {@code test}'s answer; one that throws reads as no, so nothing is reported on a guess. */
    private static boolean lasting(@Nonnull Predicate<String> test, @Nonnull String value) {
        try {
            return test.test(value);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nonnull
    private static String fold(@Nullable String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
