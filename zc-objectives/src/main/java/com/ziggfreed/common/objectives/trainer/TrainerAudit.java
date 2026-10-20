package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The trainers' content audit (domain {@value #DOMAIN}), every finding a WARNING (an id may be a mod's this server
 * does not run): a trainer block naming a track or a category no loaded file defines ({@link #UNKNOWN_TRAINER_TRACK},
 * {@link #UNKNOWN_TRAINER_CATEGORY}); one character standing at two trainer placements, so a conversation opened
 * without its placement opens the first of them ({@link #SHARED_TRAINER_CHARACTER}); and a pick whose {@code Where}
 * names a placement that is no trainer, so that trainer never teaches it ({@link #WHERE_NAMES_NO_TRAINER}). It reads
 * the loaded files, so it runs once per boot: at the first player ready ({@link #runLateAudit}) or in zc's boot audit
 * ({@link #claimLateFindings}), never both.
 */
public final class TrainerAudit {

    public static final String DOMAIN = "trainer";
    public static final String LOG_LABEL = "[trainer] audit";

    public static final String UNKNOWN_TRAINER_TRACK = "UNKNOWN_TRAINER_TRACK";
    public static final String UNKNOWN_TRAINER_CATEGORY = "UNKNOWN_TRAINER_CATEGORY";
    public static final String SHARED_TRAINER_CHARACTER = "SHARED_TRAINER_CHARACTER";
    public static final String WHERE_NAMES_NO_TRAINER = "WHERE_NAMES_NO_TRAINER";

    private static final AtomicBoolean LATE_AUDIT_RAN = new AtomicBoolean();

    private TrainerAudit() {
    }

    /** Audit the loaded placements and picks. */
    @Nonnull
    public static List<Finding> audit() {
        NpcPlacementConfig placements = NpcPlacementConfig.getInstance();
        List<TrackPickAsset> picks = new ArrayList<>();
        for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
            picks.add(pick.asset());
        }
        Set<String> refused = placements.modGateRefusedFiles().keySet();
        return audit(placements.all().values(), id -> TrackConfig.getInstance().resolve(id) != null,
                id -> TrackCategoryConfig.getInstance().category(id) != null, Trainers::npcOf, picks,
                id -> refused.contains(id.toLowerCase(Locale.ROOT)));
    }

    /**
     * The pure core.
     *
     * @param npcOf   who stands at a placement (its id lower-cased), or null
     * @param refused whether a placement id names a file the mod gate kept out, which is never named
     */
    @Nonnull
    static List<Finding> audit(@Nonnull Collection<NpcPlacementAsset> placements,
            @Nonnull Predicate<String> trackKnown, @Nonnull Predicate<String> categoryKnown,
            @Nonnull Function<String, String> npcOf, @Nonnull Collection<TrackPickAsset> picks,
            @Nonnull Predicate<String> refused) {
        List<Finding> out = new ArrayList<>();
        Set<String> trainers = new TreeSet<>();
        Map<String, List<String>> byCharacter = new LinkedHashMap<>();
        for (NpcPlacementAsset placement : placements) {
            NpcPlacementAsset.Trainer block = placement == null ? null : placement.getTrainer();
            if (block == null || placement.getId() == null) {
                continue;
            }
            String id = placement.getId();
            trainers.add(id.toLowerCase(Locale.ROOT));
            for (String track : block.tracks()) {
                if (!trackKnown.test(track)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_TRAINER_TRACK, "the trainer block names the track '"
                            + track + "', which no loaded track file defines, so it offers no respec of it", id));
                }
            }
            for (String category : block.categories()) {
                if (!categoryKnown.test(category)) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_TRAINER_CATEGORY, "the trainer block names the category '"
                            + category + "', which no loaded category file defines, so it teaches none of its tracks",
                            id));
                }
            }
            String npc = placement.isEnabled() ? npcOf.apply(id.toLowerCase(Locale.ROOT)) : null;
            if (npc != null && !npc.isBlank()) {
                byCharacter.computeIfAbsent(npc.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
            }
        }
        byCharacter.forEach((npc, ids) -> {
            if (ids.size() > 1) {
                for (String id : ids) {
                    out.add(Finding.warning(DOMAIN, SHARED_TRAINER_CHARACTER, "the character '" + npc + "' stands at "
                            + ids.size() + " trainer placements (" + String.join(", ", ids) + "): a conversation "
                            + "opened at one of them opens that one, but one opened anywhere else opens the first. "
                            + "Name the trainer there with { \"Type\": \"Trainer\", \"Placement\": \"...\" }", id));
                }
            }
        });
        for (TrackPickAsset pick : picks) {
            if (pick == null) {
                continue;
            }
            for (String named : pick.placements()) {
                if (!trainers.contains(named) && !refused.test(named)) {
                    out.add(Finding.warning(DOMAIN, WHERE_NAMES_NO_TRAINER, "the pick's Where names '" + named
                            + "', which is no trainer placement (no file, or no Trainer block), so it is learned on "
                            + "its track's own page instead", pick.getId() == null ? "<unnamed>" : pick.getId()));
                }
            }
        }
        return out;
    }

    /** The once-per-boot pass at the first player ready, logged; nothing where the boot audit already took it. */
    public static void runLateAudit() {
        if (LATE_AUDIT_RAN.compareAndSet(false, true)) {
            log(audit());
        }
    }

    /** The once-per-boot pass's findings for zc's boot audit, which logs them; empty once the pass has run. */
    @Nonnull
    public static List<Finding> claimLateFindings() {
        return LATE_AUDIT_RAN.compareAndSet(false, true) ? audit() : List.of();
    }

    private static void log(@Nullable List<Finding> findings) {
        if (findings != null && !findings.isEmpty()) {
            ValidationReport.logAll(LOG_LABEL, findings, SafeLog::warn, SafeLog::info);
        }
    }
}
