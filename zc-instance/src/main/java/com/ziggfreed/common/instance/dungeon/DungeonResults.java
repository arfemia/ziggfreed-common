package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.claims.ClaimNotices;
import com.ziggfreed.common.instance.claims.ClaimsView;
import com.ziggfreed.common.instance.claims.SpoilsChests;
import com.ziggfreed.common.instance.result.MatchResult;
import com.ziggfreed.common.instance.result.PlayerResultRow;
import com.ziggfreed.common.instance.result.ResultKind;
import com.ziggfreed.common.instance.result.ResultsPage;
import com.ziggfreed.common.instance.result.ResultsPageDeps;
import com.ziggfreed.common.instance.result.ScoreColumn;
import com.ziggfreed.common.instance.result.TeamResult;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a dungeon run's end shows its members, handed over by {@link DungeonRuns} once the run's lock is let go.
 *
 * <p><b>A clear.</b> Each member in the area at the clear earns the dungeon's completion Claim: its
 * {@code Rewards.Lootable} rolled once for them ({@link LootEngine#select}, as a won round with no score), kept as
 * rewards to hand over later ({@link DeferredRewards}), and written through {@link ClaimStore#award}, which counts
 * it against {@code Rewards.Diminish} (absent, every clear pays in full) and writes nothing a second time or for a
 * roll that landed nothing. The Claim's id is {@code dungeon:<id>:<run>:<player>}, its source {@code dungeon:<id>}
 * and its returns key {@code dungeon:<id>:<difficulty>}. Then the member's results screen opens, a normal
 * {@link ResultsPage} built from the library's own {@link DungeonResultsMessages} and a
 * {@link DungeonResultsActions} holding their Claim: its Claim button hands the Claim over through
 * {@code ClaimGrants} (collected, never granted unasked), and whatever they leave, or a full bag keeps, waits on
 * the Claims tab. Since the screen offers that Claim itself, the new-Claim toast leaves it out
 * ({@link ClaimNotices#offeredOnPage}). A member who is offline or dead at the clear gets no screen (the respawn
 * screen is a page of its own, which a results page would replace): their Claim waits on the tab all the same, and
 * the toast names it.
 *
 * <p><b>Into the chest.</b> With {@code Rewards.InChest}, a run cleared by a boss fight whose chest holds a share for
 * a member ({@link SpoilsChests#takes}) files that member's Claim under the fight, so the chest offers it with the
 * fight's own (and stands for it alone on a later clear the fight paid nothing for); no results screen opens, and the
 * member sees the chest's banner instead ({@link SpoilsChests#tellInChest}): the dungeon's name cleared over its
 * {@code Text.ChestKey} line. A member the chest holds no share for, or whose roll wrote nothing, sees the results
 * screen as before.
 *
 * <p><b>A failure</b> the party could have prevented, its time limit or a wipe under {@code FailOnWipe}, tells each
 * member in the area why, in a toast where they are looking.
 *
 * <p>Both run wherever the run's queue is drained (the copy's world thread, as a rule) and hop to each player's
 * own world for anything that touches them. Neither throws.
 */
public final class DungeonResults {

    /** The key family the lines resolve under: the shipped file's name, minus {@code .lang}. */
    static final String PREFIX = DungeonRefusals.PREFIX;

    private static final String LOG = "[dungeon] ";

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    /** Loot tables a dungeon names that no loaded file defines, already logged. */
    private static final Set<String> WARNED_TABLES = ConcurrentHashMap.newKeySet();

    /**
     * A cleared run, as its payout needs it.
     *
     * @param dungeonId      the dungeon's id, as its file names it
     * @param runId          which run
     * @param difficulty     its {@code Difficulty} label, empty for normal
     * @param nameKey        its {@code Text.NameKey}, or null
     * @param lootable       its {@code Rewards.Lootable}, or null for no reward
     * @param diminish       its {@code Rewards.Diminish}, or null for every clear in full
     * @param seconds        how long the run lasted
     * @param winners        every member in the area at the clear
     * @param damage         the damage each member dealt the bosses that fell during the run
     * @param bossesDefeated how many boss fights were won in the area during the run
     * @param chestFight     with {@code Rewards.InChest}, the boss fight whose fall cleared the run, whose chest takes
     *                       each member's Claim it holds a share for; null for the results screen alone
     * @param chestKey       its {@code Text.ChestKey}, or null for the library's line
     */
    record Clear(@Nonnull String dungeonId, @Nonnull String runId, @Nonnull String difficulty,
                 @Nullable String nameKey, @Nullable String lootable, @Nullable DiminishSpec diminish, long seconds,
                 @Nonnull List<UUID> winners, @Nonnull Map<UUID, Long> damage, int bossesDefeated,
                 @Nullable String chestFight, @Nullable String chestKey) {
    }

    /**
     * A failed run whose members are told why.
     *
     * @param lineKey the line's key under {@link #PREFIX}, its {@code {0}} the dungeon's name
     * @param members every member in the area when it failed
     */
    record Failure(@Nonnull String dungeonId, @Nullable String nameKey, @Nonnull String lineKey,
                   @Nonnull List<UUID> members) {
    }

    private DungeonResults() {
    }

    // ==================== a clear ====================

    /** Pay each winner of {@code clear} their Claim and open their results screen. Never throws. */
    static void cleared(@Nonnull Clear clear) {
        try {
            LootableAsset table = table(clear);
            long now = System.currentTimeMillis();
            int paid = 0;
            for (UUID player : clear.winners()) {
                Claim written = null;
                String fight = SpoilsChests.takes(clear.chestFight(), player) ? clear.chestFight() : null;
                try {
                    written = award(clear, table, player, now, fight);
                    if (written != null) {
                        paid++;
                    }
                } catch (Throwable t) {
                    SafeLog.warn(LOG + "the completion Claim of " + clear.dungeonId() + " could not be written for "
                            + player, t);
                }
                if (fight != null && written != null) {
                    tellInChest(clear, player);
                } else {
                    show(clear, player, written);
                }
            }
            SafeLog.info(LOG + "the clear of " + clear.dungeonId() + " (run " + clear.runId() + ") wrote "
                    + paid + " of " + clear.winners().size() + " member(s) a Claim"
                    + (clear.lootable() == null ? "; it names no Rewards.Lootable" : " from " + clear.lootable()));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the clear of " + clear.dungeonId() + " could not be paid or shown", t);
        }
    }

    /** The dungeon's loot table, or null when it names none or none of that id is loaded (logged once per id). */
    @Nullable
    private static LootableAsset table(@Nonnull Clear clear) {
        String id = clear.lootable();
        if (id == null) {
            return null;
        }
        LootableAsset table = LootableConfig.getInstance().resolve(id);
        if (table == null && WARNED_TABLES.add(id.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "the dungeon '" + clear.dungeonId() + "' pays its clears from the loot table '" + id
                    + "', which no loaded file defines, so its clears pay nothing");
        }
        return table;
    }

    /**
     * Roll {@code table} for {@code player} and award what it lands as their completion Claim, filed under
     * {@code fight} when a chest takes it (null: the Claims tab alone); the Claim as written (scaled by its returns),
     * or null when nothing was written.
     */
    @Nullable
    private static Claim award(@Nonnull Clear clear, @Nullable LootableAsset table, @Nonnull UUID player, long now,
            @Nullable String fight) {
        if (table == null) {
            return null;
        }
        String source = DungeonRuns.SOURCE_PREFIX + clear.dungeonId();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<LootEngine.Selected> decided = LootEngine.select(table.rollsOrEmpty(), table.poolOrEmpty(), null,
                LootFactors.lookupFor(0, true), random::nextDouble);
        List<InstanceReward> rewards = InstanceReward.merge(DeferredRewards.fromSelection(decided,
                RewardKinds.shared(), Subject.of(player, CLAIMER), source, message -> SafeLog.warn(LOG + message)));
        if (rewards.isEmpty()) {
            return null;
        }
        String claimId = claimId(clear.dungeonId(), clear.runId(), player);
        Claim claim = new Claim(claimId, source, clear.nameKey(), List.of(),
                clear.difficulty().isBlank() ? null : clear.difficulty(), rewards, now, fight);
        if (!ClaimStore.shared().award(player, claim, clear.diminish(),
                diminishKey(clear.dungeonId(), clear.difficulty()), now)) {
            return null;
        }
        for (Claim held : ClaimStore.shared().list(player)) {
            if (held.is(claimId)) {
                return held;
            }
        }
        return null;
    }

    /** {@code dungeon:<id>:<run>:<player>}: one completion Claim per run, per player. */
    @Nonnull
    static String claimId(@Nonnull String dungeonId, @Nonnull String runId, @Nonnull UUID player) {
        return DungeonRuns.SOURCE_PREFIX + dungeonId + ":" + runId + ":" + player;
    }

    /** {@code dungeon:<id>:<difficulty>}, the difficulty empty for normal: the count every run of it shares. */
    @Nonnull
    static String diminishKey(@Nonnull String dungeonId, @Nonnull String difficulty) {
        return DungeonRuns.SOURCE_PREFIX + dungeonId + ":" + difficulty.trim();
    }

    /**
     * Tell {@code player} their completion Claim went into the boss's chest: the chest's banner, the dungeon's name
     * cleared over its {@code Text.ChestKey} line. No results screen opens; the chest offers the Claim.
     */
    private static void tellInChest(@Nonnull Clear clear, @Nonnull UUID player) {
        Universe universe = Universe.get();
        PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
        if (playerRef != null) {
            SpoilsChests.tellInChest(playerRef,
                    Msg.key(PREFIX + "clear.banner", nameOf(clear.dungeonId(), clear.nameKey())), clear.chestKey());
        }
    }

    /**
     * Open {@code player}'s results screen on their own world thread: a normal page, never a preview. Nothing for a
     * player who is offline, or dead, whose respawn screen it would replace.
     *
     * <p>The page offers {@code written} with its own Claim button, so once it opens the Claim is marked offered
     * ({@link ClaimNotices#offeredOnPage}) and the new-Claim toast leaves it out. The order holds on the copy's world
     * thread, where a clear runs: {@link ClaimStore#award} raised the Claim's notice inline, which queued the toast
     * on the player's world (the copy), and the page opens here inline, before that task runs.
     */
    private static void show(@Nonnull Clear clear, @Nonnull UUID player, @Nullable Claim written) {
        try {
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
            World world = ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }
            MatchResult result = result(clear, player, written);
            String offered = written == null ? null : written.id();
            ResultsPageDeps deps = new ResultsPageDeps(DungeonResultsMessages.LIBRARY,
                    new DungeonResultsActions(offered));
            Runnable open = () -> open(playerRef, player, result, deps, offered);
            if (world.isInThread()) {
                open.run();
            } else {
                world.execute(open);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the results screen of " + clear.dungeonId() + " could not open for " + player, t);
        }
    }

    /**
     * On the player's world thread: open the page, unless they are dead or gone, and mark the Claim it offers
     * ({@code offered}, null for none) as offered on it.
     */
    private static void open(@Nonnull PlayerRef playerRef, @Nonnull UUID viewer, @Nonnull MatchResult result,
            @Nonnull ResultsPageDeps deps, @Nullable String offered) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid() || !ref.getStore().isInThread()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(ref, store, new ResultsPage(playerRef, result, deps));
                if (offered != null) {
                    ClaimNotices.offeredOnPage(viewer, offered);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a dungeon results screen could not open", t);
        }
    }

    /**
     * What {@code viewer}'s results screen shows: the run cleared, its length, every member by the damage they dealt
     * its bosses (the most marked when more than one dealt any), the viewer's own damage, the boss fights the party
     * won, and the viewer's Claim as chips. No leaderboard link: Records keeps a dungeon's counts.
     */
    @Nonnull
    static MatchResult result(@Nonnull Clear clear, @Nonnull UUID viewer, @Nullable Claim written) {
        List<UUID> members = new ArrayList<>(clear.winners());
        members.sort(Comparator.comparingLong((UUID member) -> clear.damage().getOrDefault(member, 0L)).reversed());
        UUID top = members.size() > 1 && clear.damage().getOrDefault(members.get(0), 0L) > 0 ? members.get(0) : null;
        long total = 0;
        List<PlayerResultRow> rows = new ArrayList<>();
        for (UUID member : members) {
            long dealt = clear.damage().getOrDefault(member, 0L);
            total += dealt;
            boolean isViewer = member.equals(viewer);
            List<ScoreColumn> columns = isViewer
                    ? List.of(ScoreColumn.of(DungeonResultsMessages.LIBRARY.damageLabel(), dealt)) : List.of();
            List<ScoreColumn> party = isViewer
                    ? List.of(ScoreColumn.of(DungeonResultsMessages.LIBRARY.bossesLabel(), clear.bossesDefeated()))
                    : List.of();
            rows.add(new PlayerResultRow(member, dealt, columns, party, isViewer, member.equals(top)));
        }
        List<RewardChip> chips = new ArrayList<>();
        if (written != null) {
            for (InstanceReward reward : written.rewards()) {
                chips.add(ClaimsView.chip(reward));
            }
        }
        return new MatchResult(ResultKind.WIN, (int) Math.min(Integer.MAX_VALUE, clear.seconds()),
                List.of(TeamResult.single(total, rows)), 0, chips, null);
    }

    // ==================== a failure ====================

    /** Tell each member of {@code failure} why their run failed, where they are looking. Never throws. */
    static void failed(@Nonnull Failure failure) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            Message line = Msg.key(PREFIX + failure.lineKey(), nameOf(failure.dungeonId(), failure.nameKey()));
            for (UUID member : failure.members()) {
                PlayerRef playerRef = universe.getPlayer(member);
                if (playerRef != null) {
                    ToastDelivery.deliverWhenSettled(playerRef, ToastSpec.of(ToastKind.WARNING, line), null);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the members of a failed run of " + failure.dungeonId() + " could not be told why", t);
        }
    }

    /** A dungeon's name as its players read it: {@code nameKey}, or its id when it names none. */
    @Nonnull
    static Message nameOf(@Nonnull String dungeonId, @Nullable String nameKey) {
        return nameKey == null || nameKey.isBlank() ? Msg.raw(dungeonId) : ContentKeys.tr(nameKey.trim());
    }
}
