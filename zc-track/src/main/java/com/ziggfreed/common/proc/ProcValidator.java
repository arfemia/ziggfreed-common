package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.stats.condition.EffectFamilyConfig;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.track.TrackFactors;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The audit of procs, counters and held effects (zc's boot audit runs it, {@value #LOG_LABEL}). An ERROR: a proc
 * naming no known trigger, or any file naming an effect, a stat, a RootInteraction, an effect family or a counter this
 * server does not have (each would do nothing where it should act). A WARNING: a proc that can never run as written
 * (no actions, a {@code Hit} or {@code Like} on a moment that carries no hit, a {@code Like} no damage line names, an
 * unknown {@code Overlap}) and a proc or held effect whose {@code ziggfreedcommon:track_pick} names no pick on its
 * track, which no player can take, so nothing reaches it.
 */
public final class ProcValidator {

    /** The audit's domain. */
    public static final String DOMAIN = "proc";

    /** The label the boot audit's pass prints its lines under. */
    public static final String LOG_LABEL = "[proc] audit";

    public static final String UNKNOWN_TRIGGER = "UNKNOWN_TRIGGER";
    public static final String UNKNOWN_EFFECT = "UNKNOWN_EFFECT";
    public static final String UNKNOWN_STAT = "UNKNOWN_STAT";
    public static final String UNKNOWN_INTERACTION = "UNKNOWN_INTERACTION";
    public static final String UNKNOWN_FAMILY = "UNKNOWN_FAMILY";
    public static final String UNKNOWN_COUNTER = "UNKNOWN_COUNTER";
    public static final String NO_ACTIONS = "NO_ACTIONS";
    public static final String HIT_WITHOUT_HIT = "HIT_WITHOUT_HIT";
    public static final String LIKE_NO_LINE = "LIKE_NO_LINE";
    public static final String UNKNOWN_OVERLAP = "UNKNOWN_OVERLAP";
    public static final String UNREACHABLE_PICK = "UNREACHABLE_PICK";

    /** What the audit asks of the server: each answers whether an id is known here. */
    public record Vocabulary(@Nonnull Predicate<String> effect, @Nonnull Predicate<String> stat,
            @Nonnull Predicate<String> interaction, @Nonnull Predicate<String> family,
            @Nonnull Predicate<String> line, @Nonnull BiPredicate<String, String> pick) {
    }

    private ProcValidator() {
    }

    /** The walk over every folded file, against the loaded assets, the damage lines and the picks. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            Vocabulary vocabulary = new Vocabulary(
                    id -> EntityEffect.getAssetMap().getIndex(id) != AssetMapWithIndexes.NOT_FOUND,
                    id -> EntityStatType.getAssetMap().getIndex(id) != AssetMapWithIndexes.NOT_FOUND,
                    id -> RootInteraction.getAssetMap().getAsset(id) != null,
                    id -> EffectFamilyConfig.getInstance().has(id),
                    TypedDamageBonuses::hasLine,
                    TrackPicks::knownOnTrack);
            return audit(ProcConfig.getInstance().all(), CounterConfig.getInstance().all(),
                    HeldEffectConfig.getInstance().all(), vocabulary);
        } catch (Throwable t) {
            SafeLog.warn("[proc] the proc audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The audit over the three folds, with the server's vocabulary passed in. Pure. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Map<String, ProcAsset> procs, @Nonnull Map<String, CounterAsset> counters,
            @Nonnull Map<String, HeldEffectAsset> held, @Nonnull Vocabulary known) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, ProcAsset> entry : procs.entrySet()) {
            ProcAsset proc = entry.getValue();
            if (proc != null && !proc.isAbstract() && proc.isEnabled()) {
                auditProc(entry.getKey(), proc, counters, known, out);
            }
        }
        for (Map.Entry<String, CounterAsset> entry : counters.entrySet()) {
            CounterAsset counter = entry.getValue();
            if (counter != null && !counter.isAbstract() && counter.isEnabled()) {
                auditCounter(entry.getKey(), counter, known, out);
            }
        }
        for (Map.Entry<String, HeldEffectAsset> entry : held.entrySet()) {
            HeldEffectAsset file = entry.getValue();
            if (file == null || file.isAbstract() || !file.isEnabled()) {
                continue;
            }
            String id = entry.getKey();
            if (file.effect() == null || !known.effect().test(file.effect())) {
                out.add(Finding.error(DOMAIN, UNKNOWN_EFFECT, "held effect '" + id + "' names the effect '"
                        + file.effect() + "', which this server does not have", id));
            }
            if (file.stat() != null && !known.stat().test(file.stat())) {
                out.add(Finding.error(DOMAIN, UNKNOWN_STAT, "held effect '" + id + "' holds on the stat '"
                        + file.stat() + "', which this server does not have", id));
            }
            auditPicks("held effect", id, file.requires(), known, out);
        }
        return out;
    }

    private static void auditProc(@Nonnull String id, @Nonnull ProcAsset proc, @Nonnull Map<String, CounterAsset> counters,
            @Nonnull Vocabulary known, @Nonnull List<Finding> out) {
        ProcTrigger trigger = proc.trigger();
        if (trigger == null) {
            out.add(Finding.error(DOMAIN, UNKNOWN_TRIGGER, "proc '" + id + "' names the trigger '" + proc.rawTrigger()
                    + "', which is none of " + String.join(", ", ProcTrigger.ids()) + ", so it never runs", id));
        } else if (!trigger.struck() && (proc.like() != null || (proc.hit() != null && !proc.hit().isEmpty()))) {
            out.add(Finding.warning(DOMAIN, HIT_WITHOUT_HIT, "proc '" + id + "' asks something of a hit on "
                    + trigger.id() + ", a moment that carries none, so it never runs", id));
        }
        if (proc.like() != null && !known.line().test(proc.like())) {
            out.add(Finding.warning(DOMAIN, LIKE_NO_LINE, "proc '" + id + "' follows the damage line of '"
                    + proc.like() + "', which no damage source names, so it runs on no hit", id));
        }
        if (proc.actions().isEmpty()) {
            out.add(Finding.warning(DOMAIN, NO_ACTIONS, "proc '" + id + "' has no actions, so it does nothing", id));
        }
        for (ProcAction action : proc.actions()) {
            ProcAction.ApplyEffect apply = action.applyEffect();
            if (apply != null) {
                effect(id, apply.effect(), known, out);
                if (apply.rawOverlap() != null && apply.overlap() == null) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_OVERLAP, "proc '" + id + "' names the overlap '"
                            + apply.rawOverlap() + "' (Overwrite, Extend or Ignore): the effect's own is used", id));
                }
            }
            ProcAction.RemoveEffect remove = action.removeEffect();
            if (remove != null) {
                for (String effect : remove.effects()) {
                    effect(id, effect, known, out);
                }
                for (String family : remove.families()) {
                    if (!known.family().test(family)) {
                        out.add(Finding.error(DOMAIN, UNKNOWN_FAMILY, "proc '" + id + "' takes off the effect family '"
                                + family + "', which this server does not have", id));
                    }
                }
            }
            ProcAction.ChangeStat change = action.changeStat();
            if (change != null && (change.stat() == null || !known.stat().test(change.stat()))) {
                out.add(Finding.error(DOMAIN, UNKNOWN_STAT, "proc '" + id + "' changes the stat '" + change.stat()
                        + "', which this server does not have", id));
            }
            ProcAction.RunInteraction run = action.runInteraction();
            if (run != null && (run.interaction() == null || !known.interaction().test(run.interaction()))) {
                out.add(Finding.error(DOMAIN, UNKNOWN_INTERACTION, "proc '" + id + "' runs the RootInteraction '"
                        + run.interaction() + "', which this server does not have", id));
            }
            ProcAction.CounterChange counter = action.counter();
            if (counter != null && !liveCounter(counter.of(), counters)) {
                out.add(Finding.error(DOMAIN, UNKNOWN_COUNTER, "proc '" + id + "' changes the counter '"
                        + counter.of() + "', which no live counter file names", id));
            }
        }
        auditPicks("proc", id, proc.requires(), known, out);
    }

    private static void auditCounter(@Nonnull String id, @Nonnull CounterAsset counter, @Nonnull Vocabulary known,
            @Nonnull List<Finding> out) {
        for (CounterAsset.PerStack row : counter.perStack()) {
            if (!known.stat().test(row.stat())) {
                out.add(Finding.error(DOMAIN, UNKNOWN_STAT, "counter '" + id + "' raises the stat '" + row.stat()
                        + "', which this server does not have", id));
            }
        }
        for (String effect : counter.effects()) {
            if (!known.effect().test(effect)) {
                out.add(Finding.error(DOMAIN, UNKNOWN_EFFECT, "counter '" + id + "' shows the effect '" + effect
                        + "', which this server does not have", id));
            }
        }
        for (String raw : counter.rawClearedBy()) {
            if (ProcTrigger.parse(raw) == null) {
                out.add(Finding.error(DOMAIN, UNKNOWN_TRIGGER, "counter '" + id + "' is cleared by '" + raw
                        + "', which is none of " + String.join(", ", ProcTrigger.ids()), id));
            }
        }
        auditPicks("counter", id, counter.requires(), known, out);
    }

    private static void effect(@Nonnull String id, @Nullable String effect, @Nonnull Vocabulary known,
            @Nonnull List<Finding> out) {
        if (effect == null || !known.effect().test(effect)) {
            out.add(Finding.error(DOMAIN, UNKNOWN_EFFECT, "proc '" + id + "' names the effect '" + effect
                    + "', which this server does not have", id));
        }
    }

    private static boolean liveCounter(@Nullable String of, @Nonnull Map<String, CounterAsset> counters) {
        if (of == null) {
            return false;
        }
        for (Map.Entry<String, CounterAsset> entry : counters.entrySet()) {
            CounterAsset counter = entry.getValue();
            if (entry.getKey().equalsIgnoreCase(of) && counter != null && !counter.isAbstract()
                    && counter.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every {@code ziggfreedcommon:track_pick} condition with a {@code Min} of 1 or more at the top of
     * {@code requires} (or in an {@code AllOf} group) asks for a pick: one its track does not carry live can never be
     * met.
     */
    private static void auditPicks(@Nonnull String noun, @Nonnull String id, @Nullable GateSpec requires,
            @Nonnull Vocabulary known, @Nonnull List<Finding> out) {
        if (requires == null) {
            return;
        }
        List<FactorCondition> asked = new ArrayList<>(List.of(requires.factorsOrEmpty()));
        for (GateClause clause : requires.allOfOrEmpty()) {
            if (clause != null) {
                asked.addAll(List.of(clause.factorsOrEmpty()));
            }
        }
        for (FactorCondition condition : asked) {
            if (condition == null || !TrackFactors.PICK.equalsIgnoreCase(condition.getFactor())
                    || condition.getMin() == null || condition.getMin() < 1.0) {
                continue;
            }
            String param = condition.getParam();
            int slash = param == null ? -1 : param.indexOf('/');
            if (slash <= 0 || slash == param.length() - 1
                    || !known.pick().test(param.substring(0, slash), param.substring(slash + 1))) {
                out.add(Finding.warning(DOMAIN, UNREACHABLE_PICK, noun + " '" + id + "' asks for the pick '" + param
                        + "', which no live pick on that track is, so no player reaches it", id));
            }
        }
    }
}
