package com.ziggfreed.common.almanac.page;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.view.AlmanacLines;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link AlmanacPage}, for the command, the destination and a consumer's own menu, the
 * answer a menu tile asks first (is there an Almanac to offer at all), and the line it may show while a
 * season runs. World thread.
 */
public final class AlmanacPages {

    private AlmanacPages() {
    }

    /** True while the Almanac is switched on and at least one season is listed. */
    public static boolean available() {
        return available(OccurrenceAlmanacCalendar.INSTANCE);
    }

    static boolean available(@Nonnull AlmanacCalendar calendar) {
        return AlmanacSwitch.isOn() && !AlmanacView.seasons(AlmanacEntryConfig.getInstance().all(), calendar).isEmpty();
    }

    /**
     * The one rule a menu tab or a hub tile asks: the Almanac is on and lists a season, the owner shows
     * the tab, and, when it is shown only while a season runs, one is running. A failing read hides it.
     */
    public static boolean menuTabVisible() {
        try {
            return menuTabVisible(OccurrenceAlmanacCalendar.INSTANCE);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean menuTabVisible(@Nonnull AlmanacCalendar calendar) {
        boolean listed = available(calendar);
        boolean live = listed && AlmanacView.anySeasonLive(AlmanacEntryConfig.getInstance().all(), calendar);
        return AlmanacMenuTab.visible(listed, AlmanacMenuTab.knobs(), live);
    }

    /**
     * The line a consumer's tile shows while a season runs, "Hallow's Eve is on now": it names every season on
     * now, in list order (one, two, or the first and how many more). Null while none runs, while the Almanac is
     * off, or when the read fails: the tile then keeps its own line.
     */
    @Nullable
    public static Message headline() {
        try {
            return headline(OccurrenceAlmanacCalendar.INSTANCE);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    static Message headline(@Nonnull AlmanacCalendar calendar) {
        if (!AlmanacSwitch.isOn()) {
            return null;
        }
        List<AlmanacView.Season> live = new ArrayList<>();
        for (AlmanacView.Season season : AlmanacView.seasons(AlmanacEntryConfig.getInstance().all(), calendar)) {
            if (season.live()) {
                live.add(season);
            }
        }
        return AlmanacLines.headline(live);
    }

    /**
     * Open the Almanac for {@code player}, on {@code eventId}'s season when it is listed (else the one
     * on now, else the first). Opened on the PLAYER's own ref, since the page reads their record. False
     * when it is switched off, when a handle is missing, or when the page manager refused.
     */
    public static boolean open(@Nullable String eventId, @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref, @Nullable Player player) {
        if (!AlmanacSwitch.isOn() || store == null || ref == null || player == null) {
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef == null) {
            SafeLog.fine("[almanac] the Almanac was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new AlmanacPage(playerRef, eventId));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the Almanac failed to open", t);
            return false;
        }
    }
}
