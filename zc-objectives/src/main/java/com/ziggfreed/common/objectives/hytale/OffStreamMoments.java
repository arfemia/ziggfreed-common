package com.ziggfreed.common.objectives.hytale;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.producer.ProgressDispatch;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one warning a {@code ZigMoment} task needs that no boot check can give: a kind a loaded task names reaches the
 * quest and achievement engines straight from a mod, never through CommonLib's moment stream, so no native objective
 * ever counts it. Which mod fires a kind where is known only when it fires (a mod may count its own kinds, and some
 * built-in ones, straight into the engines), so this watches the engines rather than guessing at boot.
 *
 * <p>A dispatch tap: it sees every authoritative engine dispatch whoever made it, skips the engine half of a moment
 * the stream carried ({@link ProgressDispatch#dispatchingStreamedMoment}), and warns once per kind, naming the
 * objectives whose tasks name it, the first time a watched kind comes in any other way. The kinds watched are set by
 * the boot audit from the loaded objectives ({@link NativeObjectiveAudit#run}); until then, and on a server whose
 * objectives name no {@code ZigMoment}, a dispatch costs one empty-map read. Two kinds are never watched:
 * {@code STAT_THRESHOLD}, which a task reads for itself as it starts and loads, and {@code DEAL_DAMAGE}, which the
 * stream carries only while a track source names it, a fact the boot audit checks. Never throws.
 */
public final class OffStreamMoments implements ProgressDispatchTap {

    public static final OffStreamMoments INSTANCE = new OffStreamMoments();

    /** How many objective ids a warning names before it counts the rest. */
    private static final int NAMED_OBJECTIVES = 5;

    /** Each watched kind (any case) to the objectives whose ZigMoment tasks name it, as the boot audit found them. */
    private static volatile Map<String, List<String>> watched = Map.of();

    /** The kinds already warned about, so each is said once a process. */
    private static final Set<String> WARNED = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    private OffStreamMoments() {
    }

    /**
     * Watch the kinds {@code objectivesByKind} names, each to the objective ids whose tasks name it, replacing what
     * was watched. {@code STAT_THRESHOLD} and {@code DEAL_DAMAGE} are left out (see the class javadoc).
     */
    static void watch(@Nonnull Map<String, List<String>> objectivesByKind) {
        TreeMap<String, List<String>> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : objectivesByKind.entrySet()) {
            String kind = entry.getKey();
            if (kind == null || kind.isBlank() || ObjectiveKindRegistry.STAT_THRESHOLD.equalsIgnoreCase(kind)
                    || TrackSources.DEAL_DAMAGE.equalsIgnoreCase(kind)) {
                continue;
            }
            kinds.put(kind, List.copyOf(entry.getValue()));
        }
        watched = kinds.isEmpty() ? Map.of() : kinds;
    }

    @Override
    public void observe(@Nonnull Subject subject, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        try {
            Map<String, List<String>> kinds = watched;
            if (kinds.isEmpty() || kind == null || ProgressDispatch.dispatchingStreamedMoment()) {
                return;
            }
            List<String> objectives = kinds.get(kind);
            if (objectives != null && WARNED.add(kind)) {
                SafeLog.warn(warning(kind, objectives));
            }
        } catch (Throwable t) {
            SafeLog.fine("[objectives] the off-stream moment check failed: " + t.getMessage());
        }
    }

    /** The author's line: which objectives, which kind, why it never counts, and what to use instead. */
    @Nonnull
    static String warning(@Nonnull String kind, @Nonnull List<String> objectives) {
        StringBuilder named = new StringBuilder();
        int shown = Math.min(NAMED_OBJECTIVES, objectives.size());
        for (int i = 0; i < shown; i++) {
            named.append(i == 0 ? "" : ", ").append('\'').append(objectives.get(i)).append('\'');
        }
        if (objectives.size() > shown) {
            named.append(" and ").append(objectives.size() - shown).append(" more");
        }
        return "[objectives] objective " + named + ": a " + ZigMomentTaskAsset.TYPE + " task names the kind '" + kind
                + "', which a mod on this server counts straight into quests and achievements rather than through "
                + "CommonLib's moment stream, so a native objective never counts it; count it with a quest step or "
                + "an achievement criterion instead";
    }
}
