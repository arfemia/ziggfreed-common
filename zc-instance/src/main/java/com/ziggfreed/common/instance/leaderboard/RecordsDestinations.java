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
 * Records: the library's tab and destination for the records a server keeps, today the encounter board's
 * boss fights. The name is generic on purpose, so a later board can join it. {@code "Open": "Records"}
 * opens the first fight on record (by its binding row's id) on the library's records page, on the rail with
 * this tab selected; the tab shows only while the board holds a fight with rows. Opened on the player's own
 * ref.
 */
public final class RecordsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Records";
    public static final Records RECORDS = new Records();

    /** The tab's picture: a pile of skulls, for the boss fights it records. */
    static final String ICON = "Deco_Bone_Skulls";

    private RecordsDestinations() {
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
                viewer -> shows(EncounterLeaderboardListener.board(), EncounterBindingConfig.getInstance().all().values()));
    }

    /** Shown while the board exists and a fight on it has rows. */
    public static boolean shows(@Nullable Leaderboard board, @Nonnull Collection<EncounterBindingAsset> rows) {
        return board != null && !EncounterBoards.ranked(board, rows).isEmpty();
    }

    private static boolean open(@Nonnull Records destination, @Nonnull DestinationContext ctx) {
        Leaderboard board = EncounterLeaderboardListener.board();
        PlayerRef viewer = ctx.playerRef();
        if (board == null || viewer == null) {
            return false;
        }
        List<EncounterBindingAsset> ranked = EncounterBoards.ranked(board,
                EncounterBindingConfig.getInstance().all().values());
        LeaderboardPageDeps deps = ranked.isEmpty() ? null : EncounterBoards.deps(board, ranked.get(0));
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
