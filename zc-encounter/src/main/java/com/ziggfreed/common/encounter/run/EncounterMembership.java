package com.ziggfreed.common.encounter.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.ledger.ParticipationShares;

import it.unimi.dsi.fastutil.objects.Reference2FloatMap;

/**
 * Writes to the engine's own member roster, the one it stamps from the script's Player sensor and
 * applies every per-member effect (the bar, the music) through, and the one its {@code GrantReward}
 * pays.
 *
 * <p>A seeded member is re-stamped every tick, because the engine decays each member's time-to-live
 * every tick and reverts whoever lapses; a one-shot stamp at spawn would give a party the bar for
 * half a second. A won run's credited players are re-stamped the same way until it resets, so a
 * reward the script grants after the defeat reaches a player the Player sensor no longer sees. An
 * ejected member is simply dropped from the live map, which is the single-member removal the
 * engine's own tick reads on its next pass.
 */
public final class EncounterMembership {

    /** How long a seeded or credited stamp lasts before the next tick renews it. */
    public static final float SEED_TTL_SECONDS = 2.0F;

    private EncounterMembership() {
    }

    /** Stamp every online member of {@code run.seedMembers()} standing in this store as a member. */
    public static void stampSeeded(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run) {
        stampOnline(accessor, encounterRef, run.seedMembers());
    }

    /**
     * On a won run, stamp every player its defeat credited who stands in this store, wherever in it they
     * stand. The engine's {@code GrantReward} pays only the live roster, and the script's Player sensor keeps
     * only the players in its range, so without this a player credited for the kill who stood past that range
     * when the boss fell (the far end of a long arena, an archer) is paid nothing. Renewed every tick from the
     * defeat until the reset rolls the run over, which clears its shares; a lost run stamps nobody.
     */
    public static void stampCredited(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> encounterRef, @Nonnull ZigEncounterRun run) {
        if (!run.isDefeated()) {
            return;
        }
        stampOnline(accessor, encounterRef, creditedIds(run.settledShares()));
    }

    /** The players {@code shares} credits, share descending; attempt-only participants are left out. */
    @Nonnull
    private static List<UUID> creditedIds(@Nonnull ParticipationShares shares) {
        List<ParticipantShare> credited = shares.credited();
        List<UUID> out = new ArrayList<>(credited.size());
        for (ParticipantShare share : credited) {
            out.add(share.playerId());
        }
        return out;
    }

    /**
     * Stamp each of {@code players} who is online and standing in the encounter's own store as a member, at
     * {@link #SEED_TTL_SECONDS}; anyone offline, or in another world, is skipped.
     */
    private static void stampOnline(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> encounterRef, @Nonnull Collection<UUID> players) {
        if (players.isEmpty() || !encounterRef.isValid()) {
            return;
        }
        EncounterMembers members = accessor.getComponent(encounterRef, EncounterMembers.getComponentType());
        if (members == null) {
            return;
        }
        for (UUID playerId : players) {
            PlayerRef player = Universe.get().getPlayer(playerId);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != encounterRef.getStore()) {
                continue;
            }
            members.stampMember(ref, SEED_TTL_SECONDS);
        }
    }

    /** Drop {@code playerRef} from the roster now; answers whether they were on it. */
    public static boolean eject(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull Ref<EntityStore> playerRef) {
        if (!encounterRef.isValid()) {
            return false;
        }
        EncounterMembers members = accessor.getComponent(encounterRef, EncounterMembers.getComponentType());
        if (members == null) {
            return false;
        }
        return dropMember(members.getMemberTtl(), playerRef);
    }

    /**
     * Drop one key from a member roster; answers whether it was there. Goes through the map's
     * primitive {@code removeFloat}, never the boxed {@code remove(Object)} fastutil deprecates: the
     * boxed form only wraps this same call in a {@code containsKey} test, so the roster ends up the
     * same either way.
     */
    static <K> boolean dropMember(@Nonnull Reference2FloatMap<K> memberTtl, @Nonnull K member) {
        if (!memberTtl.containsKey(member)) {
            return false;
        }
        memberTtl.removeFloat(member);
        return true;
    }
}
