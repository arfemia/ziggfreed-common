package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
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
 *
 * <p>{@code {"Type": "Records", "Section": "Dungeons", "Pick": "My_Crypt", "Tab": "All_Time"}} opens it on one tab
 * (a section's id), one thing picked from that tab's list (a dungeon's or a boss's id) and one period under it, each
 * matched without regard to case ({@link LeaderboardPage#openedAt}), as a dungeon's records wall does. A name the
 * board does not offer now (a section with no column yet, a thing nobody has recorded) is left as a fresh open leaves
 * it, so a wall raised before anyone has a run still opens Records, on its tab with nothing picked.
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
        ctx.player().getPageManager().openCustomPage(ctx.playerReference(), ctx.store(),
                LeaderboardPage.openedAt(viewer, deps, destination.getSection(), destination.getTab(),
                        destination.getPick()));
        return true;
    }

    /** The records page, optionally on one tab, period and picked thing. */
    public static final class Records extends Destination {

        @Nullable protected String section;
        @Nullable protected String pick;
        @Nullable protected String tab;

        public static final BuilderCodec<Records> CODEC = BuilderCodec.builder(Records.class, Records::new)
                .append(new KeyedCodec<>("Section", Codec.STRING, false),
                        (d, v) -> d.section = v, d -> d.section)
                .documentation("The tab Records opens on, by its id: a stat section's file name under "
                        + "Server/ZiggfreedCommon/StatSections/ (Dungeons, Boss_Fights), any case. Unauthored, or a "
                        + "tab that shows nothing yet, opens on the first tab.").add()
                .append(new KeyedCodec<>("Pick", Codec.STRING, false),
                        (d, v) -> d.pick = v, d -> d.pick)
                .documentation("The one thing picked from that tab's list, by its id: a dungeon's file name on the "
                        + "Dungeons tab, a boss's binding row on the Bosses tab, any case. Unauthored, or a thing "
                        + "nobody has a record of yet, shows the whole tab.").add()
                .append(new KeyedCodec<>("Tab", Codec.STRING, false),
                        (d, v) -> d.tab = v, d -> d.tab)
                .documentation("The period Records opens on, by its id: a period's file name under "
                        + "Server/ZiggfreedCommon/StatPeriods/ (All_Time, Weekly), any case. Unauthored, or a period "
                        + "the tab does not show, opens on the first period.").add()
                .build();

        public Records() {
        }

        /** {@code Section}, trimmed, or null when none is written. */
        @Nullable
        public String getSection() {
            return blankToNull(section);
        }

        /** {@code Pick}, trimmed, or null when none is written. */
        @Nullable
        public String getPick() {
            return blankToNull(pick);
        }

        /** {@code Tab}, trimmed, or null when none is written. */
        @Nullable
        public String getTab() {
            return blankToNull(tab);
        }

        @Nullable
        private static String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
