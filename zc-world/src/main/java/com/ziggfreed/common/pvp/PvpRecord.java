package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's PvP record, saved on the player: the kills, assists and deaths the ledger credited ({@link PvpLedger}),
 * the streak of credited kills since their last death (what a bounty stands on, so it survives a logout), the best
 * streak and the bounties they ended. Its own component under its own save key, so a server moved back to a CommonLib
 * that does not know it keeps the data untouched (the engine holds an unknown component's data and writes it back) and
 * a consumer keeping players in a database saves it as one record ({@link PlayerRecords#changed}, named
 * {@value #REGISTRY_ID}). The lifetime numbers are read by the player stat store as native stats ({@code Pvp_Kills} and
 * the rest), never counted a second time. Append a field, never rename one.
 *
 * <p>World thread: written only by the ledger on the player's own world thread; a consumer reads it through
 * {@link #of}. Registered at library setup, BEFORE any world loads, and attached to every player on connect.
 */
public final class PvpRecord implements Component<EntityStore> {

    /** The engine registry id; the persisted save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Pvp";

    /** The save's {@code Version} leaf. */
    public static final int VERSION = 1;

    @Nullable
    private static volatile ComponentType<EntityStore, PvpRecord> type;

    public static final BuilderCodec<PvpRecord> CODEC;

    private int kills;
    private int assists;
    private int deaths;
    private int streak;
    private int bestStreak;
    private int bounties;

    static {
        var builder = BuilderCodec.builder(PvpRecord.class, PvpRecord::new);
        builder.versioned().codecVersion(VERSION);
        builder.append(new KeyedCodec<>("Kills", Codec.INTEGER, false),
                (c, v) -> c.kills = count(v), c -> c.kills).add();
        builder.append(new KeyedCodec<>("Assists", Codec.INTEGER, false),
                (c, v) -> c.assists = count(v), c -> c.assists).add();
        builder.append(new KeyedCodec<>("Deaths", Codec.INTEGER, false),
                (c, v) -> c.deaths = count(v), c -> c.deaths).add();
        builder.append(new KeyedCodec<>("Streak", Codec.INTEGER, false),
                (c, v) -> c.streak = count(v), c -> c.streak).add();
        builder.append(new KeyedCodec<>("BestStreak", Codec.INTEGER, false),
                (c, v) -> c.bestStreak = count(v), c -> c.bestStreak).add();
        builder.append(new KeyedCodec<>("Bounties", Codec.INTEGER, false),
                (c, v) -> c.bounties = count(v), c -> c.bounties).add();
        CODEC = builder.build();
    }

    public PvpRecord() {
    }

    /** Kills of other players credited to this player. */
    public int kills() {
        return kills;
    }

    /** Kills of other players this player helped with and did not land. */
    public int assists() {
        return assists;
    }

    /** Deaths of this player another player was credited with. */
    public int deaths() {
        return deaths;
    }

    /** Credited kills since this player last died. */
    public int streak() {
        return streak;
    }

    /** The longest streak this player has had. */
    public int bestStreak() {
        return bestStreak;
    }

    /** Bounties this player ended. */
    public int bounties() {
        return bounties;
    }

    /** One more credited kill: the streak grows, and the best streak with it. */
    void addKill() {
        kills = bump(kills);
        streak = bump(streak);
        bestStreak = Math.max(bestStreak, streak);
    }

    void addAssist() {
        assists = bump(assists);
    }

    void addDeath() {
        deaths = bump(deaths);
    }

    void addBounty() {
        bounties = bump(bounties);
    }

    /** The streak ends (a death, or leaving mid-fight). */
    void endStreak() {
        streak = 0;
    }

    private static int bump(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static int count(@Nullable Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    /** The registered type, or null when registration failed (every read then answers none). */
    @Nullable
    public static ComponentType<EntityStore, PvpRecord> getComponentType() {
        return type;
    }

    /** The record of the player at {@code ref}, or null when it has none or it cannot be read. World thread. */
    @Nullable
    public static PvpRecord of(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        ComponentType<EntityStore, PvpRecord> held = type;
        if (held == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            return accessor.getComponent(ref, held);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Register the type with the entity-store registry. Once, at library setup; never throws. */
    @Nullable
    public static ComponentType<EntityStore, PvpRecord> register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            type = registry.registerComponent(PvpRecord.class, REGISTRY_ID, CODEC);
            return type;
        } catch (Throwable t) {
            SafeLog.warn("[pvp] could not register the PvP record: kills, streaks and bounties are not kept", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, PvpRecord::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, PvpRecord> held = type;
            if (held != null) {
                event.getHolder().ensureAndGetComponent(held);
            }
        } catch (Throwable t) {
            SafeLog.warn("[pvp] could not attach the PvP record", t);
        }
    }

    @Override
    public PvpRecord clone() {
        PvpRecord copy = new PvpRecord();
        copy.kills = kills;
        copy.assists = assists;
        copy.deaths = deaths;
        copy.streak = streak;
        copy.bestStreak = bestStreak;
        copy.bounties = bounties;
        return copy;
    }
}
