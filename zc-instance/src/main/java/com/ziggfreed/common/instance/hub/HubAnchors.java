package com.ziggfreed.common.instance.hub;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * The hub's named spots as an NPC placement reads them: its {@code Custom} anchor
 * {@code {"Provider": "ziggfreedcommon:hub", "Params": {"District": "<id>", "Anchor": "<name>"}}} stands the character
 * on that spot of that district, on whichever lot the district stands in the copy ({@link HubPlan#spots}), so a
 * character follows its district when an owner moves it. A district standing on several lots (a placeholder) gives one
 * spot per lot, each its own placement instance. Outside a hub copy it gives none.
 *
 * <p>For a spot no district in force stands in the copy (its district switched off, gated out or left off the map),
 * the placement stays absent, unless its {@code Params} also write {@code "OrArrival": true}: then the character waits
 * by the arrival instead ({@link HubPlan#byArrival}), so a character players need to leave the hub (the porter) is
 * always there. The hub audit names a spot that resolves nowhere without that fallback, and a typo either way.
 *
 * <p>The library registers the provider with the placement engine at setup (the wiring root, which sees both modules).
 */
public final class HubAnchors {

    /** The provider id a placement's {@code Custom} anchor names. */
    public static final String PROVIDER = "ziggfreedcommon:hub";

    /** Who registers the provider. */
    public static final String OWNER = "ziggfreedcommon";

    /** The param naming the district. */
    public static final String DISTRICT = "District";

    /** The param naming the spot. */
    public static final String ANCHOR = "Anchor";

    /** The param that, {@code true}, stands the character by the arrival when its own spot stands nowhere. */
    public static final String OR_ARRIVAL = "OrArrival";

    private HubAnchors() {
    }

    /**
     * The spots a placement's {@code Params} name in {@code world}: the district's spot on each lot it stands on, else
     * the spot by the arrival when the params ask for it ({@link #OR_ARRIVAL}); empty outside a hub copy.
     */
    @Nonnull
    public static List<HubPlan.Spot> resolve(@Nullable World world, @Nonnull Map<String, String> params) {
        ZigHubWorldGenProvider hub = ZigHubWorldGenProvider.of(world);
        if (hub == null) {
            return List.of();
        }
        List<HubPlan.Spot> spots = hub.plan().spots(param(params, DISTRICT), param(params, ANCHOR));
        return spots.isEmpty() && orArrival(params) ? List.of(HubPlan.byArrival()) : spots;
    }

    /** Do {@code params} ask for the spot by the arrival when their own stands nowhere? */
    public static boolean orArrival(@Nonnull Map<String, String> params) {
        return "true".equalsIgnoreCase(param(params, OR_ARRIVAL));
    }

    /** A spot's placement instance id, stable for its lot and name: {@code <lot>/<name>}, lower-cased. */
    @Nonnull
    public static String instanceId(@Nonnull HubPlan.Spot spot) {
        return spot.lotId().toLowerCase(Locale.ROOT) + "/" + spot.name().toLowerCase(Locale.ROOT);
    }

    /** The param named {@code name}, its key read without regard to case, trimmed; null when unwritten. */
    @Nullable
    public static String param(@Nonnull Map<String, String> params, @Nonnull String name) {
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(name)) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
