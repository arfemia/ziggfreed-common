package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackValidator;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The stance audit, filed under the track audit's domain (inside {@code TrackValidator.audit}); every finding is a
 * WARNING, since a stance or a pick naming one may come from a mod this server does not run:
 *
 * <ul>
 *   <li>{@link #UNKNOWN_STANCE}: a pick naming a stance no switched-on file defines (it counts nowhere);</li>
 *   <li>{@link #UNKNOWN_STANCE_FACE}: a step whose {@code Face} names no pick, or a pick that is no face of the same
 *       stance (it counts nowhere);</li>
 *   <li>{@link #UNKNOWN_STANCE_KIND}: a face of a kind its stance's {@code Kinds} does not name (held under no cap,
 *       adding nothing while held);</li>
 *   <li>{@link #STANCE_WITHOUT_SLOT}: a stance with no slot row (nothing can be held);</li>
 *   <li>{@link #UNKNOWN_SLOT_PICK}: a slot opening on a pick no file defines (it never opens);</li>
 *   <li>{@link #STANCE_FACE_OVER_BUDGET}: a face whose live grants (the face and every step naming it, each at its
 *       full rank, an either-or ({@code Node}) at its largest face) add more to a stat or a grant family than its
 *       stance's {@code Budget} allows;</li>
 *   <li>{@link #TAUGHT_ONLY_CHOICE}: a choice written {@code TaughtOnly}, which nobody can ever be given (a teach
 *       gives a pick bought outright).</li>
 * </ul>
 * A slot's {@code Requires} is checked by the one gate audit ({@code GateValidator}).
 */
public final class StanceValidator {

    public static final String UNKNOWN_STANCE = "UNKNOWN_STANCE";
    public static final String UNKNOWN_STANCE_FACE = "UNKNOWN_STANCE_FACE";
    public static final String UNKNOWN_STANCE_KIND = "UNKNOWN_STANCE_KIND";
    public static final String STANCE_WITHOUT_SLOT = "STANCE_WITHOUT_SLOT";
    public static final String UNKNOWN_SLOT_PICK = "UNKNOWN_SLOT_PICK";
    public static final String STANCE_FACE_OVER_BUDGET = "STANCE_FACE_OVER_BUDGET";
    public static final String TAUGHT_ONLY_CHOICE = "TAUGHT_ONLY_CHOICE";

    private static final String DOMAIN = TrackValidator.DOMAIN;

    private StanceValidator() {
    }

    /** The engine walk over every switched-on stance and the live picks. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            List<TrackStanceAsset> stances = TrackStanceConfig.getInstance().liveStances();
            List<Finding> out = new ArrayList<>(audit(stances, TrackPicks.all(),
                    id -> TrackStanceConfig.getInstance().live(id)));
            for (TrackStanceAsset stance : stances) {
                for (SlotRow row : stance.slots()) {
                    out.addAll(GateValidator.validate(row.requires(), DOMAIN, stance.getId() + "/" + row.key(),
                            "stance slot", GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                }
            }
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[track] the stance audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core: {@code stances} (the switched-on ones) against {@code picks} (by lower-cased id), each stance id
     * a pick names resolved through {@code live} (null for one unknown or off).
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<TrackStanceAsset> stances,
            @Nonnull Map<String, TrackPicks.PickDef> picks, @Nonnull Function<String, TrackStanceAsset> live) {
        List<Finding> out = new ArrayList<>();
        for (TrackStanceAsset stance : stances) {
            String id = stance.getId();
            if (stance.slots().isEmpty()) {
                out.add(Finding.warning(DOMAIN, STANCE_WITHOUT_SLOT, "stance '" + id + "' writes no slot row, so no "
                        + "face can be held in it", id));
            }
            for (SlotRow row : stance.slots()) {
                if (row.pick() != null && !picks.containsKey(row.pick())) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_SLOT_PICK, "stance '" + id + "' slot '" + row.id()
                            + "' opens on the pick '" + row.pick() + "', which no loaded file defines, so it never "
                            + "opens", id));
                }
            }
        }
        Map<String, List<TrackPicks.PickDef>> steps = new HashMap<>();
        for (TrackPicks.PickDef pick : picks.values()) {
            TrackPickAsset asset = pick.asset();
            if (asset.taughtOnly() && pick.isChoice()) {
                out.add(Finding.warning(DOMAIN, TAUGHT_ONLY_CHOICE, "track pick '" + asset.getId() + "' is a choice "
                        + "written TaughtOnly, which nobody can be given: a teach gives a pick bought outright",
                        asset.getId()));
            }
            String stanceId = asset.stanceId();
            if (stanceId == null) {
                continue;
            }
            TrackStanceAsset stance = live.apply(stanceId);
            if (stance == null) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_STANCE, "track pick '" + asset.getId() + "' belongs to the "
                        + "stance '" + stanceId + "', which no switched-on file defines, so it counts nowhere",
                        asset.getId()));
                continue;
            }
            if (asset.isStanceFace()) {
                String kind = asset.stanceKind();
                if (kind != null && stance.kind(kind) == null) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_STANCE_KIND, "track pick '" + asset.getId() + "' is a "
                            + "face of the kind '" + kind + "', which the stance '" + stance.getId() + "' names no "
                            + "Kinds entry for, so it is held under no cap and adds nothing while held",
                            asset.getId()));
                }
            } else if (!StanceRules.isFaceOf(picks.get(asset.stanceFace()), stanceId)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_STANCE_FACE, "track pick '" + asset.getId() + "' lives with "
                        + "the face '" + asset.stanceFace() + "', which is no face of the stance '" + stance.getId()
                        + "', so it counts nowhere", asset.getId()));
            }
            steps.computeIfAbsent(asset.stanceFace(), k -> new ArrayList<>()).add(pick);
        }
        for (Map.Entry<String, List<TrackPicks.PickDef>> face : steps.entrySet()) {
            TrackPicks.PickDef own = picks.get(face.getKey());
            TrackStanceAsset stance = own == null ? null : live.apply(own.asset().stanceId());
            if (stance == null || !StanceRules.isFaceOf(own, TrackKey.normalize(stance.getId()))) {
                continue;
            }
            stance.budget().forEach((key, most) -> {
                double total = peak(face.getValue(), key);
                if (total > most + 1e-9) {
                    out.add(Finding.warning(DOMAIN, STANCE_FACE_OVER_BUDGET, "stance '" + stance.getId()
                            + "' face '" + own.asset().getId() + "' adds " + (Double.isInfinite(total) ? "without end"
                            : String.format(Locale.ROOT, "%.2f", total)) + " to '" + key + "' (the face and every "
                            + "step naming it, at full rank, an either-or at its largest face), past its Budget of "
                            + String.format(Locale.ROOT, "%.2f", most), own.asset().getId()));
                }
            });
        }
        return out;
    }

    /**
     * The most {@code steps} (one face and every step naming it) add to {@code key} (a stat id, or a family of a
     * stat's {@code ZigGrantFamily} tag): each bought step at its full rank ({@code Repeat.Max}, no end for an
     * unlimited one), the faces of one either-or ({@code Node}) at the largest. A choice step counts nothing here.
     */
    static double peak(@Nonnull List<TrackPicks.PickDef> steps, @Nonnull String key) {
        double total = 0.0;
        Map<String, Double> byNode = new LinkedHashMap<>();
        for (TrackPicks.PickDef step : steps) {
            if (step.isChoice()) {
                continue;
            }
            double each = amountOn(step.asset().grants(), key);
            if (each == 0.0) {
                continue;
            }
            int ranks = step.asset().repeatMax();
            double value = ranks == Integer.MAX_VALUE ? (each > 0 ? Double.POSITIVE_INFINITY : 0.0)
                    : each * ranks;
            String node = step.asset().node();
            if (node == null) {
                total += value;
            } else {
                byNode.merge(node, value, Math::max);
            }
        }
        for (double value : byNode.values()) {
            total += value;
        }
        return total;
    }

    /** What one take of {@code grants} adds to {@code key}: each Stat grant on that stat, or in that family. */
    private static double amountOn(@Nonnull List<Grant> grants, @Nonnull String key) {
        double total = 0.0;
        for (Grant grant : grants) {
            if (!(grant instanceof StatGrant stat) || stat.stat() == null) {
                continue;
            }
            if (stat.stat().equalsIgnoreCase(key) || inFamily(stat, key)) {
                total += stat.add() + stat.percent();
            }
        }
        return total;
    }

    private static boolean inFamily(@Nonnull StatGrant grant, @Nonnull String family) {
        for (String own : families(grant)) {
            if (own != null && own.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    /** A stat grant's families, read off the loaded stat; none where the stat cannot be read. */
    @Nonnull
    private static List<String> families(@Nullable StatGrant grant) {
        try {
            return grant == null ? List.of() : grant.families();
        } catch (Throwable t) {
            return List.of();
        }
    }
}
