package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.util.NumberFormatter;

/**
 * The generic leaderboard page (the reusable lift of {@code KweebecLeaderboardPage}, the
 * paradigm the user asked to generalize). Tabbed buckets (consumer-supplied
 * {@link LeaderboardBucketTab}s), a column header, a scrollable ranked list of the
 * selected bucket sorted by best score (top three gold/silver/bronze, the viewer's row
 * highlighted), and a "your rank" footer. The {@code ResultsPage} CTA deep-links here to
 * the just-played bucket.
 *
 * <p>Consumer policy (the {@link Leaderboard}, tabs, chrome) is supplied through
 * {@link LeaderboardPageDeps}; the page is mod-agnostic. Names resolve live then the
 * persisted entry name then a short uuid. Every {@code handleDataEvent} exit path sends a
 * response.
 */
public class LeaderboardPage extends InteractiveCustomUIPage<LeaderboardEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigLeaderboardPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigLeaderboardRow.ui";
    private static final String TAB_TEMPLATE = "Pages/ZigLeaderboardTab.ui";
    private static final int MAX_ROWS = 100;
    private static final String TAB_ACTIVE = "#5a7ba8";

    private final LeaderboardPageDeps deps;
    private final String activeBucket;

    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps) {
        this(playerRef, deps, null);
    }

    public LeaderboardPage(@Nonnull PlayerRef playerRef, @Nonnull LeaderboardPageDeps deps,
                           @Nullable String bucket) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, LeaderboardEventData.CODEC);
        this.deps = deps;
        this.activeBucket = resolveBucket(deps, bucket);
    }

    @Nonnull
    private static String resolveBucket(@Nonnull LeaderboardPageDeps deps, @Nullable String bucket) {
        if (bucket != null) {
            for (LeaderboardBucketTab tab : deps.tabs()) {
                if (tab.bucketKey().equals(bucket)) {
                    return bucket;
                }
            }
        }
        return deps.tabs().isEmpty() ? "" : deps.tabs().get(0).bucketKey();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        LeaderboardScreenMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        cmd.set("#Title.Text", t.title());
        cmd.set("#HdrRank.Text", t.colRank());
        cmd.set("#HdrPlayer.Text", t.colPlayer());
        cmd.set("#HdrScore.Text", t.colScore());
        cmd.set("#HdrTime.Text", t.colTime());
        cmd.set("#HdrPlays.Text", t.colPlays());
        cmd.set("#EmptyState.Text", t.empty());

        List<LeaderboardBucketTab> tabs = deps.tabs();
        for (int i = 0; i < tabs.size(); i++) {
            LeaderboardBucketTab tab = tabs.get(i);
            cmd.append("#TabBar", TAB_TEMPLATE);
            String sel = "#TabBar[" + i + "]";
            cmd.set(sel + " #TabBtn.Text", tab.label());
            if (tab.bucketKey().equals(activeBucket)) {
                cmd.set(sel + " #TabBtn.Style.Default.Background.Color", TAB_ACTIVE);
                cmd.set(sel + " #TabBtn.Style.Hovered.Background.Color", TAB_ACTIVE);
                cmd.set(sel + " #TabBtn.Style.Pressed.Background.Color", TAB_ACTIVE);
                cmd.set(sel + " #TabBtn.Style.Default.LabelStyle.TextColor", "#ffffff");
            }
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #TabBtn",
                    EventData.of("Action", "tab").append("Bucket", tab.bucketKey()), false);
        }

        Map<UUID, LeaderboardEntry> bucket = deps.board().forBucket(activeBucket);
        List<Map.Entry<UUID, LeaderboardEntry>> sorted = new ArrayList<>(bucket.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<UUID, LeaderboardEntry> e) -> e.getValue().bestScore).reversed());

        UUID self = playerRef.getUuid();
        if (sorted.isEmpty()) {
            cmd.set("#EmptyState.Visible", true);
        } else {
            int max = Math.min(sorted.size(), MAX_ROWS);
            for (int i = 0; i < max; i++) {
                Map.Entry<UUID, LeaderboardEntry> en = sorted.get(i);
                appendRow(cmd, i, i + 1, en.getKey(), en.getValue(), en.getKey().equals(self));
            }
        }
        setYourRank(cmd, t, sorted, self);
    }

    private void appendRow(@Nonnull UICommandBuilder cmd, int index, int rank,
                           @Nonnull UUID uuid, @Nonnull LeaderboardEntry e, boolean isSelf) {
        cmd.append("#LeaderboardList", ROW_TEMPLATE);
        String sel = "#LeaderboardList[" + index + "]";
        cmd.set(sel + " #Rank.Text", Message.raw("#" + rank));
        cmd.set(sel + " #Player.Text", Message.raw(resolveName(uuid, e)));
        cmd.set(sel + " #Score.Text", Message.raw(NumberFormatter.grouped(e.bestScore)));
        cmd.set(sel + " #Time.Text", Message.raw(e.bestTimeSeconds > 0 ? formatTime(e.bestTimeSeconds) : "-"));
        cmd.set(sel + " #Plays.Text", Message.raw(Integer.toString(e.plays)));
        if (rank == 1) {
            cmd.set(sel + " #Rank.Style.TextColor", "#ffd700");
        } else if (rank == 2) {
            cmd.set(sel + " #Rank.Style.TextColor", "#c0c0c0");
        } else if (rank == 3) {
            cmd.set(sel + " #Rank.Style.TextColor", "#cd7f32");
        }
        if (isSelf) {
            cmd.set(sel + ".Background", "#1a3d4a");
        }
    }

    private void setYourRank(@Nonnull UICommandBuilder cmd, @Nonnull LeaderboardScreenMessages t,
                             @Nonnull List<Map.Entry<UUID, LeaderboardEntry>> sorted, @Nonnull UUID self) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(self)) {
                cmd.set("#YourRank.Text", t.yourRank(i + 1, sorted.get(i).getValue().bestScore));
                return;
            }
        }
        cmd.set("#YourRank.Text", t.yourRankNone());
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull LeaderboardEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if ("tab".equals(data.action) && data.bucket != null) {
            player.getPageManager().openCustomPage(ref, store, new LeaderboardPage(playerRef, deps, data.bucket));
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    @Nonnull
    private static String resolveName(@Nonnull UUID uuid, @Nonnull LeaderboardEntry e) {
        try {
            PlayerRef pr = Universe.get().getPlayer(uuid);
            if (pr != null) {
                String live = pr.getUsername();
                if (live != null && !live.isBlank()) {
                    return live;
                }
            }
        } catch (Throwable ignored) {
        }
        if (e.name != null && !e.name.isBlank()) {
            return e.name;
        }
        return uuid.toString().substring(0, 8);
    }

    @Nonnull
    private static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : Integer.toString(s));
    }
}
