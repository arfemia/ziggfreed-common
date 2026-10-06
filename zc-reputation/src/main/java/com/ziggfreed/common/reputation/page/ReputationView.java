package com.ziggfreed.common.reputation.page;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.ReputationText;

/**
 * What the Reputation page shows, decided where a test can reach it: one row per met reputation (in the
 * service's page order), the rank above it or, past the top, where the next Beyond reward lands; which
 * row is read; and the words of a row and of the detail. The page paints this and decides nothing.
 */
public final class ReputationView {

    /**
     * One met reputation: its standing, the rank above (null at the top) and the next Beyond reward (null
     * without one).
     */
    public record Row(@Nonnull ReputationService.Standing standing, @Nullable ReputationLadder.Rank next,
                      @Nullable Long nextReward) {

        @Nonnull
        public ReputationDef reputation() {
            return standing.reputation();
        }

        @Nonnull
        public String id() {
            return standing.reputation().id();
        }
    }

    private ReputationView() {
    }

    @Nonnull
    public static List<Row> rows(@Nonnull List<ReputationService.Standing> met, @Nonnull ReputationLadder ladder) {
        List<Row> out = new ArrayList<>(met.size());
        ReputationLadder.Rank top = ladder.top();
        for (ReputationService.Standing standing : met) {
            ReputationLadder.Rank next = ladder.next(standing.rank());
            int every = standing.reputation().beyondEvery();
            Long reward = next == null && top != null && every > 0
                    ? ReputationLadder.nextRewardAt(standing.earned(), top.min(), every) : null;
            out.add(new Row(standing, next, reward));
        }
        return List.copyOf(out);
    }

    /** The row named {@code selectedId} (any case), else the first; null when there are none. */
    @Nullable
    public static Row pick(@Nonnull List<Row> rows, @Nullable String selectedId) {
        if (rows.isEmpty()) {
            return null;
        }
        if (selectedId != null) {
            for (Row row : rows) {
                if (row.id().equalsIgnoreCase(selectedId.trim())) {
                    return row;
                }
            }
        }
        return rows.get(0);
    }

    /** "Old Jack's Favor: Trusted, 750 to Confidant (+100 from gear)", by parts that each have a key. */
    @Nonnull
    public static Message rowLine(@Nonnull Row row) {
        ReputationService.Standing standing = row.standing();
        ReputationDef def = standing.reputation();
        Message line = ReputationText.line("page.row", ReputationText.name(def),
                ReputationText.rankName(def, standing.rank()));
        if (row.next() != null) {
            line = ReputationText.line("page.row.next", line, row.next().min() - standing.effective(),
                    ReputationText.rankName(def, row.next()));
        } else if (row.nextReward() != null) {
            line = ReputationText.line("page.row.reward", line, row.nextReward() - standing.earned());
        }
        if (standing.gear() > 0) {
            line = ReputationText.line("page.row.gear", line, standing.gear());
        } else if (standing.gear() < 0) {
            line = ReputationText.line("page.row.gear.loss", line, standing.gear());
        }
        return line;
    }

    /** The detail's lines: the description, earned, gear, what comes next, and the cap. */
    @Nonnull
    public static List<Message> detailLines(@Nonnull Row row) {
        List<Message> out = new ArrayList<>();
        ReputationService.Standing standing = row.standing();
        ReputationDef def = standing.reputation();
        Message description = ReputationText.description(def);
        if (description != null) {
            out.add(description);
        }
        out.add(ReputationText.line("detail.earned", standing.earned()));
        if (standing.gear() > 0) {
            out.add(ReputationText.line("detail.gear", standing.gear()));
        } else if (standing.gear() < 0) {
            out.add(ReputationText.line("detail.gear.loss", standing.gear()));
        }
        if (row.next() != null) {
            out.add(ReputationText.line("detail.next", ReputationText.rankName(def, row.next()), row.next().min()));
        } else if (row.nextReward() != null) {
            out.add(ReputationText.line("detail.reward", row.nextReward()));
        } else {
            out.add(ReputationText.line("detail.top"));
        }
        if (def.cap() != null) {
            out.add(ReputationText.line("detail.cap", def.cap()));
        }
        return out;
    }
}
