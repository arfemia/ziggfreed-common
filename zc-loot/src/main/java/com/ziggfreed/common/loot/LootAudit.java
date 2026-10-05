package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKindValidator;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.trigger.BonusRowAudit;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Every loaded loot table, audited on demand against the reward kinds this server actually pays,
 * then every loaded reward-kind file ({@link RewardKindValidator#auditAll()}), then the library's
 * own bonus rows as they fold, a switched-off row left out ({@link BonusRowAudit#auditAll}, domain
 * {@link BonusRowAudit#DOMAIN bonus_rows}), against the same reward kinds and the factor vocabulary
 * the rolling kinds read, in one list.
 *
 * <p>A reward-kind file that only decorates a Java-registered kind (no {@code Command}, so it gives
 * that kind its {@code Presentation} and the payout stays with the mod) is the legitimate
 * command-less shape, not a mistake: its INFO {@link RewardKindValidator#PRESENTATION_ONLY} note is
 * left out, so a stock server, whose library and consumers ship such files, can still answer that
 * there is nothing to report. Every other reward-kind finding is kept, errors and warnings included.
 *
 * <p>It runs when an owner asks ({@code /zigloot validate}), never at boot: a consumer that audits
 * the tables in its own boot pass already reports those lines, and a second pass would print each
 * of them twice. On a server with no such consumer, this is how an author finds out what a table, a
 * reward-kind file or a bonus row does wrong.
 */
public final class LootAudit {

    /** The label every logged line carries. */
    public static final String LOG_LABEL = "[loot] audit";

    private LootAudit() {
    }

    /**
     * Every loaded loot table, audited as authored against the kinds this server pays, then every
     * loaded reward-kind file, a decoration's note left out, then the library's own bonus rows as
     * they fold (domain {@code bonus_rows}).
     */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> findings = new ArrayList<>(LootableValidator.auditAll(RewardKinds.shared()));
        for (Finding finding : RewardKindValidator.auditAll()) {
            if (!isDecorationNote(finding)) {
                findings.add(finding);
            }
        }
        findings.addAll(BonusRowAudit.auditAll(RewardKinds.shared(), LootRewardKinds.installedFactors()));
        return findings;
    }

    /** Push findings already in hand at the server log, split by how much each one matters. */
    public static void log(@Nonnull List<Finding> findings) {
        ValidationReport.logAll(LOG_LABEL, findings, SafeLog::warn, SafeLog::info);
    }

    /** The validator's note on a file that only decorates a Java-registered kind. */
    private static boolean isDecorationNote(@Nonnull Finding finding) {
        return finding.severity() == Severity.INFO
                && RewardKindValidator.PRESENTATION_ONLY.equals(finding.code());
    }
}
