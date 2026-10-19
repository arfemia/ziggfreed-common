package com.ziggfreed.common.objectives.hytale;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.config.task.CountObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.ObjectiveTaskAsset;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.progress.ObjectiveComposer;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;

/**
 * A step of one of Hytale's own Objectives that counts a CommonLib moment: any objective kind the library or a
 * pack registers (a block broken, a kill, a craft, a talk, a level reached on a track), read exactly as a quest
 * step reads it.
 *
 * <pre>{@code
 * { "Type": "ZigMoment", "Count": 5,
 *   "Moment": { "Kind": "KILL_ENTITY", "Target": "Trork_Warrior", "MatchMode": "EXACT" } }
 * }</pre>
 *
 * <p>{@code Moment} is the shared objective leaf group ({@link ObjectiveLeafAsset}), decoded by its own codec, so
 * its words (Kind, Target, Targets, MatchMode, Qualifier, Qualifiers, QualifierMatchMode, Zone, TextKey) never
 * drift from a quest's, and a kind alias applies here as there ({@link ObjectiveLeafAsset#toDefBuilder}). The
 * native {@code Count} is the step's one number (1 when unwritten, the engine's own default): how many for a kind
 * that adds up, the value to reach for a value-based kind (a level, a tier), and the ceiling a fired value must
 * come in at or under for a ceiling kind; the group's own {@code Amount} is not read. The native
 * {@code DescriptionId}, {@code TaskConditions} and {@code MapMarkerPositions} work as on every vanilla task.
 *
 * <p>What reaches it: every moment CommonLib's moment stream carries ({@link MomentTaskFeed}). A kind a mod counts
 * straight into the quest and achievement engines, never through the stream, never does; the log names the
 * objective the first time such a moment arrives ({@link OffStreamMoments}). A value-based step also reads,
 * as the task starts and each time it loads, the standing value a quest step is seeded from when the quest is
 * taken ({@link ZigMomentTask#seedStanding}); a kind restated by its producer at login (a track level, a rank)
 * settles then, as a quest step taken past its target does.
 *
 * <p>One Type for every kind, rather than one per kind: the kinds are data (an {@code ObjectiveKinds} file, which
 * a pack adds to), and the engine's codec map keys a Type by class, so a Type per kind would need a Java class
 * per kind and could never cover one a pack adds. Registered at setup by {@link NativeObjectivesBootstrap}; the
 * moments reach it through {@link MomentTaskFeed}.
 */
public final class ZigMomentTaskAsset extends CountObjectiveTaskAsset {

    /** The authored {@code "Type"} name. */
    public static final String TYPE = "ZigMoment";

    /** The id the step's {@link ObjectiveDef} carries; it names nothing a player sees. */
    private static final String DEF_ID = "zig_moment";

    @Nonnull
    public static final BuilderCodec<ZigMomentTaskAsset> CODEC = BuilderCodec.builder(ZigMomentTaskAsset.class,
                    ZigMomentTaskAsset::new, CountObjectiveTaskAsset.CODEC)
            .documentation("Counts a CommonLib moment toward a native objective: any objective kind CommonLib or a "
                    + "pack registers (KILL_ENTITY, BREAK_BLOCK, CRAFT_ITEM, TALK_TO_NPC, TRACK_LEVEL and the rest), "
                    + "matched exactly as a quest step matches it. Count is the step's number, 1 when unwritten: how "
                    + "many for a kind that adds up, the value to reach for a value-based kind such as TRACK_LEVEL, "
                    + "and the most a fired value may be for a ceiling kind. It counts moments in the objective's own "
                    + "world, as every vanilla task does, and only moments CommonLib's moment stream carries: a kind "
                    + "a mod counts straight into quests and achievements never reaches it, and the server log names "
                    + "the objective the first time such a moment comes in. A value-based step also reads, as the "
                    + "task starts and each time it loads, what a quest step reads when the quest is taken (a "
                    + "STAT_THRESHOLD stat), so a player already past it completes it at once. TRACK_LEVEL, "
                    + "TRACK_TIER and REPUTATION_RANK are stated again at each login, so a task started past its "
                    + "target settles then.")
            .append(new KeyedCodec<>("Moment", ObjectiveLeafAsset.CODEC, false),
                    (task, moment) -> task.moment = moment, task -> task.moment)
            .documentation("What counts: the objective leaves every quest step carries (Kind, Target, Targets, "
                    + "MatchMode, Qualifier, Qualifiers, QualifierMatchMode, Zone, TextKey), read the same way. Its "
                    + "Amount is not read here, since Count says how many. TextKey, or else the kind's own sentence, "
                    + "is the line the player reads when the task authors no DescriptionId and ships no line under "
                    + "the objective's default task key. Unauthored, or with no Kind, the task never counts.")
            .add()
            .build();

    @Nullable
    private ObjectiveLeafAsset moment;

    /** The step as the engines read it, built on first use (after setup, so every kind alias is registered). */
    @Nullable
    private volatile ObjectiveDef def;

    private ZigMomentTaskAsset() {
    }

    /** The authored {@code Moment} group, or null when none was written. */
    @Nullable
    public ObjectiveLeafAsset getMoment() {
        return moment;
    }

    /**
     * The step as a quest's engine would read it: its run kind and target (any alias applied), its match rules and
     * zone, and {@code Count} as its amount; null when no {@code Moment} names a Kind.
     */
    @Nullable
    public ObjectiveDef def() {
        ObjectiveDef built = def;
        if (built != null) {
            return built;
        }
        ObjectiveLeafAsset leaves = moment;
        if (leaves == null || leaves.isBlank()) {
            return null;
        }
        built = leaves.toDefBuilder(DEF_ID).amount(getCount()).build();
        def = built;
        return built;
    }

    /**
     * The line this step reads with when the objective names none for it: the step's own {@code TextKey}, else its
     * kind's sentence, through the one composer every CommonLib surface uses; null when neither has words.
     */
    @Nullable
    public Message line() {
        ObjectiveDef step = def();
        ObjectiveLeafAsset leaves = moment;
        return step == null || leaves == null ? null : ObjectiveComposer.line(step, leaves.getTextKey());
    }

    @Nonnull
    @Override
    public TaskScope getTaskScope() {
        return ObjectiveTaskAsset.TaskScope.PLAYER_AND_MARKER;
    }

    /**
     * The same step when a reload keeps a running task: the count and every leaf that decides what counts. The
     * TextKey decides only wording, so a reload that retunes it keeps the player's progress.
     */
    @Override
    protected boolean matchesAsset0(ObjectiveTaskAsset task) {
        if (!super.matchesAsset0(task) || !(task instanceof ZigMomentTaskAsset other)) {
            return false;
        }
        return sameCounting(moment, other.moment);
    }

    /** Whether two {@code Moment} groups count the same moments. */
    private static boolean sameCounting(@Nullable ObjectiveLeafAsset a, @Nullable ObjectiveLeafAsset b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.getKind(), b.getKind())
                && Objects.equals(a.getTarget(), b.getTarget())
                && Arrays.equals(a.getTargets(), b.getTargets())
                && Objects.equals(a.getMatchMode(), b.getMatchMode())
                && Objects.equals(a.getQualifier(), b.getQualifier())
                && Arrays.equals(a.getQualifiers(), b.getQualifiers())
                && Objects.equals(a.getQualifierMatchMode(), b.getQualifierMatchMode())
                && Objects.equals(a.getZone(), b.getZone());
    }

    @Nonnull
    @Override
    public String toString() {
        ObjectiveDef step = def();
        return "ZigMomentTaskAsset{" + (step == null ? "no moment" : step.toString()) + "} " + super.toString();
    }
}
