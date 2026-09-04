package com.ziggfreed.common.encounter.run;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Writes to the engine's own member roster, the one it stamps from the script's Player sensor and
 * applies every per-member effect (the bar, the music) through.
 *
 * <p>A seeded member is re-stamped every tick, because the engine decays each member's time-to-live
 * every tick and reverts whoever lapses; a one-shot stamp at spawn would give a party the bar for
 * half a second. An ejected member is simply dropped from the live map, which is the single-member
 * removal the engine's own tick reads on its next pass.
 */
public final class EncounterMembership {

    /** How long a seeded stamp lasts before the next tick renews it. */
    public static final float SEED_TTL_SECONDS = 2.0F;

    private EncounterMembership() {
    }

    /** Stamp every online member of {@code run.seedMembers()} standing in this store as a member. */
    public static void stampSeeded(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> encounterRef,
            @Nonnull ZigEncounterRun run) {
        if (run.seedMembers().isEmpty() || !encounterRef.isValid()) {
            return;
        }
        EncounterMembers members = accessor.getComponent(encounterRef, EncounterMembers.getComponentType());
        if (members == null) {
            return;
        }
        for (UUID playerId : run.seedMembers()) {
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
        boolean wasMember = members.getMemberTtl().containsKey(playerRef);
        members.getMemberTtl().remove(playerRef);
        return wasMember;
    }
}
