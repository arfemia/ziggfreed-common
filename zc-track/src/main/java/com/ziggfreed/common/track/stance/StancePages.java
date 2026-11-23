package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.pick.PickTrainers;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way the stance page opens: the {@code Track_Stance} destination ({@link StanceDestinations}: a trainer's
 * conversation, a placement's press F, a link) and a consumer's own button both land here. World thread.
 */
public final class StancePages {

    private StancePages() {
    }

    /**
     * Open the stance page for {@code player}, on {@code anchor} (the character they stand at, else themselves): the
     * stance {@code stanceId} names, or with none every switched-on stance whose {@code Where} names
     * {@code placement} (every switched-on stance when that is none too). Holds and releases there are made at
     * {@code placement}. Nothing to show opens nothing and tells the player so.
     *
     * @return whether the screen was taken
     */
    public static boolean open(@Nullable String stanceId, @Nullable String placement,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> anchor, @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[stance] a stance page was asked for by an entity that is not a player");
            return false;
        }
        List<String> ids = chosen(stanceId, placement);
        if (ids.isEmpty()) {
            playerRef.sendMessage(StanceText.line("page.none"));
            return false;
        }
        try {
            player.getPageManager().openCustomPage(anchor, store, new StancePage(playerRef, ids, placement));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[stance] the stance page failed to open", t);
            return false;
        }
    }

    /** The stance ids a page asked for this way shows, in order: empty when there is none to show. */
    @Nonnull
    static List<String> chosen(@Nullable String stanceId, @Nullable String placement) {
        List<String> out = new ArrayList<>();
        if (stanceId != null && !stanceId.isBlank()) {
            TrackStanceAsset one = TrackStanceConfig.getInstance().live(stanceId);
            if (one != null) {
                out.add(TrackKey.normalize(one.getId()));
            }
            return out;
        }
        String at = TrackKey.normalize(placement);
        for (TrackStanceAsset stance : TrackStanceConfig.getInstance().liveStances()) {
            // The placements that stand now, as a hold reads them (Stances.here): none standing is anywhere.
            List<String> standing = PickTrainers.teachingAmong(stance.placements());
            if (at.isEmpty() || standing.isEmpty() || standing.contains(at)) {
                out.add(TrackKey.normalize(stance.getId()));
            }
        }
        return out;
    }

    /** The switched-on stances among {@code ids}, in their order (one switched off since is left out). */
    @Nonnull
    static List<TrackStanceAsset> shown(@Nonnull List<String> ids) {
        List<TrackStanceAsset> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            TrackStanceAsset stance = TrackStanceConfig.getInstance().live(id);
            if (stance != null) {
                out.add(stance);
            }
        }
        return out;
    }
}
