package com.ziggfreed.common.almanac.page;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacComponent;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Almanac: every season this server runs on the left, the one being read on the right. It paints
 * {@link AlmanacView} and decides nothing: which seasons are listed, which year a section shows and
 * what counts as earned are the view's. Picking a season reopens the page, so every build is a full
 * one and no row is ever addressed by a recomputed index. It sits in the shared menu frame with the
 * Almanac tab selected on the rail. Every {@code handleDataEvent} exit opens a page, closes this one, or
 * answers a rail click nothing opened for.
 */
public final class AlmanacPage extends InteractiveCustomUIPage<AlmanacEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigAlmanacPage.ui";
    static final String ROW_TEMPLATE = "Pages/ZigSelectRow.ui";
    private static final String LINE_TEMPLATE = "Pages/ZigDetailLine.ui";

    /** The slot beside the season's name that holds its picture: the two widgets {@link IconRenderer} drives. */
    static final String SEASON_ICON = "#DetailIconSlot";

    /**
     * The same picture's slot in each season's list row: the shared row's own hidden slot, addressed
     * under the row's indexed parent.
     */
    static final String ROW_ICON = "#RowIconSlot";

    private static final String LIVE_DOT = "#7ad17a";
    private static final String IDLE_DOT = "#96a9be";
    private static final String HEADING = "#ffd97a";
    private static final String ROW_SELECTED_TINT = "#1a2d44";
    private static final String ROW_SELECTED_TEXT = "#ffffff";

    @Nullable
    private String selected;

    /** The rail this build painted. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    public AlmanacPage(@Nonnull PlayerRef playerRef, @Nullable String selectedEventId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, AlmanacEventData.CODEC);
        this.selected = selectedEventId;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL, "#LeftPanel");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        cmd.set("#AlmanacTitle.TextSpans", AlmanacText.line("title"));
        // The shared menu's rail, the Almanac tab selected; painted before the empty-list early return.
        rail = ZigMenu.paint(cmd, events, store, ref, store.getComponent(ref, Player.getComponentType()),
                MenuSlot.ALMANAC.id(), false);

        Map<String, AlmanacEntryAsset> pages = AlmanacEntryConfig.getInstance().all();
        List<AlmanacView.Season> seasons = AlmanacView.seasons(pages, OccurrenceAlmanacCalendar.INSTANCE);
        AchievementEngine engine = ProgressionRuntime.achievements();
        Subject subject = achievementSubject(store, ref);
        paintBanner(cmd, engine, subject);

        AlmanacView.Season season = AlmanacView.pick(seasons, selected);
        if (season == null) {
            cmd.set("#EmptyListLabel.TextSpans", AlmanacText.line("seasons.empty"));
            cmd.set("#EmptyListLabel.Visible", true);
            cmd.set("#RightPanel.Visible", false);
            return;
        }
        selected = season.eventId();
        for (int i = 0; i < seasons.size(); i++) {
            paintRow(cmd, events, i, seasons.get(i));
        }
        paintDetail(cmd, AlmanacView.detail(season, pages.get(season.eventId()), tallies(store, ref), engine, subject));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull AlmanacEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (rail.handle(data.menu, store, ref, player, this::answer)) {
            return;
        }
        if ("select".equals(data.action) && data.season != null && !data.season.isBlank()) {
            selected = data.season;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    /** An empty update, for a rail click nothing opened for: the client always hears back. */
    private void answer() {
        sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    private void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, int index,
            @Nonnull AlmanacView.Season season) {
        cmd.append("#SeasonList", ROW_TEMPLATE);
        String sel = "#SeasonList[" + index + "]";
        ZigRichButton.text(cmd, sel + " #RowBtn", AlmanacText.authored(season.titleKey(), season.eventId()));
        String icon = sel + " " + ROW_ICON;
        cmd.set(icon + ".Visible", IconRenderer.applyIcon(cmd, icon, season.icon(), null));
        cmd.set(sel + " #StatusDot.Background", season.live() ? LIVE_DOT : IDLE_DOT);
        if (season.live()) {
            cmd.set(sel + " #RowBadge.Visible", true);
            UiText.setText(cmd, sel + " #RowBadge.Text", AlmanacText.line("status.live"));
        }
        if (season.eventId().equals(selected)) {
            UiRetint.retintButtonStates(cmd, sel + " #RowBtn", ROW_SELECTED_TINT, ROW_SELECTED_TINT, ROW_SELECTED_TINT);
            ZigRichButton.color(cmd, sel + " #RowBtn", ROW_SELECTED_TEXT);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RowBtn",
                EventData.of("Action", "select").append("Season", season.eventId()), false);
    }

    private static void paintBanner(@Nonnull UICommandBuilder cmd, @Nonnull AchievementEngine engine,
            @Nullable Subject subject) {
        AlmanacView.Banner banner = subject == null ? null : AlmanacView.banner(engine, subject);
        if (banner == null) {
            return;
        }
        Message title = ProgressionTexts.titleOrUntitled(banner.achievementId());
        Message line;
        if (banner.earned()) {
            line = AlmanacText.line("banner.earned", title);
        } else if (banner.childrenTotal() > 0) {
            line = AlmanacText.line("banner.line", title, banner.childrenEarned(), banner.childrenTotal());
        } else {
            line = title;
        }
        cmd.set("#BannerLabel.TextSpans", line);
        cmd.set("#BannerLabel.Visible", true);
    }

    private static void paintDetail(@Nonnull UICommandBuilder cmd, @Nonnull AlmanacView.Detail detail) {
        AlmanacView.Season season = detail.season();
        cmd.set("#DetailTitle.TextSpans", AlmanacText.authored(season.titleKey(), season.eventId()));
        cmd.set(SEASON_ICON + ".Visible", IconRenderer.applyIcon(cmd, SEASON_ICON, season.icon(), null));
        cmd.set("#DetailStatus.TextSpans", AlmanacText.line(season.live() ? "status.live" : "status.between"));
        if (season.flavorKey() != null) {
            cmd.set("#Flavor.TextSpans", AlmanacText.authored(season.flavorKey(), season.eventId()));
            cmd.set("#Flavor.Visible", true);
        }
        int at = 0;
        Integer year = detail.shownYear();
        if (year != null) {
            // A year is a label, never a quantity: passed as text so no locale groups it.
            at = heading(cmd, at, AlmanacText.line(season.live() ? "section.season" : "section.last",
                    String.valueOf(year)));
            if (detail.attendedShownYear()) {
                at = line(cmd, at, AlmanacText.line("attended.yes"), null);
            }
            at = statLines(cmd, at, detail.seasonLines());
        }
        at = heading(cmd, at, AlmanacText.line("section.lifetime"));
        at = line(cmd, at, AlmanacText.line("attended.count", detail.seasonsAttended()), null);
        at = statLines(cmd, at, detail.lifetimeLines());
        at = heading(cmd, at, AlmanacText.line("section.keepsakes"));
        if (detail.keepsakes().isEmpty()) {
            at = line(cmd, at, AlmanacText.line("keepsake.none"), null);
        }
        for (AlmanacView.Keepsake keepsake : detail.keepsakes()) {
            at = line(cmd, at, AlmanacText.line("keepsake.line", String.valueOf(keepsake.year()),
                    ProgressionTexts.titleOrUntitled(keepsake.achievementId())), keepsake.icon());
        }
        if (detail.achievementsListed() > 0 || !detail.feats().isEmpty()) {
            at = heading(cmd, at, AlmanacText.line("section.achievements"));
            if (detail.achievementsListed() > 0) {
                at = line(cmd, at, AlmanacText.line("achievements.count", detail.achievementsEarned(),
                        detail.achievementsListed()), null);
            }
            for (AlmanacView.Feat feat : detail.feats()) {
                at = line(cmd, at, ProgressionTexts.titleOrUntitled(feat.achievementId()), feat.icon());
            }
        }
    }

    private static int statLines(@Nonnull UICommandBuilder cmd, int at, @Nonnull List<AlmanacView.StatLine> lines) {
        if (lines.isEmpty()) {
            return line(cmd, at, AlmanacText.line("stat.none"), null);
        }
        int next = at;
        for (AlmanacView.StatLine stat : lines) {
            next = line(cmd, next, AlmanacText.line("stat.line",
                    AlmanacText.authored(stat.textKey(), stat.statId()), stat.count()), stat.icon());
        }
        return next;
    }

    private static int heading(@Nonnull UICommandBuilder cmd, int at, @Nonnull Message text) {
        int next = line(cmd, at, text, null);
        cmd.set("#DetailList[" + at + "] #LineText.Style.TextColor", HEADING);
        return next;
    }

    private static int line(@Nonnull UICommandBuilder cmd, int at, @Nonnull Message text, @Nullable String icon) {
        cmd.append("#DetailList", LINE_TEMPLATE);
        String sel = "#DetailList[" + at + "]";
        cmd.set(sel + " #LineText.TextSpans", text);
        cmd.set(sel + " #LineIconSlot.Visible", IconRenderer.applyIcon(cmd, sel, icon, null));
        return at + 1;
    }

    @Nonnull
    private static CounterMap tallies(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, AlmanacComponent> type = AlmanacComponent.TYPE;
        AlmanacComponent record = type == null ? null : store.getComponent(ref, type);
        return record == null ? new CounterMap() : record.tallies;
    }

    @Nullable
    private static Subject achievementSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return ProgressionRuntime.subjects().achievementSubject(store, ref);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] no achievement subject for the page: " + t.getMessage());
            return null;
        }
    }
}
