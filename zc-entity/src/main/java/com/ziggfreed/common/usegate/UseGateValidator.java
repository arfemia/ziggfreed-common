package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The use-gate audit, run once at boot by the library's boot audit: a gate that names nothing on any axis, a gate
 * whose {@code Requires} asks for nothing beyond its presence (it can refuse nobody), a {@code Feature} that is not
 * {@code <namespace>:<Feature_Id>} or names a switch no installed mod declares, and each gate's whole
 * {@code Requires} through the one gate audit ({@link GateValidator#validate}: unknown factor ids, a definite-0
 * factor with no bound, a kind's unknown parameters). Then the {@code ZigLevel} item tags ({@link ItemLevelTags}):
 * a value that is not {@code <Track>:<Level>}, a switch no installed mod declares, and each track the tags name that
 * this server does not run (once per track, those values ask nothing). A finding is advisory: every gate still
 * loads.
 */
public final class UseGateValidator {

    public static final String DOMAIN = "use_gate";

    /** The label the audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[usegate] audit";

    public static final String NOTHING_GATED = "NOTHING_GATED";
    public static final String REFUSES_NOBODY = "REFUSES_NOBODY";
    public static final String BAD_FEATURE = "BAD_FEATURE";
    public static final String UNKNOWN_FEATURE = "UNKNOWN_FEATURE";
    public static final String BAD_LEVEL_TAG = "BAD_LEVEL_TAG";
    public static final String UNKNOWN_LEVEL_TRACK = "UNKNOWN_LEVEL_TRACK";

    private UseGateValidator() {
    }

    /** The walk over every folded gate and every tagged item, against the live vocabulary. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            List<Finding> out = new ArrayList<>(audit(UseGateConfig.getInstance().all().values(),
                    GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered));
            out.addAll(auditTags(ItemLevelTags.all(), ItemLevelTags::usable));
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[usegate] the use-gate audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** One line, once the server has booted: how many gates are folded and how many items carry the tag. */
    public static void logSummary() {
        try {
            Collection<UseGateAsset> gates = UseGateConfig.getInstance().all().values();
            int on = 0;
            for (UseGateAsset gate : gates) {
                if (gate != null && gate.isEnabled()) {
                    on++;
                }
            }
            SafeLog.info("[usegate] " + gates.size() + " use gate(s) folded (" + on + " switched on), "
                    + ItemLevelTags.all().size() + " item(s) tagged " + ItemLevelTags.TAG);
        } catch (Throwable t) {
            SafeLog.fine("[usegate] the use-gate summary could not be read: " + t.getMessage());
        }
    }

    /** The audit over {@code gates}; either vocabulary check is skipped when null. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<UseGateAsset> gates, @Nullable GateKindRegistry kinds,
            @Nullable Predicate<String> knownFactors) {
        List<Finding> out = new ArrayList<>();
        for (UseGateAsset gate : gates) {
            if (gate == null || gate.getId() == null || !gate.isEnabled()) {
                continue;
            }
            String id = gate.getId();
            if (gate.on().isEmpty()) {
                out.add(Finding.warning(DOMAIN, NOTHING_GATED, "use gate '" + id + "' names no Held, Worn, Block "
                        + "or Mob pattern under On, so it applies to nothing"));
            }
            if (gate.liveRequires() == null) {
                out.add(Finding.warning(DOMAIN, REFUSES_NOBODY, "use gate '" + id + "' asks for nothing beyond "
                        + "whether it is there, so it refuses nobody; give it a Requires condition"));
            }
            String feature = gate.feature();
            if (feature != null && !feature.isBlank()) {
                if (gate.features().isEmpty()) {
                    out.add(Finding.warning(DOMAIN, BAD_FEATURE, "use gate '" + id + "' writes Feature '"
                            + feature.trim() + "', which is not <namespace>:<Feature_Id>, so it sits behind nothing"));
                } else {
                    checkSwitch(gate.features().get(0), "use gate '" + id + "'", out);
                }
            }
            out.addAll(GateValidator.validate(gate.requires(), DOMAIN, id, "use gate", kinds, knownFactors, null));
        }
        return out;
    }

    /** The audit over the tagged items, {@code usableTrack} saying which tracks this server runs. */
    @Nonnull
    public static List<Finding> auditTags(@Nonnull Collection<ItemLevelTags.Tagged> tagged,
            @Nonnull Predicate<String> usableTrack) {
        List<Finding> out = new ArrayList<>();
        Map<String, Integer> unknownTracks = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ItemLevelTags.Tagged item : tagged) {
            if (item == null) {
                continue;
            }
            for (String bad : item.malformed()) {
                boolean badSwitch = bad.startsWith(ItemLevelTags.FEATURE_TAG + " ");
                String effect = !badSwitch ? "so it asks nothing"
                        : item.switchUnreadable() ? "and the item names no switch that reads, so its "
                                + ItemLevelTags.TAG + " asks nothing until one does"
                        : "so the item sits behind its other switches only";
                out.add(Finding.warning(DOMAIN, BAD_LEVEL_TAG, "item '" + item.itemId() + "' tags " + bad
                        + ", which cannot be read (" + ItemLevelTags.TAG + " takes <Track>:<Level>, a whole level "
                        + "from 1; " + ItemLevelTags.FEATURE_TAG + " takes <namespace>:<Feature_Id>), " + effect));
            }
            for (FeatureLift.Lifted lifted : item.switches()) {
                checkSwitch(lifted, "item '" + item.itemId() + "'", out);
            }
            for (ItemLevelTags.Ask ask : item.asks()) {
                if (!usableTrack.test(ask.track())) {
                    unknownTracks.merge(ask.track(), 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> track : unknownTracks.entrySet()) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_LEVEL_TRACK, track.getValue() + " " + ItemLevelTags.TAG
                    + " value(s) name the track '" + track.getKey() + "', which this server does not run, so they "
                    + "ask nothing until it does"));
        }
        return out;
    }

    /** A switch no installed mod declares reads off, so what sits behind it is never there. */
    private static void checkSwitch(@Nonnull FeatureLift.Lifted lifted, @Nonnull String owner,
            @Nonnull List<Finding> out) {
        String factorId = lifted.factorId();
        String namespace = factorId.substring(0, factorId.length() - FeatureFlags.FACTOR_SUFFIX.length());
        if (!FeatureFlags.isKnown(namespace, lifted.param())) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_FEATURE, owner + " sits behind the switch '"
                    + namespace.toLowerCase(Locale.ROOT) + ":" + lifted.param() + "', which no installed mod declares, "
                    + "so it is never there"));
        }
    }
}
