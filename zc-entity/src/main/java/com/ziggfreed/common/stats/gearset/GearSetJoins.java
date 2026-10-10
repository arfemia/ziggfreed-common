package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The PURE fold behind a set file's {@code Joins}: the files the store holds in, the sets a player
 * can wear out. A file that joins nothing is a set; a file naming another set in {@code Joins} is a
 * LAYER on it, so a mod adding to a set it does not own makes one set with one notice, one entry and
 * one look, never a second set over the same members (whose full set would flip two tiers at once).
 *
 * <p>The rule, layers taken in id order so the result is the same twice:
 * <ul>
 *   <li>the layer's {@code Members} join the set's;</li>
 *   <li>each of its tiers asking exactly what a tier of the set asks (the same four minimums, see
 *       {@link GearSetAsset.Condition}) is folded INTO the first such tier: its stats are added (a
 *       channel both author keeps both lists), its line follows the tier's own
 *       ({@link GearSetAsset.Tier#titleKeys()}), and its {@code Effect} is used only when that tier
 *       carries none, so the set's look stays the set's;</li>
 *   <li>any other tier of it, a tier asking nothing included, is added after the set's own, so the
 *       set's own tier positions (and the modifier keys written under them) never move.</li>
 * </ul>
 * The set keeps its own id, name ({@code Text}), {@code Enabled} and {@code Requires}; a layer's own
 * {@code Text} is never read. A layer switched off adds nothing. A layer whose set no file answers to,
 * or whose set is a layer itself (itself included), is dropped and named in {@link Folded#dropped()},
 * which the validator reports. A set no layer joins comes back as the same instance, untouched.
 */
public final class GearSetJoins {

    /** Why a layer was dropped. */
    public enum Reason {
        /** No loaded file answers to the id it joins. */
        MISSING_TARGET,
        /** The file it joins is a layer itself, or is itself. */
        TARGET_IS_LAYER
    }

    /** A layer the fold dropped: its id, the id it joins as authored, and why. */
    public record Dropped(@Nonnull String layerId, @Nonnull String target, @Nonnull Reason reason) {
    }

    /** What the fold makes: every set with its layers in, in the order given, and every layer dropped. */
    public record Folded(@Nonnull List<GearSetAsset> sets, @Nonnull List<Dropped> dropped) {
    }

    private GearSetJoins() {
    }

    /** Fold every layer in {@code files} into the set it joins. */
    @Nonnull
    public static Folded fold(@Nonnull Collection<GearSetAsset> files) {
        Map<String, GearSetAsset> byId = new LinkedHashMap<>();
        for (GearSetAsset file : files) {
            if (file != null && file.getId() != null) {
                byId.put(GearSetKeys.setId(file.getId()), file);
            }
        }
        List<GearSetAsset> layers = new ArrayList<>();
        for (GearSetAsset file : byId.values()) {
            if (file.isLayer()) {
                layers.add(file);
            }
        }
        layers.sort(GearSetIndex.BY_ID);

        Map<String, List<GearSetAsset>> layersBySet = new LinkedHashMap<>();
        List<Dropped> dropped = new ArrayList<>();
        for (GearSetAsset layer : layers) {
            if (!layer.isEnabled()) {
                continue;
            }
            String target = String.valueOf(layer.joinsId());
            GearSetAsset set = byId.get(GearSetKeys.setId(target));
            if (set == null) {
                dropped.add(new Dropped(layer.getId(), target, Reason.MISSING_TARGET));
            } else if (set.isLayer()) {
                dropped.add(new Dropped(layer.getId(), target, Reason.TARGET_IS_LAYER));
            } else {
                layersBySet.computeIfAbsent(GearSetKeys.setId(set.getId()), k -> new ArrayList<>()).add(layer);
            }
        }

        List<GearSetAsset> sets = new ArrayList<>();
        for (GearSetAsset file : byId.values()) {
            if (file.isLayer()) {
                continue;
            }
            List<GearSetAsset> joining = layersBySet.get(GearSetKeys.setId(file.getId()));
            sets.add(joining == null ? file : join(file, joining));
        }
        return new Folded(List.copyOf(sets), List.copyOf(dropped));
    }

    /**
     * The position in {@code tiers} of the first tier asking exactly what {@code tier} asks, or -1
     * when none does; a tier asking nothing matches none. The fold and the validator both ask this.
     */
    static int matchOf(@Nonnull List<GearSetAsset.Tier> tiers, @Nonnull GearSetAsset.Tier tier) {
        if (!tier.hasCondition()) {
            return -1;
        }
        for (int i = 0; i < tiers.size(); i++) {
            GearSetAsset.Tier candidate = tiers.get(i);
            if (candidate.hasCondition() && candidate.condition().equals(tier.condition())) {
                return i;
            }
        }
        return -1;
    }

    /** {@code set} with {@code layers} folded in, in the order given. */
    @Nonnull
    private static GearSetAsset join(@Nonnull GearSetAsset set, @Nonnull List<GearSetAsset> layers) {
        List<String> members = new ArrayList<>();
        addMembers(set.getMembers(), members);
        List<Draft> drafts = new ArrayList<>();
        List<GearSetAsset.Tier> bases = new ArrayList<>();
        for (GearSetAsset.Tier tier : set.tiers()) {
            drafts.add(new Draft(tier));
            bases.add(tier);
        }
        for (GearSetAsset layer : layers) {
            addMembers(layer.getMembers(), members);
            for (GearSetAsset.Tier tier : layer.tiers()) {
                int at = matchOf(bases, tier);
                if (at < 0) {
                    drafts.add(new Draft(tier));
                    bases.add(tier);
                } else {
                    drafts.get(at).fold(tier);
                }
            }
        }
        List<GearSetAsset.Tier> tiers = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            tiers.add(draft.build());
        }
        return GearSetAsset.joined(set, members.toArray(String[]::new), tiers.toArray(GearSetAsset.Tier[]::new));
    }

    private static void addMembers(@Nullable String[] authored, @Nonnull List<String> out) {
        if (authored == null) {
            return;
        }
        for (String member : authored) {
            if (member != null) {
                out.add(member);
            }
        }
    }

    /** One tier of the set being folded: its first tier, and what the layers joined to it. */
    private static final class Draft {

        private final GearSetAsset.Tier base;
        @Nullable private String effect;
        private final Map<String, StatModifierSpec[]> stats;
        private final List<String> lines = new ArrayList<>();
        private boolean joined;

        Draft(@Nonnull GearSetAsset.Tier base) {
            this.base = base;
            this.effect = base.effectId();
            this.stats = new LinkedHashMap<>(base.statModifiers());
        }

        void fold(@Nonnull GearSetAsset.Tier layerTier) {
            joined = true;
            if (effect == null) {
                effect = layerTier.effectId();
            }
            for (Map.Entry<String, StatModifierSpec[]> entry : layerTier.statModifiers().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    stats.merge(entry.getKey(), entry.getValue(), Draft::concat);
                }
            }
            lines.addAll(layerTier.titleKeys());
        }

        @Nonnull
        GearSetAsset.Tier build() {
            return joined ? GearSetAsset.Tier.joined(base, effect, stats, lines) : base;
        }

        @Nonnull
        private static StatModifierSpec[] concat(@Nonnull StatModifierSpec[] first, @Nonnull StatModifierSpec[] second) {
            StatModifierSpec[] out = new StatModifierSpec[first.length + second.length];
            System.arraycopy(first, 0, out, 0, first.length);
            System.arraycopy(second, 0, out, first.length, second.length);
            return out;
        }
    }
}
