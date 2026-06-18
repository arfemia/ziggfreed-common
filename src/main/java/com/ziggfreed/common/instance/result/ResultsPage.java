package com.ziggfreed.common.instance.result;

import java.util.List;
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
 * The generic end-of-game results screen (the user's primary ask). Rendered from a
 * {@link MatchResult} snapshot the consumer builds at its resolve choke-point: an outcome
 * header (tinted by {@link ResultKind}), the team-grouped per-player score breakdown (the
 * viewer highlighted, MVP/medal tinted), the viewer's score-column breakdown, the granted
 * / pending reward chips, and footer CTAs (View Leaderboard / Play Again / Close).
 *
 * <p>Consumer policy (the chrome {@link ResultsMessages}, the {@link ResultsActions}
 * footer handlers) is supplied through {@link ResultsPageDeps}; the page is mod-agnostic.
 * Non-blocking by design - the player may dismiss it and the consumer's normal eject runs.
 * Every {@code handleDataEvent} exit path sends a response.
 */
public class ResultsPage extends InteractiveCustomUIPage<ResultsEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigResultsPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigResultRow.ui";
    private static final String CHIP_TEMPLATE = "Pages/ZigResultChip.ui";

    private final MatchResult result;
    private final ResultsPageDeps deps;

    public ResultsPage(@Nonnull PlayerRef playerRef, @Nonnull MatchResult result, @Nonnull ResultsPageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ResultsEventData.CODEC);
        this.result = result;
        this.deps = deps;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ResultsMessages t = deps.text();
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        cmd.set("#OutcomeTitle.Text", t.outcomeTitle(result.kind()));
        cmd.set("#OutcomeTitle.Style.TextColor", outcomeColor(result.kind()));
        cmd.set("#Duration.Text", t.duration(result.durationSeconds()));

        // Team-grouped per-player rows.
        boolean multiTeam = result.teams().size() > 1;
        int row = 0;
        PlayerResultRow viewerRow = null;
        for (TeamResult team : result.teams()) {
            if (multiTeam && team.teamLabel() != null) {
                appendRow(cmd, row++, team.teamLabel(), t.teamTotal(team.teamTotal()), "#ffd97a");
            }
            for (PlayerResultRow pr : team.rows()) {
                if (pr.isViewer()) {
                    viewerRow = pr;
                }
                Message nameMsg = Message.raw(name(pr.uuid()));
                Message scoreMsg = Message.raw(NumberFormatter.grouped(pr.primaryScore()));
                String color = pr.isMvp() ? "#ffd700" : (pr.isViewer() ? "#5ab0ff" : "#d7e4f0");
                String sel = appendRow(cmd, row++, nameMsg, scoreMsg, color);
                if (pr.isViewer()) {
                    cmd.set(sel + ".Background", "#1a3d4a");
                }
            }
        }

        // Viewer's score-column breakdown.
        cmd.set("#BreakdownTitle.Text", t.breakdownTitle());
        if (viewerRow != null) {
            int b = 0;
            for (ScoreColumn col : viewerRow.columns()) {
                cmd.append("#BreakdownList", ROW_TEMPLATE);
                String sel = "#BreakdownList[" + b++ + "]";
                cmd.set(sel + " #Cell1.Text", col.label());
                cmd.set(sel + " #Cell2.Text", Message.raw(col.formatted()));
            }
        }

        // Reward strip.
        cmd.set("#RewardsTitle.Text", t.rewardsTitle());
        List<RewardChip> chips = result.rewards();
        if (chips.isEmpty()) {
            cmd.set("#NoRewards.Visible", true);
            cmd.set("#NoRewards.Text", t.noRewards());
        } else {
            boolean anyPending = false;
            for (int i = 0; i < chips.size(); i++) {
                RewardChip chip = chips.get(i);
                cmd.append("#RewardList", CHIP_TEMPLATE);
                String sel = "#RewardList[" + i + "]";
                cmd.set(sel + " #ChipLabel.Text", chip.label());
                if (chip.pending()) {
                    cmd.set(sel + " #ChipLabel.Style.TextColor", "#e0a030");
                    anyPending = true;
                }
            }
            if (anyPending) {
                cmd.set("#PendingNote.Visible", true);
                cmd.set("#PendingNote.Text", t.pendingNote());
            }
        }

        // Footer CTAs.
        ResultsActions actions = deps.actions();
        if (actions != null && result.leaderboardBucket() != null) {
            cmd.set("#ViewLbBtn.Visible", true);
            cmd.set("#ViewLbBtn.Text", t.viewLeaderboardButton());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ViewLbBtn", EventData.of("Action", "leaderboard"));
        }
        if (actions != null) {
            cmd.set("#PlayAgainBtn.Visible", true);
            cmd.set("#PlayAgainBtn.Text", t.playAgainButton());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#PlayAgainBtn", EventData.of("Action", "again"));
        }
    }

    @Nonnull
    private String appendRow(@Nonnull UICommandBuilder cmd, int row, @Nonnull Message cell1,
                             @Nonnull Message cell2, @Nonnull String cell1Color) {
        cmd.append("#ResultsList", ROW_TEMPLATE);
        String sel = "#ResultsList[" + row + "]";
        cmd.set(sel + " #Cell1.Text", cell1);
        cmd.set(sel + " #Cell1.Style.TextColor", cell1Color);
        cmd.set(sel + " #Cell2.Text", cell2);
        return sel;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ResultsEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        ResultsActions actions = deps.actions();
        String action = data.action == null ? "" : data.action;
        if (actions != null && "leaderboard".equals(action)) {
            actions.viewLeaderboard(playerRef, ref, store, result.leaderboardBucket());
            return; // the handler owns the next page
        }
        if (actions != null && "again".equals(action)) {
            actions.playAgain(playerRef, ref, store);
            return;
        }
        player.getPageManager().setPage(ref, store, Page.None);
    }

    @Nonnull
    private static String outcomeColor(@Nonnull ResultKind kind) {
        return switch (kind) {
            case WIN -> "#ffd700";
            case LOSS -> "#e05a5a";
            case DRAW -> "#b6c9de";
            case ABORT -> "#8696a8";
        };
    }

    @Nonnull
    private static String name(@Nonnull UUID uuid) {
        try {
            PlayerRef p = Universe.get().getPlayer(uuid);
            if (p != null) {
                String live = p.getUsername();
                if (live != null && !live.isBlank()) {
                    return live;
                }
            }
        } catch (Throwable ignored) {
        }
        return uuid.toString().substring(0, 8);
    }
}
