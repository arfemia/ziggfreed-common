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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.ui.toast.ToastKind;
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
    /** When set, the page is a forward-looking PREVIEW: this note replaces the reward strip (nothing granted yet). */
    @Nullable private final Message rewardsPreviewNote;
    /** Flipped once the viewer presses Claim: hides the claim button + shows the claimed/pending note on re-render. */
    private boolean claimed = false;
    /** Whether the last claim delivered everything (false = some reward held for a later claim - full inventory). */
    private boolean claimAllSucceeded = false;

    public ResultsPage(@Nonnull PlayerRef playerRef, @Nonnull MatchResult result, @Nonnull ResultsPageDeps deps) {
        this(playerRef, result, deps, null);
    }

    /**
     * Preview overload: when {@code rewardsPreviewNote} is non-null the page renders that note in place of
     * the reward strip and skips the granted-rewards toast - the in-instance preview shown BEFORE the
     * consumer grants the rewards on its return path. A {@code null} note is the normal granted open.
     */
    public ResultsPage(@Nonnull PlayerRef playerRef, @Nonnull MatchResult result, @Nonnull ResultsPageDeps deps,
                       @Nullable Message rewardsPreviewNote) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ResultsEventData.CODEC);
        this.result = result;
        this.deps = deps;
        this.rewardsPreviewNote = rewardsPreviewNote;
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

        // Viewer's point-breakdown (how the score was earned).
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

        // Viewer's run-stats breakdown (raw per-run activity, a second section below the points).
        cmd.set("#StatsTitle.Text", t.statsTitle());
        if (viewerRow != null) {
            int s = 0;
            for (ScoreColumn col : viewerRow.statColumns()) {
                cmd.append("#StatsList", ROW_TEMPLATE);
                String sel = "#StatsList[" + s++ + "]";
                setCell(cmd, sel + " #Cell1", col.label());
                setCell(cmd, sel + " #Cell2", col.formatted());
            }
        }

        // Reward strip: a preview note (in-instance), the no-spoils line, or the claimable spoils chips.
        // Nothing is granted on render - the player presses Claim (a manual claim, full-inventory guarded).
        cmd.set("#RewardsTitle.Text", t.rewardsTitle());
        List<RewardChip> chips = result.rewards();
        if (rewardsPreviewNote != null) {
            cmd.set("#NoRewards.Visible", true);
            cmd.set("#NoRewards.Text", rewardsPreviewNote);
        } else if (chips.isEmpty()) {
            cmd.set("#NoRewards.Visible", true);
            cmd.set("#NoRewards.Text", t.noRewards());
        } else {
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
            }
            // After a claim, the note replaces the (now-hidden) Claim button: green = all delivered,
            // amber = some held for a later claim (a full inventory at claim time).
            if (claimed) {
                cmd.set("#PendingNote.Visible", true);
                cmd.set("#PendingNote.Text", claimAllSucceeded ? t.claimedNote() : t.pendingNote());
                cmd.set("#PendingNote.Style.TextColor", claimAllSucceeded ? "#7ad17a" : "#e0a030");
            }
        }

        // Claim button (overworld, unclaimed spoils only) + footer CTAs.
        ResultsActions actions = deps.actions();
        if (rewardsPreviewNote == null && actions != null && !claimed && !chips.isEmpty()) {
            cmd.set("#ClaimBtn.Visible", true);
            cmd.set("#ClaimBtn.Text", t.claimButton());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimBtn", EventData.of("Action", "claim"));
        }
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
        if (actions != null && "claim".equals(action)) {
            boolean allClaimed = actions.claimRewards(playerRef, ref, store);
            claimed = true;
            claimAllSucceeded = allClaimed;
            // Re-render in the claimed state (button gone, note shown); prime the toast so this build paints it.
            primeToast(ToastSpec.of(ToastKind.REWARD,
                    allClaimed ? deps.text().claimedNote() : deps.text().pendingNote()));
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
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
