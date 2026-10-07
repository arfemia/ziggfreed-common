package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The content audit over reputations, filed under the {@value #DOMAIN} domain. Split the way every validator
 * in this family is: {@link #audit()} is the engine walk (it never throws, and finds nothing while the
 * engine's reputation plugin is off) and the second {@code audit} is the pure core a test drives. The MMO's
 * content audit folds it; {@link #logErrorsOnce()} also logs the ERRORs once at boot, so a server without
 * the MMO still hears them.
 *
 * <ul>
 *   <li>ERROR {@link #TOO_FEW_RANKS} (the engine's clamp needs two ranks), {@link #GROUP_WITHOUT_NPC_GROUPS}
 *       (the engine throws on every NPC added while one exists), {@link #KILL_WITHOUT_GROUPS},
 *       {@link #FROM_ON_SHARED_RANK}, {@link #TIER_OUT_OF_RANGE}, {@link #TIER_SHARES_FLOOR} (a floor that would
 *       move a shared rank or can never be reached; ignored), {@link #TIER_SHARES_ID} (a rank of its own written
 *       twice in two cases; the second is ignored);</li>
 *   <li>WARNING {@link #NO_NATIVE_GROUP}, {@link #UNKNOWN_NPC_GROUP}, {@link #UNKNOWN_KILL_GROUP},
 *       {@link #UNKNOWN_GEAR_STAT}, {@link #UNKNOWN_ICON}, {@link #UNKNOWN_RANK}, {@link #CAP_BELOW_LADDER},
 *       {@link #TIER_ABOVE_CAP}, {@link #TIER_WITHOUT_NAME} (a rank of its own with no Name shows its id).</li>
 * </ul>
 */
public final class ReputationValidator {

    public static final String DOMAIN = "reputation";

    public static final String TOO_FEW_RANKS = "TOO_FEW_RANKS";
    public static final String GROUP_WITHOUT_NPC_GROUPS = "GROUP_WITHOUT_NPC_GROUPS";
    public static final String KILL_WITHOUT_GROUPS = "KILL_WITHOUT_GROUPS";
    public static final String NO_NATIVE_GROUP = "NO_NATIVE_GROUP";
    public static final String UNKNOWN_NPC_GROUP = "UNKNOWN_NPC_GROUP";
    public static final String UNKNOWN_KILL_GROUP = "UNKNOWN_KILL_GROUP";
    public static final String UNKNOWN_GEAR_STAT = "UNKNOWN_GEAR_STAT";
    public static final String UNKNOWN_ICON = "UNKNOWN_ICON";
    public static final String UNKNOWN_RANK = "UNKNOWN_RANK";
    public static final String CAP_BELOW_LADDER = "CAP_BELOW_LADDER";
    public static final String FROM_ON_SHARED_RANK = "FROM_ON_SHARED_RANK";
    public static final String TIER_OUT_OF_RANGE = "TIER_OUT_OF_RANGE";
    public static final String TIER_SHARES_FLOOR = "TIER_SHARES_FLOOR";
    public static final String TIER_SHARES_ID = "TIER_SHARES_ID";
    public static final String TIER_ABOVE_CAP = "TIER_ABOVE_CAP";
    public static final String TIER_WITHOUT_NAME = "TIER_WITHOUT_NAME";

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ReputationValidator() {
    }

    /** The engine walk over the loaded ranks, groups and companions. Never throws. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            ReputationNative engine = ReputationRuntime.service().engine();
            if (!engine.available()) {
                return List.of();
            }
            return audit(ReputationConfig.getInstance().all().values(), engine.groups(), engine.ranks(),
                    engine::npcGroupExists, id -> StatIndexCache.resolve(id) != StatIndexCache.UNRESOLVED,
                    id -> Item.getAssetMap().getAsset(id) != null);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the reputation audit failed: " + t.getMessage());
            return List.of();
        }
    }

    /** The pure core. */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<ReputationAsset> companions,
            @Nonnull List<ReputationNative.Group> groups, @Nonnull List<ReputationLadder.Rank> ranks,
            @Nonnull Predicate<String> npcGroupKnown, @Nonnull Predicate<String> statKnown,
            @Nonnull Predicate<String> itemKnown) {
        List<Finding> out = new ArrayList<>();
        if (ranks.size() < 2) {
            out.add(Finding.error(DOMAIN, TOO_FEW_RANKS, ranks.size() + " ReputationRank file(s) are loaded; the "
                    + "engine needs at least two to bound standing, so stored standing can overflow. The library "
                    + "ships seven: check nothing replaced or removed them", "ReputationRank"));
        }
        for (ReputationNative.Group group : groups) {
            auditGroup(group, npcGroupKnown, out);
        }
        for (ReputationAsset companion : companions) {
            if (companion != null && companion.isEnabled()) {
                auditCompanion(companion, groups, ranks, npcGroupKnown, statKnown, itemKnown, out);
            }
        }
        return out;
    }

    /** Log every ERROR of the engine walk, once per boot. */
    public static void logErrorsOnce() {
        if (LOGGED.compareAndSet(false, true)) {
            logErrors(audit());
        }
    }

    /** Log each ERROR in {@code findings}; how many were logged. */
    static int logErrors(@Nonnull List<Finding> findings) {
        int logged = 0;
        for (Finding finding : findings) {
            if (finding.severity() == Severity.ERROR) {
                SafeLog.warn("[reputation] " + finding.code() + " (" + finding.sourceId() + "): " + finding.message());
                logged++;
            }
        }
        return logged;
    }

    /** Let the next {@link #logErrorsOnce} log again; for a test. */
    static void resetForTests() {
        LOGGED.set(false);
    }

    private static void auditGroup(@Nonnull ReputationNative.Group group, @Nonnull Predicate<String> npcGroupKnown,
            @Nonnull List<Finding> out) {
        if (group.npcGroups() == null) {
            out.add(Finding.error(DOMAIN, GROUP_WITHOUT_NPC_GROUPS, "the ReputationGroup '" + group.id()
                    + "' has no NPCGroups key, which makes the engine throw on every NPC added to every world; "
                    + "write \"NPCGroups\": [] for a reputation with no members", group.id()));
            return;
        }
        for (String npcGroup : group.npcGroups()) {
            if (!npcGroupKnown.test(npcGroup)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_NPC_GROUP, "the ReputationGroup '" + group.id()
                        + "' names the NPC group '" + npcGroup + "', which is not loaded; the engine throws on "
                        + "every NPC added while it is missing", group.id()));
            }
        }
    }

    private static void auditCompanion(@Nonnull ReputationAsset companion, @Nonnull List<ReputationNative.Group> groups,
            @Nonnull List<ReputationLadder.Rank> ranks, @Nonnull Predicate<String> npcGroupKnown,
            @Nonnull Predicate<String> statKnown, @Nonnull Predicate<String> itemKnown, @Nonnull List<Finding> out) {
        String id = companion.getId() == null ? "" : companion.getId();
        String where = "the reputation file '" + id + "'";
        if (groups.stream().noneMatch(group -> group.id().equalsIgnoreCase(id.trim()))) {
            out.add(Finding.warning(DOMAIN, NO_NATIVE_GROUP, where + " matches no native ReputationGroup "
                    + "(Server/NPC/Reputation/Groups/<Id>.json), so nothing reads it", id));
        }
        String stat = companion.gearStat();
        if (stat != null && !statKnown.test(stat)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_GEAR_STAT, where + " names the Gear.Stat '" + stat
                    + "', which is no registered stat, so gear adds nothing", id));
        }
        String icon = companion.icon();
        if (icon != null && !itemKnown.test(icon)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_ICON, where + " names the Icon '" + icon
                    + "', which is no loaded item, so its picture is missing", id));
        }
        ReputationLadder shared = ReputationLadder.of(ranks);
        Map<String, Integer> floors = companion.tierFloors();
        ReputationLadder own = ReputationLadder.of(ranks, floors);
        Set<String> refused = new HashSet<>();
        for (ReputationLadder.RefusedTier tier : ReputationLadder.refusedTiers(ranks, floors)) {
            refused.add(tier.id().toLowerCase(Locale.ROOT));
            auditRefusedTier(where, id, tier, shared.top(), out);
        }
        List<ReputationLadder.Rank> ownTiers = own.ranks().subList(shared.ranks().size(), own.ranks().size());
        for (ReputationLadder.Rank tier : ownTiers) {
            if (companion.rankNameKey(tier.id()) == null) {
                out.add(Finding.warning(DOMAIN, TIER_WITHOUT_NAME, where + " gives its own rank '" + tier.id()
                        + "' a From but no Name, so players read the rank's id where its name belongs", id));
            }
        }
        for (Map.Entry<String, String> rank : companion.rankNames().entrySet()) {
            if (own.byId(rank.getKey()) == null && !refused.contains(rank.getKey().toLowerCase(Locale.ROOT))) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_RANK, where + " names the rank '" + rank.getKey()
                        + "', which is neither on the server's ladder nor one of this reputation's own ranks "
                        + "above it, so that name is never shown", id));
            }
        }
        Integer cap = companion.cap();
        ReputationLadder.Rank bottom = shared.bottom();
        if (cap != null && bottom != null && cap < bottom.min()) {
            out.add(Finding.warning(DOMAIN, CAP_BELOW_LADDER, where + " sets Cap " + cap + ", below the ladder's "
                    + "bottom (" + bottom.min() + "), so every gain is cut to nothing", id));
        }
        if (cap != null) {
            for (ReputationLadder.Rank tier : ownTiers) {
                if (tier.min() > cap) {
                    out.add(Finding.warning(DOMAIN, TIER_ABOVE_CAP, where + " sets Cap " + cap + ", below the "
                            + "floor of its own rank '" + tier.id() + "' (" + tier.min() + "), so rewards and kills "
                            + "never reach it", id));
                }
            }
        }
        List<ReputationAsset.Kill> kills = companion.kills();
        for (int i = 0; i < kills.size(); i++) {
            auditKill(where, id, i, kills.get(i), npcGroupKnown, out);
        }
    }

    /** One tier the ladder leaves out, as the ERROR that says why. */
    private static void auditRefusedTier(@Nonnull String where, @Nonnull String id,
            @Nonnull ReputationLadder.RefusedTier tier, @Nullable ReputationLadder.Rank top,
            @Nonnull List<Finding> out) {
        switch (tier.refusal()) {
            case SHARED_RANK -> out.add(Finding.error(DOMAIN, FROM_ON_SHARED_RANK, where + " gives the server's "
                    + "shared rank '" + tier.id() + "' a From of " + tier.from() + "; the shared ranks keep the same "
                    + "floors for every reputation, so it is ignored. A rank above the top needs an id of this "
                    + "reputation's own", id));
            case OUT_OF_RANGE -> out.add(Finding.error(DOMAIN, TIER_OUT_OF_RANGE, where + " gives the rank '"
                    + tier.id() + "' a From of " + tier.from() + ", which is not above the top rank's floor and "
                    + "below its ceiling" + (top == null ? "" : " (" + top.min() + " to " + top.max() + ")")
                    + ", so the rank is left off this reputation's ladder", id));
            case SHARED_FLOOR -> out.add(Finding.error(DOMAIN, TIER_SHARES_FLOOR, where + " gives the rank '"
                    + tier.id() + "' the same From (" + tier.from() + ") as another of its ranks, so one of them "
                    + "could never be reached; '" + tier.id() + "' is left off this reputation's ladder", id));
            case SHARED_ID -> out.add(Finding.error(DOMAIN, TIER_SHARES_ID, where + " writes its rank '" + tier.id()
                    + "' (From " + tier.from() + ") a second time in another case; ids match without regard to "
                    + "case, so only the first by floor stands and this one is left off this reputation's ladder",
                    id));
        }
    }

    private static void auditKill(@Nonnull String where, @Nonnull String id, int index,
            @Nonnull ReputationAsset.Kill kill, @Nonnull Predicate<String> npcGroupKnown, @Nonnull List<Finding> out) {
        if (kill.npcGroups().isEmpty()) {
            out.add(Finding.error(DOMAIN, KILL_WITHOUT_GROUPS, where + " Kills row " + index
                    + " lists no NPCGroups, so it never counts a kill", id));
            return;
        }
        for (String group : kill.npcGroups()) {
            if (!npcGroupKnown.test(group)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_KILL_GROUP, where + " Kills row " + index
                        + " names the NPC group '" + group + "', which is not loaded, so it counts nothing", id));
            }
        }
    }
}
