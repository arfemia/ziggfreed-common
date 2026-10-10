package com.ziggfreed.common.instance.leaderboard;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationKind;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * Records: the library's tab and destination for the records a server keeps: the board filled through
 * {@link #setBoard} (the stat board) while it shows, else the encounter board's boss fights.
 * {@code "Open": "Records"} opens that board on the library's records page, on the rail with this tab
 * selected; for the encounter board, the first fight on record (by its binding row's id). Over the filled
 * board, a Boss Fights tab ends its first row and opens that same fight page, so boss records are always
 * a click from Records. The tab shows while either has something to show. Opened on the player's own ref.
 */
public final class RecordsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Records";
    public static final Records RECORDS = new Records();

    /** The tab's picture: a pile of skulls, for the boss fights it records. */
    static final String ICON = "Deco_Bone_Skulls";

    /** The key of the filled board's tab that opens the boss fights. */
    static final String BOSS_FIGHTS = "boss_fights";

    /** The board Records opens before the encounter board; null for the encounter board alone. */
    @Nullable private static volatile RecordsBoard board;

    private RecordsDestinations() {
    }

    /** Fill the seam: Records opens {@code first} while it shows, else the encounter board. Null empties it. */
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
                RECORDS,
                viewer -> firstShows() || shows(EncounterLeaderboardListener.board(),
                        EncounterBindingConfig.getInstance().all().values()));
    }

    /** Shown while the board exists and a fight on it has rows. */
    public static boolean shows(@Nullable Leaderboard board, @Nonnull Collection<EncounterBindingAsset> rows) {
        return board != null && !EncounterBoards.ranked(board, rows).isEmpty();
    }

    /** Whether the filled board shows; false with none filled. */
    private static boolean firstShows() {
        RecordsBoard first = board;
        return first != null && first.shows();
    }

    private static boolean open(@Nonnull Records destination, @Nonnull DestinationContext ctx) {
        PlayerRef viewer = ctx.playerRef();
        if (viewer == null) {
            return false;
        }
        RecordsBoard first = board;
        LeaderboardPageDeps deps = first != null && first.shows() ? first.deps() : null;
        if (deps != null) {
            // The boss fights stay one tab away: the filled board's first row ends in a tab that opens the
            // encounter board's page while a fight on it has rows.
            deps = deps.withPrimaryLink(BOSS_FIGHTS, EncounterLeaderboardMessages.line("records.boss_fights"),
                    RecordsDestinations::encounterDeps);
        } else {
            deps = encounterDeps();
        }
        if (deps == null) {
            return false;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerReference(), ctx.store(), new LeaderboardPage(viewer, deps));
        return true;
    }

    /** The first ranked fight's page, or null when the encounter board has none. */
    @Nullable
    private static LeaderboardPageDeps encounterDeps() {
        Leaderboard encounters = EncounterLeaderboardListener.board();
        if (encounters == null) {
            return null;
        }
        List<EncounterBindingAsset> ranked = EncounterBoards.ranked(encounters,
                EncounterBindingConfig.getInstance().all().values());
        return ranked.isEmpty() ? null : EncounterBoards.deps(encounters, ranked.get(0));
    }

    /** The records page. */
    public static final class Records extends Destination {

        public static final BuilderCodec<Records> CODEC = BuilderCodec.builder(Records.class, Records::new).build();
    }
}
