package com.ziggfreed.common.almanac.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacComponent;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.OccurrenceAlmanacCalendar;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.almanac.view.AlmanacLines;
import com.ziggfreed.common.almanac.view.AlmanacView;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * The achievement book's Statistics view, its Seasons section: what the Almanac contributes to
 * {@code LedgerContributions.STATISTICS}, so the book shows a player's seasons with no edge to this module
 * (none may have one). One section, a row per listed season (the seasons on now first) with the seasons
 * taken part in; a row's page lists the season's every-season tallies and opens the Almanac on it through
 * the {@code Almanac} destination, which the book answers.
 *
 * <p>Nothing while the Almanac is off or lists no season: the book then hides the section, and the whole
 * view when no other source contributes.
 */
public final class AlmanacStatistics implements LedgerSource {

    /** This source's id on the Statistics surface. */
    public static final String ID = "ziggfreedcommon:almanac";

    /** Where Seasons sits among the Statistics sections: after a consumer's own. */
    public static final int ORDER = 900;

    /** The section's id. */
    public static final String SECTION = "almanac.seasons";

    /** The tallies block's id on a season's page. */
    public static final String TALLIES_BLOCK = "almanac.tallies";

    /** The page action's id (its destination does the opening). */
    public static final String OPEN_ACTION = "almanac.open";

    private final Supplier<Map<String, AlmanacEntryAsset>> pages;
    private final AlmanacCalendar calendar;

    /** A source over {@code pages} (keyed by lower-cased event id) and {@code calendar}. */
    public AlmanacStatistics(@Nonnull Supplier<Map<String, AlmanacEntryAsset>> pages,
            @Nonnull AlmanacCalendar calendar) {
        this.pages = pages;
        this.calendar = calendar;
    }

    /** The production source: the loaded season pages and the server's calendar, read on every look. */
    @Nonnull
    public static AlmanacStatistics production() {
        return new AlmanacStatistics(() -> AlmanacEntryConfig.getInstance().all(), OccurrenceAlmanacCalendar.INSTANCE);
    }

    @Override
    @Nonnull
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    @Nonnull
    public List<LedgerSection> sections(@Nonnull LedgerContext ctx) {
        return sections(AlmanacComponent.talliesOf(ctx.store(), ctx.ref()));
    }

    @Override
    @Nullable
    public DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx) {
        return page(rowId, AlmanacComponent.talliesOf(ctx.store(), ctx.ref()), ctx.nowMs());
    }

    /** The Seasons section for a player holding {@code tallies}; empty while the Almanac is off or lists nothing. */
    @Nonnull
    List<LedgerSection> sections(@Nonnull CounterMap tallies) {
        if (!AlmanacSwitch.isOn()) {
            return List.of();
        }
        List<Season> seasons = AlmanacView.seasons(pages.get(), calendar);
        if (seasons.isEmpty()) {
            return List.of();
        }
        List<LedgerRow> rows = new ArrayList<>();
        for (Season season : seasons) {
            rows.add(new LedgerRow(season.eventId(), title(season),
                    AlmanacText.line("stats.row.meta", takenPart(tallies, season)), Picture.item(season.icon()),
                    season.live() ? Tone.LIVE : Tone.NEUTRAL, season.live() ? AlmanacText.line("status.live") : null,
                    null, null, Mark.NONE, false));
        }
        return List.of(new LedgerSection(SECTION, AlmanacText.line("stats.section"), rows, true));
    }

    /**
     * One season's page: when it runs, its every-season tallies (no block when it counts nothing), and a
     * way into the Almanac on it. Null while the Almanac is off and for a season no longer listed.
     */
    @Nullable
    DetailView page(@Nonnull String rowId, @Nonnull CounterMap tallies, long nowMs) {
        if (!AlmanacSwitch.isOn()) {
            return null;
        }
        Map<String, AlmanacEntryAsset> all = pages.get();
        String wanted = AlmanacKeys.normalize(rowId);
        Season season = null;
        for (Season listed : AlmanacView.seasons(all, calendar)) {
            if (listed.eventId().equals(wanted)) {
                season = listed;
                break;
            }
        }
        if (season == null) {
            return null;
        }
        long takenPart = takenPart(tallies, season);
        List<DetailLine> lines = new ArrayList<>();
        for (Tally tally : AlmanacView.tallies(all.get(season.eventId()), season.eventId(), tallies, Scope.EVERY,
                null)) {
            lines.add(new DetailLine(Picture.item(tally.icon()), AlmanacText.authored(tally.textKey(), tally.statId()),
                    Msg.num(tally.figure()), null, Tick.NONE, null, false));
        }
        List<DetailBlock> blocks = lines.isEmpty() ? List.of() : List.of(new DetailBlock(TALLIES_BLOCK,
                AlmanacLines.scopeHeader(Scope.EVERY), AlmanacLines.scopeMeta(Scope.EVERY, takenPart > 0L, takenPart),
                lines));
        DetailAction open = new DetailAction(ActionSlot.PRIMARY, AlmanacText.line("stats.open"), ActionLook.NORMAL,
                OPEN_ACTION, AlmanacDestinations.Almanac.of(season.eventId()), true, null);
        Timing timing = AlmanacView.timing(season, calendar, nowMs);
        Message lead = season.flavorKey() == null ? null : AlmanacText.authored(season.flavorKey(), season.eventId());
        return new DetailView(Picture.item(season.icon()), title(season), AlmanacLines.chip(timing),
                AlmanacLines.window(timing), List.of(), null, null, null, lead, blocks, List.of(open), null);
    }

    @Nonnull
    private static Message title(@Nonnull Season season) {
        return AlmanacText.authored(season.titleKey(), season.eventId());
    }

    private static long takenPart(@Nonnull CounterMap tallies, @Nonnull Season season) {
        return tallies.get(AlmanacKeys.lifetime(season.eventId(), AlmanacKeys.ATTENDED));
    }
}
