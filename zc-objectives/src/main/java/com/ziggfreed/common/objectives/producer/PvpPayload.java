package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.source.ExactAmountPayload;
import com.ziggfreed.common.track.source.VictimPayload;

/**
 * What rides with a PvP moment ({@code PLAYER_KILL}, {@code PLAYER_ASSIST}, {@code PLAYER_BOUNTY}) beyond its Target:
 * the player who fell (so a track source's {@code Victim} diminishing key counts each fallen player apart, and a
 * {@code PerMaxHealth} row reads their health), what the moment is worth in health points ({@code exactAmount}: the
 * fallen player's health for a kill, their health times the share for an assist, the bounty's worth; each times what
 * the repeat guard counted it for), the share of the kill and the guard's weight. A moment of PvP's own stands
 * outside the {@code Gain.PlayerHits} rule ({@link #underPlayerHitRule}): only a fight between players makes it,
 * and only a source naming its kind pays it.
 *
 * @param victimRef   the player who fell
 * @param exactAmount what the moment is worth, in health points
 * @param share       the credited player's share of the kill (1 for a kill or a bounty)
 * @param weight      what the repeat guard counted it for (above 0 to 1)
 */
public record PvpPayload(@Nonnull Ref<EntityStore> victimRef, double exactAmount, double share, double weight)
        implements VictimPayload, ExactAmountPayload {

    @Override
    public boolean underPlayerHitRule() {
        return false;
    }
}
