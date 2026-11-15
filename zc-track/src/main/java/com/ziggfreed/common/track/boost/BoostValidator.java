package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The content audit over boosts, filed under the {@value #DOMAIN} domain and run inside the track audit (so zc's
 * boot audit and the once-at-boot error log carry it). Split the way every validator in this family is:
 * {@link #audit()} is the engine walk (it never throws), and
 * {@link #audit(Collection, Predicate, Predicate, Predicate)} the pure core. A switched-off boost is skipped. Every
 * finding is a WARNING (an unknown id may belong to a pack this server does not run), but for a standing boost that
 * also writes a length, which is INFO.
 */
public final class BoostValidator {

    public static final String DOMAIN = "boost";

    /** It names no track, category or All, so it lifts nothing. */
    public static final String NO_TRACKS = "BOOST_NO_TRACKS";
    /** A Tracks.Ids entry is no track this server has switched on. */
    public static final String UNKNOWN_TRACK = "BOOST_UNKNOWN_TRACK";
    /** A Tracks.Categories entry is no category this server has. */
    public static final String UNKNOWN_CATEGORY = "BOOST_UNKNOWN_CATEGORY";
    /** Its Percent is missing or 0, so it lifts nothing. */
    public static final String NO_PERCENT = "BOOST_NO_PERCENT";
    /** Neither Standing nor a Seconds above 0, so nothing can run it (unless a start gives its own length). */
    public static final String NEVER_RUNS = "BOOST_NEVER_RUNS";
    /** A standing boost that writes a length: a standing boost keeps no timer, so the length is never read. */
    public static final String STANDING_WITH_SECONDS = "BOOST_STANDING_WITH_SECONDS";
    /** Its Effect is no EntityEffect a loaded pack ships, so it shows on the shared tile. */
    public static final String UNKNOWN_EFFECT = "BOOST_UNKNOWN_EFFECT";

    private BoostValidator() {
    }

    /** The engine walk over every folded boost. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            Collection<BoostAsset> boosts = BoostConfig.getInstance().all().values();
            List<Finding> out = new ArrayList<>(audit(boosts,
                    id -> TrackConfig.getInstance().live(id) != null,
                    id -> TrackCategoryConfig.getInstance().category(id) != null,
                    id -> EntityEffect.getAssetMap().getIndex(id) != Integer.MIN_VALUE));
            for (BoostAsset boost : boosts) {
                if (boost != null && boost.isEnabled()) {
                    out.addAll(GateValidator.validate(boost.requires(), DOMAIN, boost.getId(), "boost",
                            GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                    if (boost.reusableRequires() != null) {
                        out.addAll(GateValidator.validate(boost.reusableRequires(), DOMAIN, boost.getId(), "boost",
                                GateRuntime.kinds(), GateRuntime.vocabulary()::isRegistered, null));
                    }
                    SeasonGate.checkKnown(out, DOMAIN, boost.season(), boost.getId());
                }
            }
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[boost] the boost audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The pure core: each switched-on boost against the tracks, categories and effects the predicates know. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<BoostAsset> boosts, @Nonnull Predicate<String> trackKnown,
            @Nonnull Predicate<String> categoryKnown, @Nonnull Predicate<String> effectKnown) {
        List<Finding> out = new ArrayList<>();
        for (BoostAsset boost : boosts) {
            if (boost == null || !boost.isEnabled()) {
                continue;
            }
            String id = boost.getId() == null ? "" : boost.getId();
            if (!boost.namesTracks()) {
                out.add(Finding.warning(DOMAIN, NO_TRACKS, "boost '" + id + "' names no Tracks (Ids, Categories or "
                        + "All), so it lifts nothing", id));
            }
            if (!boost.liftsAll()) {
                for (String track : boost.trackIds()) {
                    if (!trackKnown.test(track)) {
                        out.add(Finding.warning(DOMAIN, UNKNOWN_TRACK, "boost '" + id + "' names the track '" + track
                                + "', which is not switched on here, so that entry lifts nothing", id));
                    }
                }
                for (String category : boost.categoryIds()) {
                    if (!categoryKnown.test(category)) {
                        out.add(Finding.warning(DOMAIN, UNKNOWN_CATEGORY, "boost '" + id + "' names the category '"
                                + category + "', which this server does not have, so that entry lifts nothing", id));
                    }
                }
            }
            if (boost.percent() == 0.0) {
                out.add(Finding.warning(DOMAIN, NO_PERCENT, "boost '" + id + "' has no Percent, so it lifts nothing "
                        + "unless a start asks for one; 50 is half as much again", id));
            }
            if (boost.isStanding() && boost.seconds() > 0L) {
                out.add(Finding.info(DOMAIN, STANDING_WITH_SECONDS, "boost '" + id + "' is Standing, which keeps no "
                        + "timer, so its Seconds is never read", id));
            } else if (!boost.isStanding() && boost.seconds() <= 0L) {
                out.add(Finding.warning(DOMAIN, NEVER_RUNS, "boost '" + id + "' is neither Standing nor timed (no "
                        + "Seconds), so it runs only where a start gives its own length", id));
            }
            String effect = boost.effect();
            if (effect != null && !effectKnown.test(effect)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_EFFECT, "boost '" + id + "' names the effect '" + effect
                        + "', which no loaded pack ships, so it shows on the shared boost tile", id));
            }
        }
        return out;
    }
}
