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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
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
 * naming no known trigger, or any file naming an effect, a stat, a RootInteraction, an effect family, a counter or a
 * damage cause this server does not have (each would do nothing where it should act). A WARNING: a proc that can
 * never run as written (no actions, a {@code Hit} or {@code Like} on a moment that carries no hit, an {@code Ability}
 * on one that names no move, an effect trigger naming no effect, a {@code Like} no damage line names, an unknown
 * {@code Overlap}, a coming hit's or a block's field on another moment), a group that does nothing as written (an
 * {@code Area} reaching no one, a {@code Push} moving nothing, an {@code Effect} on a trigger that never reads it, a
 * per-foe counter's rows, a {@code Soak}, {@code Heal} or {@code GuardCost} with nothing to do, a {@code Reach} or
 * {@code OnFoe} no one ever wears), and a proc or held effect whose {@code ziggfreedcommon:track_pick} names no pick
 * on its track, which no player can take, so nothing reaches it.
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
    public static final String ABILITY_WITHOUT_MOVE = "ABILITY_WITHOUT_MOVE";
    public static final String EFFECT_UNNAMED = "EFFECT_UNNAMED";
    public static final String EFFECT_WITHOUT_MOMENT = "EFFECT_WITHOUT_MOMENT";
    public static final String AREA_NO_REACH = "AREA_NO_REACH";
    public static final String AREA_TOO_WIDE = "AREA_TOO_WIDE";
    public static final String UNKNOWN_AROUND = "UNKNOWN_AROUND";
    public static final String PUSH_NOTHING = "PUSH_NOTHING";
    public static final String PER_TARGET_ROWS = "PER_TARGET_ROWS";
    public static final String UNKNOWN_CAUSE = "UNKNOWN_CAUSE";
    public static final String COMING_ONLY = "COMING_ONLY";
    public static final String BLOCK_ONLY = "BLOCK_ONLY";
    public static final String GROUP_NOTHING = "GROUP_NOTHING";
    public static final String ON_FOE_NOT_PER_TARGET = "ON_FOE_NOT_PER_TARGET";
    public static final String REACH_NOBODY = "REACH_NOBODY";

    /** What the audit asks of the server: each answers whether an id is known here. */
    public record Vocabulary(@Nonnull Predicate<String> effect, @Nonnull Predicate<String> stat,
            @Nonnull Predicate<String> interaction, @Nonnull Predicate<String> family,
            @Nonnull Predicate<String> line, @Nonnull BiPredicate<String, String> pick,
            @Nonnull Predicate<String> cause) {

        /** A vocabulary that knows every damage cause (one that asks nothing of a weakness's causes). */
        public Vocabulary(@Nonnull Predicate<String> effect, @Nonnull Predicate<String> stat,
                @Nonnull Predicate<String> interaction, @Nonnull Predicate<String> family,
                @Nonnull Predicate<String> line, @Nonnull BiPredicate<String, String> pick) {
            this(effect, stat, interaction, family, line, pick, id -> true);
        }
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
                    TrackPicks::knownOnTrack,
                    id -> DamageCause.getAssetMap().getAsset(id) != null);
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
            auditReach(id, file.reach(), out);
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
        auditMoveAndEffect(id, proc, trigger, known, out);
        auditMomentFields(id, proc, trigger, out);
        if (proc.actions().isEmpty()) {
            out.add(Finding.warning(DOMAIN, NO_ACTIONS, "proc '" + id + "' has no actions, so it does nothing", id));
        }
        for (ProcAction action : proc.actions()) {
            auditNewGroups(id, action, out);
            auditThirdRoundGroups(id, action, trigger, known, out);
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

    /**
     * The proc's {@code Ability} and {@code Effect} groups: each root and effect a server must have (ERROR), an
     * {@code Ability} on a moment that names no move, an effect trigger naming no effect, and an {@code Effect} on
     * another trigger (WARNING).
     */
    private static void auditMoveAndEffect(@Nonnull String id, @Nonnull ProcAsset proc, @Nullable ProcTrigger trigger,
            @Nonnull Vocabulary known, @Nonnull List<Finding> out) {
        ProcAsset.Ability ability = proc.ability();
        if (ability != null) {
            if (trigger != null && !trigger.namesMove()) {
                out.add(Finding.warning(DOMAIN, ABILITY_WITHOUT_MOVE, "proc '" + id + "' asks which move a "
                        + trigger.id() + " came from, a moment that names none (only a hit or a cast does), so it "
                        + "never runs", id));
            }
            for (String root : ability.roots()) {
                if (!known.interaction().test(root)) {
                    out.add(Finding.error(DOMAIN, UNKNOWN_INTERACTION, "proc '" + id + "' asks for a move from the "
                            + "RootInteraction '" + root + "', which this server does not have", id));
                }
            }
        }
        ProcAsset.Effect effect = proc.effect();
        if (trigger != null && trigger.aboutEffect() && effect == null) {
            out.add(Finding.warning(DOMAIN, EFFECT_UNNAMED, "proc '" + id + "' answers " + trigger.id()
                    + " but its Effect names no effect or family, so it never runs", id));
        }
        if (effect == null) {
            return;
        }
        if (trigger != null && !trigger.aboutEffect()) {
            out.add(Finding.warning(DOMAIN, EFFECT_WITHOUT_MOMENT, "proc '" + id + "' names an Effect, which only "
                    + "EffectLanded and EffectEnded read, on " + trigger.id() + ": it is ignored", id));
        }
        for (String watched : effect.effects()) {
            effect(id, watched, known, out);
        }
        for (String family : effect.families()) {
            if (!known.family().test(family)) {
                out.add(Finding.error(DOMAIN, UNKNOWN_FAMILY, "proc '" + id + "' watches the effect family '"
                        + family + "', which this server does not have", id));
            }
        }
    }

    /** An entry's Area, Push and the rest: a reach of nothing, an unknown centre, a push that moves nothing. */
    private static void auditNewGroups(@Nonnull String id, @Nonnull ProcAction action, @Nonnull List<Finding> out) {
        ProcAction.Area area = action.area();
        if (area != null) {
            if (!(area.radius() > 0.0) || area.max() <= 0) {
                out.add(Finding.warning(DOMAIN, AREA_NO_REACH, "proc '" + id + "' has an Area with no radius or a "
                        + "Max of 0, so its target groups reach no one", id));
            }
            if (area.radiusPastCap()) {
                out.add(Finding.warning(DOMAIN, AREA_TOO_WIDE, "proc '" + id + "' reaches an Area past "
                        + (int) ProcAction.Area.RADIUS_CAP + " blocks: it is held there", id));
            }
            if (area.aroundUnknown()) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_AROUND, "proc '" + id + "' centres an Area on '"
                        + area.rawAround() + "' (Holder or Target): it is centred on the holder", id));
            }
        }
        ProcAction.Push push = action.push();
        if (push != null && push.force() == null && push.up() == null) {
            out.add(Finding.warning(DOMAIN, PUSH_NOTHING, "proc '" + id + "' has a Push with no Force and no Up, so "
                    + "it moves nothing", id));
        }
    }

    /**
     * The fields only one kind of moment reads: {@code LeavesUnder} and {@code AllyRadius} a coming hit's,
     * {@code WouldBreakGuard} a block's (WARNING on any other trigger: the proc never runs, or the field is idle).
     */
    private static void auditMomentFields(@Nonnull String id, @Nonnull ProcAsset proc, @Nullable ProcTrigger trigger,
            @Nonnull List<Finding> out) {
        if (trigger == null) {
            return;
        }
        if (!trigger.incoming() && proc.leavesUnder() != null) {
            out.add(Finding.warning(DOMAIN, COMING_ONLY, "proc '" + id + "' asks LeavesUnder on " + trigger.id()
                    + ", which is no coming hit (HitIncoming, AllyHitIncoming), so it never runs", id));
        }
        if (trigger != ProcTrigger.ALLY_HIT_INCOMING && proc.allyRadiusWritten()) {
            out.add(Finding.warning(DOMAIN, COMING_ONLY, "proc '" + id + "' writes AllyRadius on " + trigger.id()
                    + ", which only AllyHitIncoming reads: it is ignored", id));
        }
        if (trigger != ProcTrigger.BLOCKED && proc.wouldBreakGuard() != null) {
            out.add(Finding.warning(DOMAIN, BLOCK_ONLY, "proc '" + id + "' asks WouldBreakGuard on " + trigger.id()
                    + ", which is no block, so it never runs", id));
        }
    }

    /**
     * An entry's Heal, Soak and GuardCost groups and a stat change by a moment's cost: a stat a soak pays out of that
     * the server lacks (ERROR), a group on a moment that never feeds it or one that does nothing (WARNING).
     */
    private static void auditThirdRoundGroups(@Nonnull String id, @Nonnull ProcAction action,
            @Nullable ProcTrigger trigger, @Nonnull Vocabulary known, @Nonnull List<Finding> out) {
        ProcAction.Soak soak = action.soak();
        if (soak != null) {
            if (soak.onto() != null && !known.stat().test(soak.onto())) {
                out.add(Finding.error(DOMAIN, UNKNOWN_STAT, "proc '" + id + "' soaks a hit onto the stat '"
                        + soak.onto() + "', which this server does not have", id));
            }
            if (trigger != null && !trigger.incoming()) {
                out.add(Finding.warning(DOMAIN, COMING_ONLY, "proc '" + id + "' has a Soak on " + trigger.id()
                        + ", whose hit has landed already (only HitIncoming and AllyHitIncoming can lower one): it "
                        + "does nothing", id));
            }
            if (soak.isEmpty()) {
                out.add(Finding.warning(DOMAIN, GROUP_NOTHING, "proc '" + id + "' has a Soak with no Share, Leave or "
                        + "LeavePercent, so it lowers nothing", id));
            }
        }
        if (trigger != null && trigger != ProcTrigger.BLOCKED
                && (action.guardCost() != null || (action.changeStat() != null && action.changeStat().byCost()))) {
            out.add(Finding.warning(DOMAIN, BLOCK_ONLY, "proc '" + id + "' reads a block's stamina cost (GuardCost, "
                    + "ChangeStat ByCost) on " + trigger.id() + ", which costs nothing: it does nothing", id));
        }
        if (action.heal() != null && action.heal().amount() == null) {
            out.add(Finding.warning(DOMAIN, GROUP_NOTHING, "proc '" + id + "' has a Heal with no Amount, so it heals "
                    + "nothing", id));
        }
    }

    /** A held effect's Reach: one that names no kind to reach, or none at all as written (WARNING). */
    private static void auditReach(@Nonnull String id, @Nullable HeldEffectAsset.Reach reach,
            @Nonnull List<Finding> out) {
        if (reach == null) {
            return;
        }
        if (reach.reachesNoKind() || !(reach.radius() > 0.0) || Math.max(reach.max(), reach.min()) <= 0) {
            out.add(Finding.warning(DOMAIN, REACH_NOBODY, "held effect '" + id + "' has a Reach naming neither "
                    + "Allies nor Taunted, or with no radius or a Max of 0, so it reaches no one", id));
        }
        if (reach.radiusPastCap()) {
            out.add(Finding.warning(DOMAIN, AREA_TOO_WIDE, "held effect '" + id + "' reaches past "
                    + (int) HeldEffectAsset.Reach.RADIUS_CAP + " blocks: it is held there", id));
        }
    }

    private static void auditCounter(@Nonnull String id, @Nonnull CounterAsset counter, @Nonnull Vocabulary known,
            @Nonnull List<Finding> out) {
        if (counter.perTarget() && (!counter.perStack().isEmpty() || !counter.effects().isEmpty())) {
            out.add(Finding.warning(DOMAIN, PER_TARGET_ROWS, "counter '" + id + "' keeps its stacks per foe "
                    + "(PerTarget), so its PerStack rows and its Effects are never given or shown", id));
        }
        CounterAsset.OnFoe onFoe = counter.rawOnFoe();
        if (onFoe != null) {
            if (!counter.perTarget()) {
                out.add(Finding.warning(DOMAIN, ON_FOE_NOT_PER_TARGET, "counter '" + id + "' has OnFoe marks but "
                        + "keeps one count for the holder, not one per foe (PerTarget), so no foe ever wears them",
                        id));
            }
            for (String effect : onFoe.effects()) {
                if (!known.effect().test(effect)) {
                    out.add(Finding.error(DOMAIN, UNKNOWN_EFFECT, "counter '" + id + "' marks its foes with the "
                            + "effect '" + effect + "', which this server does not have", id));
                }
            }
            CounterAsset.Weakness weakness = onFoe.weakness();
            if (weakness != null) {
                for (String cause : weakness.causes()) {
                    if (!known.cause().test(cause)) {
                        out.add(Finding.error(DOMAIN, UNKNOWN_CAUSE, "counter '" + id + "' weakens its foes to the "
                                + "damage cause '" + cause + "', which this server does not have", id));
                    }
                }
                if (weakness.causes().isEmpty() || weakness.perStack() == null) {
                    out.add(Finding.warning(DOMAIN, GROUP_NOTHING, "counter '" + id + "' has a Weakness with no "
                            + "Causes or no PerStack, so its foes take nothing more", id));
                }
            }
        }
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
