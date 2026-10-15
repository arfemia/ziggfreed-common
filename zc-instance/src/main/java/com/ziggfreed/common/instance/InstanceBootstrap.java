package com.ziggfreed.common.instance;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.encounter.asset.EncounterOwnerLayers;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.instance.claims.ClaimNotices;
import com.ziggfreed.common.instance.claims.ClaimsDestinations;
import com.ziggfreed.common.instance.claims.SpoilsChest;
import com.ziggfreed.common.instance.claims.SpoilsChestSystems;
import com.ziggfreed.common.instance.claims.SpoilsChests;
import com.ziggfreed.common.instance.claims.ZigOpenSpoilsInteraction;
import com.ziggfreed.common.instance.claims.command.ZigClaimsCommand;
import com.ziggfreed.common.instance.dungeon.AreaMembers;
import com.ziggfreed.common.instance.dungeon.AreaSpawnProvider;
import com.ziggfreed.common.instance.dungeon.DungeonDestinations;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.dungeon.ZigEnterDungeonInteraction;
import com.ziggfreed.common.instance.dungeon.command.ZigDungeonCommand;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.RecordsDestinations;
import com.ziggfreed.common.instance.leaderboard.command.ZigLeaderboardCommand;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoard;
import com.ziggfreed.common.instance.leaderboard.statboard.StatBoardRecords;
import com.ziggfreed.common.instance.leaderboard.statboard.StatPeriodOwnerLayers;
import com.ziggfreed.common.instance.world.InstanceWorlds;
import com.ziggfreed.common.interaction.type.InteractionTypeSpec;
import com.ziggfreed.common.interaction.type.InteractionTypes;
import com.ziggfreed.common.loot.claim.ClaimAddedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.party.ZcParty;
import com.ziggfreed.common.party.command.ZigPartyCommand;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * The instance module's registration phases, each called as one line from the wiring root's
 * {@code setup()}. Registration only; every decision lives in the module behind it.
 */
public final class InstanceBootstrap {

    private InstanceBootstrap() {
    }

    /**
     * Open the frozen encounter leaderboard beside the library's owner files ({@code mods/ziggfreedcommon/},
     * the encounter owner files' folder), which nothing writes any more: the stat store imports its rows
     * once and keeps a fight's records on the stat board; and register {@code /zigleaderboard}, which opens
     * Records' Boss Fights for any player. Each guarded on its own.
     */
    public static void installEncounterLeaderboard(@Nonnull JavaPlugin plugin) {
        try {
            // Before 2.2.0 the board sat in the library's data folder; the first load that finds it
            // only there moves it across.
            EncounterLeaderboardListener.open(EncounterOwnerLayers.directory(), plugin.getDataDirectory());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the frozen encounter leaderboard could not be opened", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigLeaderboardCommand());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] /zigleaderboard could not be registered", t);
        }
    }

    /**
     * Open the stat board ({@code stats-board.json}) beside the library's owner files and put it on
     * Records. It shows nothing until the store that keeps the stats fills its source
     * ({@code StatBoard.setSource}), so until then Records shows no tab.
     */
    public static void installStatBoard() {
        try {
            StatBoard.install(StatPeriodOwnerLayers.directory());
            RecordsDestinations.setBoard(StatBoardRecords.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[statboard] the stat board could not be opened", t);
        }
    }

    /**
     * The Records destination (before any asset decodes) and its tab in the shared menu. Registration only.
     */
    public static void registerRecords() {
        try {
            RecordsDestinations.register();
            ZigMenu.fill(MenuSlot.RECORDS, RecordsDestinations.entry());
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the Records destination and menu tab could not be registered", t);
        }
    }

    /**
     * Claims: open the store of what players have earned and not taken, and the ledger of how often each
     * diminishing reward was earned this window, both in the library's data folder (each writes on every
     * change, so the stop has nothing to flush); the Claims destination (before any asset decodes) and its tab
     * in the shared menu; the toast when a Claim arrives, the notice at a player's first ready while Claims
     * wait, and its reset at disconnect; and {@code /zigclaims}. Each guarded on its own.
     */
    public static void installClaims(@Nonnull JavaPlugin plugin) {
        try {
            DiminishLedger.shared().init(plugin.getDataDirectory());
            ClaimStore.shared().init(plugin.getDataDirectory());
        } catch (Throwable t) {
            SafeLog.warn("[claims] the Claims store could not be opened", t);
        }
        try {
            ClaimsDestinations.register();
            ZigMenu.fill(MenuSlot.CLAIMS, ClaimsDestinations.entry());
        } catch (Throwable t) {
            SafeLog.warn("[claims] the Claims destination and menu tab could not be registered", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(ClaimAddedEvent.class, ClaimNotices::onAdded);
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ClaimNotices::onPlayerReady);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ClaimNotices::onPlayerDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[claims] the Claims notices could not be registered", t);
        }
        try {
            plugin.getCommandRegistry().registerCommand(new ZigClaimsCommand());
        } catch (Throwable t) {
            SafeLog.warn("[claims] /zigclaims could not be registered", t);
        }
    }

    /**
     * The spoils chest a won boss fight leaves where the boss fell ({@code SpoilsChests}): its saved mark, before any
     * world loads; the upkeep that takes a chest down, the sweep that removes one a restart left behind, and the
     * guard that keeps an open spoils window's items off the ground; the {@code ZigOpenSpoils} interaction Type a
     * chest's Use runs, beside {@code ZigEnterDungeon} and like it before any asset decodes (the shipped root
     * interaction names it); the ears for a boss defeat (where the chest will stand) and a new Claim (the first of a
     * fight's raises it); and the world-closing eviction. Each guarded on its own. Registration only.
     */
    public static void installSpoils(@Nonnull JavaPlugin plugin) {
        try {
            SpoilsChest.register(plugin.getEntityStoreRegistry());
        } catch (Throwable t) {
            SafeLog.warn("[spoils] the spoils chest's mark could not be registered", t);
        }
        try {
            SpoilsChestSystems.register(plugin.getEntityStoreRegistry());
        } catch (Throwable t) {
            SafeLog.warn("[spoils] the spoils chests' systems could not be registered", t);
        }
        try {
            InteractionTypes.registerAll(plugin, List.of(InteractionTypeSpec.of(ZigOpenSpoilsInteraction.TYPE_NAME,
                    ZigOpenSpoilsInteraction.class, ZigOpenSpoilsInteraction::codec)));
        } catch (Throwable t) {
            SafeLog.warn("[spoils] the ZigOpenSpoils interaction Type could not be registered", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(EncounterDefeatedEvent.class, SpoilsChests::onDefeated);
            plugin.getEventRegistry().registerGlobal(ClaimAddedEvent.class, SpoilsChests::onClaimAdded);
            WorldEvictors.registerEvictor(SpoilsChests::forgetWorld);
        } catch (Throwable t) {
            SafeLog.warn("[spoils] the spoils chests could not listen for boss defeats and new Claims", t);
        }
    }

    /**
     * The library's party command, {@code /zigparty}, over its one default party ({@code ZcParty}, built on first
     * use). Registration only.
     */
    public static void registerParty(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getCommandRegistry().registerCommand(new ZigPartyCommand());
        } catch (Throwable t) {
            SafeLog.warn("[party] /zigparty could not be registered", t);
        }
    }

    /**
     * The dungeons' admin command, {@code /zigdungeon} ({@code list}, {@code status}, {@code close},
     * {@code enter}); the area-aware spawn provider every dungeon copy carries, on the engine's spawn
     * provider codec so a copy's config saves and loads; and a player's two ways in, the {@code ZigEnterDungeon}
     * interaction Type and the {@code Dungeon} destination, both before any asset decodes (an item, block or
     * placement naming either fails to load otherwise); the runs' ear for a boss defeat, which may clear the
     * run of each participant's area; and the encounter module's run-tag seam, filled from the runs, so a fight
     * inside an area is filed under the area's run. Their store registers with the other framework stores.
     * Registration only.
     */
    public static void registerDungeons(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getCommandRegistry().registerCommand(new ZigDungeonCommand());
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] /zigdungeon could not be registered", t);
        }
        try {
            plugin.getCodecRegistry(ISpawnProvider.CODEC)
                    .register(AreaSpawnProvider.TYPE, AreaSpawnProvider.class, AreaSpawnProvider.CODEC);
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the dungeon areas' spawn provider could not be registered", t);
        }
        try {
            InteractionTypes.registerAll(plugin, List.of(InteractionTypeSpec.of(ZigEnterDungeonInteraction.TYPE_NAME,
                    ZigEnterDungeonInteraction.class, ZigEnterDungeonInteraction::codec)));
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the ZigEnterDungeon interaction Type could not be registered", t);
        }
        try {
            DungeonDestinations.register();
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the Dungeon destination could not be registered", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(EncounterDefeatedEvent.class, DungeonRuns::onEncounterDefeated);
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the dungeon runs could not listen for boss defeats", t);
        }
        try {
            EncounterSeams.fillRunTag(DungeonRuns.shared()::runOf);
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the encounter run-tag seam could not be filled, so a fight inside a dungeon is "
                    + "filed under no run", t);
        }
    }

    /**
     * Write the boards' rows still waiting on their debounce, so a stat counted in the last seconds
     * before a stop is kept, and stop the instance copies' remover thread, the dungeon areas' member ticker
     * and the default party's invite timers. Called from the library's {@code shutdown()}, which the server
     * runs after it has disconnected every player and shut every world down, so nothing is counted after it.
     * A stop with nothing new writes nothing, and it never throws.
     */
    public static void shutdown() {
        try {
            InstanceWorlds.shutdown();
        } catch (Throwable t) {
            SafeLog.warn("[instance] the copies' remover thread could not be stopped", t);
        }
        try {
            AreaMembers.shutdown();
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the area members' ticker could not be stopped", t);
        }
        try {
            EncounterLeaderboardListener.flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard could not be written at shutdown", t);
        }
        try {
            StatBoard.flushNow();
        } catch (Throwable t) {
            SafeLog.warn("[statboard] the stat board could not be written at shutdown", t);
        }
        try {
            ZcParty.shutdown();
        } catch (Throwable t) {
            SafeLog.warn("[party] the default party's invite timers could not be stopped", t);
        }
    }
}
