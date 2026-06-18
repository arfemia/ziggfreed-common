package com.ziggfreed.common.instance.result;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
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
public class ResultsPage extends ToastablePage<ResultsEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigResultsPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigResultRow.ui";
    private static final String CHIP_TEMPLATE = "Pages/ZigResultChip.ui";

    private final MatchResult result;
    private final ResultsPageDeps deps;
    /** One-shot: the granted-rewards toast fires only on the first open, not on reopen. */
    private boolean rewardToastPrimed = false;

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
                // Name (a proper noun) + score are plain locale-neutral data -> plain String.
                String color = pr.isMvp() ? "#ffd700" : (pr.isViewer() ? "#5ab0ff" : "#d7e4f0");
                String sel = appendRow(cmd, row++, name(pr.uuid()), NumberFormatter.grouped(pr.primaryScore()), color);
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
                setCell(cmd, sel + " #Cell1", col.label());      // localized label -> TextSpans
                setCell(cmd, sel + " #Cell2", col.formatted());  // formatted number -> .Text
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
                String icon = chip.iconItemId();
                if (icon != null && !icon.isEmpty()) {
                    cmd.set(sel + " #ChipIcon.Visible", true);
                    cmd.set(sel + " #ChipIcon.Slots", List.of(new ItemGridSlot(new ItemStack(icon, 1))));
                }
                cmd.set(sel + " #ChipLabel.TextSpans", chip.label()); // composite Message (name + amount) -> TextSpans
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

        // One-shot in-page reward toast on first open (the chips persist below; the toast is a
        // transient gold flourish, and the notification feed is hidden behind the open menu).
        // Primed (no immediate push) so this same build's renderToastInto paints it.
        if (!rewardToastPrimed) {
            rewardToastPrimed = true;
            if (!chips.isEmpty()) {
                List<ToastLine> lines = new ArrayList<>(chips.size());
                for (RewardChip chip : chips) {
                    lines.add(new ToastLine(chip.iconItemId(), 1, chip.label()));
                }
                primeToast(ToastSpec.of(ToastKind.REWARD, t.rewardsTitle()).withLines(lines));
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

        renderToastInto(cmd); // LAST: the toast overlay draws on top of the page content.
    }

    @Nonnull
    private String appendRow(@Nonnull UICommandBuilder cmd, int row, @Nonnull Object cell1,
                             @Nonnull Object cell2, @Nonnull String cell1Color) {
        cmd.append("#ResultsList", ROW_TEMPLATE);
        String sel = "#ResultsList[" + row + "]";
        setCell(cmd, sel + " #Cell1", cell1);
        cmd.set(sel + " #Cell1.Style.TextColor", cell1Color);
        setCell(cmd, sel + " #Cell2", cell2);
        return sel;
    }

    /**
     * Set a cell's text from a plain String (locale-neutral data -> {@code .Text}) or a Message
     * (localized -> {@code .TextSpans}). A Label's {@code .Text} is a client String property that
     * cannot construct from a raw/composite Message object (it aborts the whole CustomUI update);
     * {@code TextSpans} is the Message sink (the dialogue-page pattern).
     */
    private static void setCell(@Nonnull UICommandBuilder cmd, @Nonnull String elem, @Nonnull Object value) {
        if (value instanceof Message m) {
            cmd.set(elem + ".TextSpans", m);
        } else {
            cmd.set(elem + ".Text", String.valueOf(value));
        }
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
