package com.ziggfreed.common.encounter.command;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRest;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterRuntime;
import com.ziggfreed.common.encounter.run.ZigEncounterRest;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.asset.ParticipationSpec;

/**
 * One run in detail: where it is, what it is fighting, who is in it, what they have earned, and the
 * rest the site owes between fights.
 */
final class EncounterInspectCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> refArg;

    EncounterInspectCommand() {
        super(EncounterCommandLine.INSPECT, EncounterAdminMessages.desc(EncounterCommandLine.INSPECT));
        this.refArg = withRequiredArg(EncounterCommandLine.ARG_REF, EncounterAdminMessages.desc("arg.ref"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        EncounterRuns.Live live = EncounterRefs.resolve(ctx, refArg.get(ctx));
        if (live == null) {
            return CompletableFuture.completedFuture(null);
        }
        World world = EncounterRefs.worldOf(ctx, live);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        return runAsync(ctx, () -> inspect(ctx, world, live), world);
    }

    private static void inspect(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull EncounterRuns.Live live) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        EncounterRun run = EncounterRun.of(live.run(), live.encounterId());
        long now = System.currentTimeMillis();
        EncounterAdminMessages.heading(ctx, "inspect.header", live.encounterId(), run.shortId());
        EncounterAdminMessages.detail(ctx, "inspect.world", world.getName());
        String state = EncounterRuntime.state(store, live.encounterRef());
        EncounterAdminMessages.detail(ctx, "inspect.state", state == null ? "?" : state);
        if (run.isEngaged()) {
            EncounterAdminMessages.detail(ctx, "inspect.engaged", (now - run.engagedAtMs()) / 1000L,
                    run.phase() == null ? "-" : run.phase(), run.waves());
        } else {
            EncounterAdminMessages.detail(ctx, "inspect.idle");
        }
        if (run.subjectMobId() != null) {
            EncounterAdminMessages.detail(ctx, "inspect.subject", run.subjectMobId());
        } else {
            EncounterAdminMessages.detail(ctx, "inspect.subject.none");
        }
        int members = EncounterRuns.memberIds(store, live.encounterRef()).size();
        EncounterAdminMessages.detail(ctx, "inspect.members", members, run.memberDeaths());
        if (run.concluded()) {
            EncounterAdminMessages.detail(ctx, "inspect.concluded", run.defeated() ? "defeated" : "wiped");
        }
        EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(live.encounterId());
        rest(ctx, store, live, row);
        ParticipationSpec spec = EncounterLifecycle.specFor(store, live.run(), row);
        ParticipationShares shares = EncounterLifecycle.settle(store, live.run(), spec);
        if (shares.isEmpty()) {
            EncounterAdminMessages.detail(ctx, "inspect.participants.none");
            return;
        }
        for (ParticipantShare share : shares.participants()) {
            EncounterAdminMessages.detail(ctx, "inspect.participant", share.playerName(),
                    Math.round(share.damageDealt()), Math.round(share.damageTaken()),
                    Math.round(share.presenceSeconds()), Math.round(share.share() * 100.0));
        }
    }

    /** The rest the row owes, in world-clock seconds, and where the site stands in it when one is stamped. */
    private static void rest(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull EncounterRuns.Live live, @Nullable EncounterBindingAsset row) {
        Duration owed = row == null || row.getTiming() == null ? null : row.getTiming().rest();
        if (owed != null) {
            EncounterAdminMessages.detail(ctx, "inspect.rest", owed.toSeconds());
        }
        ZigEncounterRest stamped = EncounterRest.restOn(store, live.encounterRef());
        if (stamped == null || stamped.restUntil() == null) {
            return;
        }
        Instant now = EncounterRest.gameTime(store);
        if (now != null && !stamped.isRested(now)) {
            EncounterAdminMessages.detail(ctx, "inspect.rest.resting", stamped.secondsLeft(now));
        } else {
            EncounterAdminMessages.detail(ctx, "inspect.rest.rested");
        }
    }
}
