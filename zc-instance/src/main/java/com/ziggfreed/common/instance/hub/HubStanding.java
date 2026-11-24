package com.ziggfreed.common.instance.hub;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.util.SafeLog;

/**
 * Whether a hub district stands, as the factor {@value #FACTOR}: with a district's id as its {@code Param}, 1 when a
 * hub copy opening now would lay that district on a lot (it is switched on in its file and the owner's
 * {@code hub-districts.json}, its season runs, no missing mod kept its file out, and {@link HubLayout} gives it a lot),
 * 0 when it would not (switched off, out of season, left off the map, or no loaded file of that id); null for a blank
 * {@code Param} or while the district store holds nothing yet (very early boot), so a gate on it stays shut until the
 * answer is real.
 *
 * <p>It reads the district files, never a live copy: the hub is an instance that opens and closes with its visitors,
 * so the question is "is the district part of the hub on this server", answered the same from any world and with no
 * subject. A placement in the Forgotten Temple stands only while a district does not with {@code "Max": 0} (a
 * stand-in for characters who moved into it), and one in the hub only while it does with {@code "Min": 1}. Absent is a
 * definite 0, as for {@code hytale:mod_installed}: a null there would shut the {@code Max: 0} half on exactly the
 * server it exists for.
 *
 * <p>The answer is kept for a second, since a trainer page asks it for every lesson it lists; a change to the
 * district files or the owner's file reaches a standing world's placements at its next sweep.
 */
public final class HubStanding {

    /** Who the factor is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The reading: Param a district id, 1 while it stands in the hub. */
    public static final String FACTOR = "ziggfreedcommon:hub_district";

    /** How long one reading of the layout serves, in milliseconds. */
    static final long FRESH_MS = 1000L;

    private static final Double YES = 1.0;
    private static final Double NO = 0.0;

    /** One reading of which districts stand: the ids lower-cased, or null for "cannot tell yet". */
    private record Snapshot(long atMs, @Nullable Set<String> standing) {
    }

    private static volatile Snapshot last;

    private HubStanding() {
    }

    /** Claim {@value #FACTOR} process-wide. Once, at setup. */
    public static void contribute() {
        FactorContributions.register(FACTOR, OWNER, HubStanding::resolve);
    }

    /** {@value #FACTOR} for one context: its {@code Param} is the district. */
    @Nullable
    static Double resolve(@Nonnull FactorContext ctx) {
        return reading(ctx.param());
    }

    /**
     * The {@value #FACTOR} reading for one district id, with no factor context in hand: exactly the class javadoc's
     * truth table, for a caller asking the same question a gate would.
     */
    @Nullable
    public static Double reading(@Nullable String district) {
        if (district == null || district.isBlank()) {
            return null;
        }
        return reading(district, standingNow());
    }

    /** The pure core: 1 when {@code standing} holds {@code district} (any case), 0 when not, null for no answer. */
    @Nullable
    static Double reading(@Nonnull String district, @Nullable Set<String> standing) {
        if (standing == null) {
            return null;
        }
        return standing.contains(district.trim().toLowerCase(Locale.ROOT)) ? YES : NO;
    }

    /** The districts {@code layout} lays on a lot, a placeholder's included, each id lower-cased. */
    @Nonnull
    static Set<String> standingIn(@Nonnull HubLayout.Result layout) {
        Set<String> out = new HashSet<>();
        for (HubLayout.Placed placed : layout.placed()) {
            out.add(placed.districtId().trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    /** Which districts stand now, kept for {@link #FRESH_MS}; null while the store holds nothing. */
    @Nullable
    private static Set<String> standingNow() {
        long now = System.currentTimeMillis();
        Snapshot kept = last;
        if (kept != null && now >= kept.atMs() && now - kept.atMs() < FRESH_MS) {
            return kept.standing();
        }
        Set<String> standing = layOut();
        last = new Snapshot(now, standing);
        return standing;
    }

    /** The layout a copy opening now would make, as {@link HubPlan#build} lays it; null when it cannot be read. */
    @Nullable
    private static Set<String> layOut() {
        try {
            HubDistrictConfig config = HubDistrictConfig.getInstance();
            if (config.all().isEmpty()) {
                return null;
            }
            return standingIn(HubLayout.assign(HubLots.all(), config.claims(true)));
        } catch (Throwable t) {
            SafeLog.fine("[hub] could not read which districts stand: " + t.getMessage());
            return null;
        }
    }
}
