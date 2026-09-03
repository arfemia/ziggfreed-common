package com.ziggfreed.common.encounter.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.encounter.run.EncounterRuns;

/**
 * How an admin names a live encounter: by a prefix of its run id, or by its script id when exactly
 * one live encounter runs that script. Resolved off the live table, which needs no world thread.
 */
public final class EncounterRefs {

    private EncounterRefs() {
    }

    /**
     * The one live encounter {@code token} names, having told the sender when it names none or
     * several; null in either of those cases.
     */
    @Nullable
    public static EncounterRuns.Live resolve(@Nonnull CommandContext ctx, @Nullable String token) {
        if (token == null || token.isBlank()) {
            EncounterAdminMessages.refused(ctx, "ref.needed");
            return null;
        }
        String wanted = token.trim().toLowerCase(Locale.ROOT);
        List<EncounterRuns.Live> hits = new ArrayList<>();
        for (EncounterRuns.Live live : EncounterRuns.allLive()) {
            if (live.run().runId().toString().startsWith(wanted)
                    || live.encounterId().toLowerCase(Locale.ROOT).equals(wanted)) {
                hits.add(live);
            }
        }
        if (hits.isEmpty()) {
            EncounterAdminMessages.refused(ctx, "ref.unknown", token);
            return null;
        }
        if (hits.size() > 1) {
            EncounterAdminMessages.refused(ctx, "ref.ambiguous", token);
            return null;
        }
        return hits.get(0);
    }

    /** The world a live run is in, or null having told the sender it is gone. */
    @Nullable
    public static World worldOf(@Nonnull CommandContext ctx, @Nonnull EncounterRuns.Live live) {
        World world = Universe.get().getWorld(live.worldUuid());
        if (world == null) {
            EncounterAdminMessages.refused(ctx, "ref.gone");
        }
        return world;
    }
}
