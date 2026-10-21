package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementOverrides;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The trainers' content audit (domain {@value #DOMAIN}), every fault a WARNING (an id may be a mod's this server does
 * not run): a trainer block naming a track or a category no loaded file defines ({@link #UNKNOWN_TRAINER_TRACK},
 * {@link #UNKNOWN_TRAINER_CATEGORY}); one character standing at two trainer placements, so a conversation opened
 * without its placement opens the first of them ({@link #SHARED_TRAINER_CHARACTER}); and a pick whose {@code Where}
 * names a placement that stands but is no trainer (no {@code Trainer} block), or an id no placement could carry, so
 * that trainer never teaches it ({@link #WHERE_NAMES_NO_TRAINER}). A {@code Where} naming a trainer that does not
 * teach here is no fault (R608: its picks are learned on their tracks' own pages until it does), so it is one INFO
 * line a trainer id with how many picks name it: a trainer placement switched off (its own {@code Enabled} or the
 * owner's switch, {@link #TRAINER_SWITCHED_OFF}), or a trainer no placement file defines as yet
 * ({@link #TRAINER_NOT_PLACED}). It reads the loaded files, so it
 * runs once per boot: at the first player ready ({@link #runLateAudit}) or in zc's boot audit
 * ({@link #claimLateFindings}), never both.
 */
public final class TrainerAudit {

    public static final String DOMAIN = "trainer";
    public static final String LOG_LABEL = "[trainer] audit";

    public static final String UNKNOWN_TRAINER_TRACK = "UNKNOWN_TRAINER_TRACK";
    public static final String UNKNOWN_TRAINER_CATEGORY = "UNKNOWN_TRAINER_CATEGORY";
    public static final String SHARED_TRAINER_CHARACTER = "SHARED_TRAINER_CHARACTER";
    public static final String WHERE_NAMES_NO_TRAINER = "WHERE_NAMES_NO_TRAINER";
    public static final String TRAINER_NOT_PLACED = "TRAINER_NOT_PLACED";
    public static final String TRAINER_SWITCHED_OFF = "TRAINER_SWITCHED_OFF";

    /** What a placement id may be spelt with: an asset's file name (letters, digits, {@code _} and {@code -}). */
    private static final Pattern PLACEMENT_ID = Pattern.compile("[A-Za-z0-9_-]+");

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
                id -> refused.contains(id.toLowerCase(Locale.ROOT)), NpcPlacementOverrides.getInstance()::isEnabled);
    }

    /**
     * The pure core.
     *
     * @param npcOf      who stands at a placement (its id lower-cased), or null
     * @param refused    whether a placement id names a file the mod gate kept out, which is never named
     * @param switchedOn whether the owner's switch leaves a placement (its id lower-cased) on
     */
    @Nonnull
    static List<Finding> audit(@Nonnull Collection<NpcPlacementAsset> placements,
            @Nonnull Predicate<String> trackKnown, @Nonnull Predicate<String> categoryKnown,
            @Nonnull Function<String, String> npcOf, @Nonnull Collection<TrackPickAsset> picks,
            @Nonnull Predicate<String> refused, @Nonnull Predicate<String> switchedOn) {
        List<Finding> out = new ArrayList<>();
        // Every placement file loaded, switched on or off; those with a Trainer block; those of them switched off.
        Set<String> loaded = new TreeSet<>();
        Set<String> trainers = new TreeSet<>();
        Set<String> trainersOff = new TreeSet<>();
        Map<String, List<String>> byCharacter = new LinkedHashMap<>();
        for (NpcPlacementAsset placement : placements) {
            if (placement != null && placement.getId() != null) {
                loaded.add(placement.getId().toLowerCase(Locale.ROOT));
            }
            NpcPlacementAsset.Trainer block = placement == null ? null : placement.getTrainer();
            if (block == null || placement.getId() == null) {
                continue;
            }
            String id = placement.getId();
            String key = id.toLowerCase(Locale.ROOT);
            trainers.add(key);
            if (!placement.isEnabled() || !switchedOn.test(key)) {
                trainersOff.add(key);
            }
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
        Map<String, Integer> notPlaced = new TreeMap<>();
        Map<String, Integer> switchedOff = new TreeMap<>();
        for (TrackPickAsset pick : picks) {
            if (pick == null) {
                continue;
            }
            String pickId = pick.getId() == null ? "<unnamed>" : pick.getId();
            for (String named : pick.placements()) {
                if (refused.test(named)) {
                    continue;
                }
                if (trainers.contains(named)) {
                    if (trainersOff.contains(named)) {
                        switchedOff.merge(named, 1, Integer::sum);
                    }
                } else if (!PLACEMENT_ID.matcher(named).matches()) {
                    out.add(Finding.warning(DOMAIN, WHERE_NAMES_NO_TRAINER, "the pick's Where names '" + named
                            + "', which no placement can be called (an id is letters, digits, _ and -), so no "
                            + "trainer ever teaches it and it is learned on its track's own page", pickId));
                } else if (loaded.contains(named)) {
                    out.add(Finding.warning(DOMAIN, WHERE_NAMES_NO_TRAINER, "the pick's Where names the placement '"
                            + named + "', which carries no Trainer block, so it is no trainer and never teaches the "
                            + "pick, which is learned on its track's own page", pickId));
                } else {
                    notPlaced.merge(named, 1, Integer::sum);
                }
            }
        }
        switchedOff.forEach((trainer, count) -> out.add(Finding.info(DOMAIN, TRAINER_SWITCHED_OFF, count
                + " pick(s) name the trainer '" + trainer + "', whose placement is switched off, so they are learned "
                + "on their tracks' own pages until it is switched on", trainer)));
        notPlaced.forEach((trainer, count) -> out.add(Finding.info(DOMAIN, TRAINER_NOT_PLACED, count
                + " pick(s) name the trainer '" + trainer + "', which no placement file defines as yet, so they are "
                + "learned on their tracks' own pages until one does", trainer)));
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
