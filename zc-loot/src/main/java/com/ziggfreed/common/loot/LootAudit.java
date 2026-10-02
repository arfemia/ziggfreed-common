package com.ziggfreed.common.loot;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Every loaded loot table, audited on demand against the reward kinds this server actually pays.
 *
 * <p>It runs when an owner asks ({@code /zigloot validate}), never at boot: a consumer that audits
 * the tables in its own boot pass already reports every one of these lines, and a second pass would
 * print each of them twice. On a server with no such consumer, this is how an author finds out what
 * a table does wrong.
 */
public final class LootAudit {

    /** The label every logged line carries. */
    public static final String LOG_LABEL = "[loot] tables";

    private LootAudit() {
    }

    /** Audit every loaded table as authored, its rewards checked against the shared vocabulary. */
    @Nonnull
    public static List<Finding> auditAll() {
        return LootableValidator.auditAll(RewardKinds.shared());
    }

    /** Push findings already in hand at the server log, split by how much each one matters. */
    public static void log(@Nonnull List<Finding> findings) {
        ValidationReport.logAll(LOG_LABEL, findings, SafeLog::warn, SafeLog::info);
    }
}
