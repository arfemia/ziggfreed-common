package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerContext;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.LedgerSource;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * The picks waiting, on the Claims tab ({@code LedgerContributions.CLAIMS}): owed picks are never Claims, so this is
 * how they show there. One section, a row per track with anything waiting (how many choices), and a row's page lists
 * the next few slots with their offers (the option a Remember prestige preselects marked), the free picks and the
 * points left to spend. It only lists: a choice is made on the page a consumer builds over {@link PickService}.
 * Nothing while no track carries a pick.
 */
public final class PickClaims implements LedgerSource {

    /** The one instance the library contributes. */
    public static final PickClaims INSTANCE = new PickClaims();

    /** This source's id on the Claims surface. */
    public static final String ID = "ziggfreedcommon:track_picks";

    /** Where the picks sit among the tab's other sources: after the Claims themselves, before a consumer's own. */
    public static final int ORDER = 100;

    /** The section's id. */
    public static final String SECTION = "picks";

    /** How many owed picks a row's page lists before saying how many more wait. */
    static final int PAGE_LIMIT = 6;

    private PickClaims() {
    }

    @Nonnull
    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Nonnull
    @Override
    public List<LedgerSection> sections(@Nonnull LedgerContext ctx) {
        UUID player = ctx.viewer().getUuid();
        if (player == null || !TrackPicks.any()) {
            return List.of();
        }
        List<LedgerRow> rows = new ArrayList<>();
        for (PickService.Waiting waiting : PickService.waiting(player)) {
            TrackDef def = TrackService.def(waiting.trackId());
            if (def == null) {
                continue;
            }
            rows.add(new LedgerRow(def.id(), TrackText.name(def.asset()),
                    TrackText.line("claims.row.meta", waiting.count()), Picture.item(def.asset().icon()), Tone.COLLECT,
                    null, null, null, Mark.NONE, false));
        }
        return rows.isEmpty() ? List.of()
                : List.of(new LedgerSection(SECTION, TrackText.line("claims.section"), rows, true));
    }

    @Nullable
    @Override
    public DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx) {
        UUID player = ctx.viewer().getUuid();
        TrackDef def = TrackService.def(rowId);
        if (player == null || def == null) {
            return null;
        }
        PickService.Waiting waiting = PickService.waiting(player, def.id());
        if (waiting.isEmpty()) {
            return null;
        }
        List<DetailBlock> blocks = new ArrayList<>();
        int shown = 0;
        for (PickService.Owed owed : waiting.owed()) {
            if (shown >= PAGE_LIMIT) {
                break;
            }
            blocks.add(block(owed));
            shown++;
        }
        if (waiting.pointsLeft() > 0) {
            blocks.add(new DetailBlock("points", TrackText.line("claims.block.points"), null,
                    List.of(DetailLine.of(Picture.item(def.asset().icon()),
                            TrackText.line("claims.line.points", waiting.pointsLeft())))));
        }
        int more = waiting.owed().size() - shown;
        Message hint = more > 0 ? TrackText.line("claims.page.more", more) : null;
        return new DetailView(Picture.item(def.asset().icon()), TrackText.name(def.asset()),
                TrackText.line("claims.row.meta", waiting.count()), null, List.of(), null, null, null,
                TrackText.line("claims.page.lead"), blocks, List.of(), hint);
    }

    /** One owed pick as a block: its level, then each option offered (or the pick itself), the preselected marked. */
    @Nonnull
    private static DetailBlock block(@Nonnull PickService.Owed owed) {
        List<DetailLine> lines = new ArrayList<>();
        if (owed.isChoice()) {
            for (String option : owed.offer()) {
                PickOptionAsset asset = PickOptionConfig.getInstance().resolve(option);
                Pill tag = owed.preselected().contains(option)
                        ? Pill.of(TrackText.line("claims.tag.remembered"), Tone.AVAILABLE) : null;
                lines.add(new DetailLine(Picture.item(asset == null ? null : asset.icon()), TrackText.pickName(option),
                        null, tag, Tick.NONE, null, false));
            }
        } else {
            TrackPickAsset asset = TrackPickConfig.getInstance().resolve(owed.pickId());
            lines.add(DetailLine.of(Picture.item(asset == null ? null : asset.icon()),
                    TrackText.pickName(owed.pickId())));
        }
        Message meta = owed.choose() > 1 ? TrackText.line("claims.block.choose", owed.choose()) : null;
        String id = owed.slot() != null ? owed.slot() : owed.pickId();
        return new DetailBlock(id, owed.isChoice() ? TrackText.line("claims.block.level", owed.level())
                : TrackText.line("claims.block.pick"), meta, lines);
    }
}
