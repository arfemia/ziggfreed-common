package com.ziggfreed.common.pvp;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A player died and the PvP ledger credited other players for it ({@link PvpLedger}): who landed the killing blow,
 * everyone who shared the kill as an assist, and the bounty the death ended, if one stood and someone ended it.
 * Synchronous {@code IEvent<Void>} on the shared engine bus, fired on the victim's world thread from inside the
 * engine's death system, only when somebody listens and only for a death that credits someone; a death the rules
 * credit to nobody (an idle victim, a party member's kill, the same player again too soon) fires none. zc-objectives
 * turns it into the three PvP moments ({@code PLAYER_KILL}, {@code PLAYER_ASSIST}, {@code PLAYER_BOUNTY}) for track
 * sources, quests, achievements and statistics; a listener doing more than reading hops off the death system first.
 *
 * @param store           the world's entity store the death happened in
 * @param victim          the player who died (still there, dead, until they come back)
 * @param victimId        their id
 * @param victimMaxHealth their maximum health at the death, what a kill is worth
 * @param credits         every credit, the killing blow first when it credits someone
 * @param bounty          the bounty this death ended, or null
 */
public record ZigPlayerKilledEvent(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victim,
                                   @Nonnull UUID victimId, double victimMaxHealth, @Nonnull List<Credit> credits,
                                   @Nullable Bounty bounty) implements IEvent<Void> {

    public ZigPlayerKilledEvent {
        credits = List.copyOf(credits);
    }

    /**
     * One player's credit: the player (their live ref in the same world), whether they landed the killing blow, their
     * share of the damage dealt to the victim in the assist window (0 to 1; an aura's owner holds the aura's share),
     * and what the repeat guard counted it for (above 0 to 1).
     */
    public record Credit(@Nonnull Ref<EntityStore> player, @Nonnull UUID playerId, boolean killingBlow, double share,
                         double weight) {
    }

    /**
     * The bounty a death ended and who ended it: the hunter (the credited killer), the streak it stood on, and what
     * ending it is worth, in health points (the rules' share of the victim's health per kill, raised by the hunter's
     * {@code PvpBountyBonus}).
     */
    public record Bounty(@Nonnull Ref<EntityStore> hunter, @Nonnull UUID hunterId, int streak, double worth) {
    }
}
