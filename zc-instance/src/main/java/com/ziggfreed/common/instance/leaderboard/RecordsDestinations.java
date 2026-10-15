package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationKind;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * Records: the library's tab and destination for the records a server keeps, the board filled through
 * {@link #setBoard} (the stat board, whose sections the boss fights and the dungeons are among, each split into its
 * own). {@code "Open": "Records"} opens that board on the library's records page, on the rail with this tab
 * selected. The tab shows while the filled board does. Opened on the player's own ref, after the filled board has
 * brought the opener's own numbers up to date ({@link RecordsBoard#opening}).
 */
public final class RecordsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Records";
    public static final Records RECORDS = new Records();

    /** The tab's picture: a pile of skulls, for the boss fights it records. */
    static final String ICON = "Deco_Bone_Skulls";

    /** The board Records opens; null for none, which shows no tab. */
    @Nullable private static volatile RecordsBoard board;

    private RecordsDestinations() {
    }

    /** Fill the seam: Records opens {@code first} while it shows. Null empties it. */
    public static void setBoard(@Nullable RecordsBoard first) {
        board = first;
    }

    /** Seed the type, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Records.class, Records.CODEC, RecordsDestinations::open)
                .withKind(DestinationKind.TROPHY));
    }

    /** The Records tab in the shared menu. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.RECORDS.entry(EncounterLeaderboardMessages.line("menu.records"), IconSpec.ofItem(ICON),
                RECORDS, viewer -> shows());
    }

    /** Whether Records has anything to open: the filled board shows. */
    private static boolean shows() {
        RecordsBoard first = board;
        return first != null && first.shows();
    }

    private static boolean open(@Nonnull Records destination, @Nonnull DestinationContext ctx) {
        PlayerRef viewer = ctx.playerRef();
        RecordsBoard first = board;
        if (viewer == null || first == null) {
            return false;
        }
        // The opener's own numbers first (the stat board's native readings), so the page shows them as they stand;
        // a board that cannot costs only that, never the page.
        try {
            first.opening(ctx.store(), ctx.playerReference());
        } catch (RuntimeException e) {
            SafeLog.warn("[records] the board could not bring a player's numbers up to date before it opened", e);
        }
        LeaderboardPageDeps deps = first.deps();
        if (deps == null) {
            return false;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerReference(), ctx.store(), new LeaderboardPage(viewer, deps));
        return true;
    }

    /** The records page. */
    public static final class Records extends Destination {

        public static final BuilderCodec<Records> CODEC = BuilderCodec.builder(Records.class, Records::new).build();
    }
}
