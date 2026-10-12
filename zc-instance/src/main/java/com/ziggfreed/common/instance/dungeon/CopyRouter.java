package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.world.CopyKey;

/**
 * Where a player entering a dungeon goes: which copy of it, and which area of that copy. Pure logic over
 * what the caller tells it, so it reads no clock and no engine: the time comes in as {@code nowMs} and every
 * question about a world goes through the caller's {@link WorldProbe} ({@link LiveWorlds} on a server).
 *
 * <p><b>The rules.</b>
 * <ul>
 *   <li>A party holds at most one area per dungeon, keyed by its party id ({@link #partyKey}); a player in no
 *       party is a party of one, keyed by the player. {@link #areaOf} answers it, and a member who enters
 *       after the others joins the area the party holds.</li>
 *   <li>An area holds {@code MaxPlayers} at most, counting the players on their way in; one more is
 *       refused ({@link Kind#FULL}).</li>
 *   <li>A party with no area takes a free area of a live copy before a new copy opens, which is named
 *       {@code <dungeon>-<n>}, {@code n} the lowest number no copy here holds and no live world carries. A
 *       dungeon whose areas are not party-locked first puts the party in an area that still has room.</li>
 *   <li>A copy whose recent tick length is over {@link #LAG_BUDGET_MS} takes no new party. The engine's
 *       average lags (its window is ten seconds), so parties reserved into a copy and not yet arrived count
 *       against it: each is taken to cost what an arrived party costs now.</li>
 *   <li>A player's place is reserved before the asynchronous teleport and settled when it lands
 *       ({@link #arrived}) or fails ({@link #failed}); an area every player has left frees, and the party
 *       holding it lets it go ({@link #left}).</li>
 *   <li>Nothing is reserved into a copy within one ticker period ({@link #TICKER_PERIOD_MS}) of its close:
 *       a copy closes once it has stood empty for its dungeon's {@code EmptyCloseSeconds}, and a copy an
 *       admin is closing ({@link #closing}) takes no one.</li>
 * </ul>
 *
 * <p>A copy the probe no longer finds live, and that no player is on the way into, is forgotten at the next
 * read, so a copy the engine closed needs no listener here. Every method is synchronized.
 */
public final class CopyRouter {

    /** A copy whose recent average tick runs over this many milliseconds takes no new party (lead's call). */
    public static final double LAG_BUDGET_MS = 25.0;

    /**
     * The period of the dungeon ticker, in milliseconds: nothing is reserved into a copy closer than this to
     * its close, since the ticker may not see the arrival before the engine removes the world.
     */
    public static final long TICKER_PERIOD_MS = 1_000L;

    private static final CopyRouter SHARED = new CopyRouter();

    /** The library's one router. */
    @Nonnull
    public static CopyRouter shared() {
        return SHARED;
    }

    /** What the router needs to know of a dungeon. Ids are lower-cased; the counts are at least one. */
    public record Rules(@Nonnull String dungeon, int areasPerCopy, int maxPlayers, boolean partyLocked,
            long emptyCloseMs) {

        public Rules {
            Objects.requireNonNull(dungeon, "dungeon");
            dungeon = dungeon.trim().toLowerCase(Locale.ROOT);
            if (dungeon.isEmpty()) {
                throw new IllegalArgumentException("a dungeon's rules need its id");
            }
            areasPerCopy = Math.max(1, areasPerCopy);
            maxPlayers = Math.max(1, maxPlayers);
            emptyCloseMs = Math.max(0L, emptyCloseMs);
        }
    }

    /** What the router asks about a world, answered by the caller. */
    public interface WorldProbe {

        /** Is a world of this name loaded and running now? */
        boolean isLive(@Nonnull String world);

        /**
         * Does the server still hold a world of this name, running or on its way out? A stopping world stays
         * registered after it stops running, and its name cannot be spawned again until it is gone.
         */
        boolean isRegistered(@Nonnull String world);

        /** Its recent average tick length in milliseconds; NaN when it cannot be read. */
        double tickAverageMs(@Nonnull String world);
    }

    /** How a reservation went. */
    public enum Kind {
        /** The party already holds this area, or the player is already on the way into it. */
        HELD,
        /** An area of a copy that is open already. */
        FILLED,
        /** Area 0 of a new copy: the caller spawns the world under the key's name before it sends the player. */
        OPENED,
        /** Refused: the party's area already holds {@code MaxPlayers}, counting those on the way. */
        FULL
    }

    /** A reservation's answer: its kind, and the area reserved (null when refused). */
    public record Placement(@Nonnull Kind kind, @Nullable CopyKey area) {

        /** Was a place reserved? */
        public boolean placed() {
            return area != null;
        }

        /** Must the caller spawn the copy first? */
        public boolean opensCopy() {
            return kind == Kind.OPENED;
        }
    }

    /** One open copy as the router sees it, for {@code /zigdungeon status}. */
    public record CopyView(@Nonnull String world, @Nonnull String dungeon, int number, int areas, int busyAreas,
            int present, int onTheWay, boolean closing, @Nullable Long closesAtMs) {
    }

    /** One area of a copy: the parties holding it, and who is in it or on the way. */
    private static final class Area {
        final Set<String> parties = new LinkedHashSet<>();
        final Set<UUID> onTheWay = new HashSet<>();
        final Set<UUID> present = new HashSet<>();

        int players() {
            return onTheWay.size() + present.size();
        }

        boolean free() {
            return players() == 0;
        }
    }

    /** One copy of a dungeon. */
    private static final class Copy {
        final String world;
        final String dungeon;
        final int number;
        final long emptyCloseMs;
        final Area[] areas;
        boolean closing;
        /** When the last player left it, or null while anyone is in it or on the way. */
        @Nullable Long emptySinceMs;

        Copy(String world, String dungeon, int number, int areaCount, long emptyCloseMs) {
            this.world = world;
            this.dungeon = dungeon;
            this.number = number;
            this.emptyCloseMs = emptyCloseMs;
            this.areas = new Area[areaCount];
            for (int i = 0; i < areaCount; i++) {
                areas[i] = new Area();
            }
        }

        boolean anyoneOnTheWay() {
            for (Area area : areas) {
                if (!area.onTheWay.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        boolean empty() {
            for (Area area : areas) {
                if (!area.free()) {
                    return false;
                }
            }
            return true;
        }

        @Nullable
        Long closesAtMs() {
            return emptySinceMs == null ? null : emptySinceMs + emptyCloseMs;
        }
    }

    /** Every copy by its lower-cased world name, in the order they opened. */
    private final Map<String, Copy> copies = new LinkedHashMap<>();
    /** Each dungeon's held areas: party key to area. */
    private final Map<String, Map<String, CopyKey>> held = new HashMap<>();
    /** Each player on the way into an area. */
    private final Map<UUID, CopyKey> onTheWay = new HashMap<>();
    /** Each player who arrived in an area and has not left it. */
    private final Map<UUID, CopyKey> present = new HashMap<>();

    public CopyRouter() {
    }

    /** The key a party is filed under: its party id, or for a player in no party, the player. */
    @Nonnull
    public static String partyKey(@Nullable String partyId, @Nonnull UUID player) {
        return partyId == null || partyId.isBlank() ? "player:" + player : "party:" + partyId.trim();
    }

    /** The world name of copy {@code number} of {@code dungeon}: {@code <dungeon>-<n>}, lower-cased. */
    @Nonnull
    public static String copyName(@Nonnull String dungeon, int number) {
        return dungeon.trim().toLowerCase(Locale.ROOT) + "-" + number;
    }

    /** The dungeon id a copy's world name carries ({@code crypt} for {@code crypt-2}); null when it is no copy name. */
    @Nullable
    public static String dungeonOf(@Nullable String world) {
        if (world == null) {
            return null;
        }
        String name = world.trim().toLowerCase(Locale.ROOT);
        int dash = name.lastIndexOf('-');
        if (dash <= 0 || dash == name.length() - 1) {
            return null;
        }
        for (int i = dash + 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return null;
            }
        }
        return name.substring(0, dash);
    }

    /** The area {@code partyKey} holds in {@code dungeon}, or null when it holds none. */
    @Nullable
    public synchronized CopyKey areaOf(@Nonnull String partyKey, @Nonnull String dungeon) {
        Map<String, CopyKey> parties = held.get(dungeon.trim().toLowerCase(Locale.ROOT));
        return parties == null ? null : parties.get(partyKey);
    }

    /**
     * Reserve a place for {@code player}, of party {@code partyKey}, in {@code rules}' dungeon. Call it before
     * the asynchronous teleport; then {@link #arrived} when the player lands, or {@link #failed} when the
     * teleport (or the spawn of an {@link Kind#OPENED} copy) does not.
     */
    @Nonnull
    public synchronized Placement reserve(@Nonnull String partyKey, @Nonnull UUID player, @Nonnull Rules rules,
            long nowMs, @Nonnull WorldProbe probe) {
        prune(probe);
        String dungeon = rules.dungeon();

        CopyKey mine = onTheWay.get(player);
        if (mine == null) {
            mine = present.get(player);
        }
        if (mine != null) {
            Copy copy = copies.get(mine.world());
            if (copy != null && copy.dungeon.equals(dungeon) && !copy.closing) {
                return new Placement(Kind.HELD, mine);
            }
            drop(player, nowMs);
        }

        CopyKey holding = areaOf(partyKey, dungeon);
        if (holding != null) {
            Copy copy = copies.get(holding.world());
            if (copy != null && !copy.closing) {
                Area area = copy.areas[holding.area()];
                if (area.players() >= rules.maxPlayers()) {
                    return new Placement(Kind.FULL, null);
                }
                claim(copy, holding.area(), partyKey, player);
                return new Placement(Kind.HELD, holding);
            }
            unhold(dungeon, partyKey);
        }

        for (Copy copy : copies.values()) {
            if (!copy.dungeon.equals(dungeon) || !takesParties(copy, nowMs, probe)) {
                continue;
            }
            int index = rules.partyLocked() ? -1 : areaWithRoom(copy, rules.maxPlayers());
            if (index < 0) {
                index = freeArea(copy);
            }
            if (index >= 0) {
                return new Placement(Kind.FILLED, claim(copy, index, partyKey, player));
            }
        }

        int number = nextNumber(dungeon, probe);
        Copy copy = new Copy(copyName(dungeon, number), dungeon, number, rules.areasPerCopy(), rules.emptyCloseMs());
        copies.put(copy.world, copy);
        return new Placement(Kind.OPENED, claim(copy, 0, partyKey, player));
    }

    /** {@code player} landed in the area reserved for them. False when no reservation of theirs is open. */
    public synchronized boolean arrived(@Nonnull UUID player) {
        CopyKey key = onTheWay.remove(player);
        Copy copy = key == null ? null : copies.get(key.world());
        if (copy == null) {
            return false;
        }
        Area area = copy.areas[key.area()];
        area.onTheWay.remove(player);
        area.present.add(player);
        present.put(player, key);
        return true;
    }

    /** {@code player}'s teleport, or the spawn of the copy they were going to, failed: release their place. */
    public synchronized void failed(@Nonnull UUID player, long nowMs) {
        CopyKey key = onTheWay.remove(player);
        Copy copy = key == null ? null : copies.get(key.world());
        if (copy == null) {
            return;
        }
        copy.areas[key.area()].onTheWay.remove(player);
        settle(copy, key, nowMs);
    }

    /** {@code player} left their area (or the copy, or the server): an area every player has left frees. */
    public synchronized void left(@Nonnull UUID player, long nowMs) {
        drop(player, nowMs);
    }

    /** An admin is closing {@code world}: nothing is reserved into it again. False when it is no copy here. */
    public synchronized boolean closing(@Nonnull String world) {
        Copy copy = copies.get(world.trim().toLowerCase(Locale.ROOT));
        if (copy == null) {
            return false;
        }
        copy.closing = true;
        return true;
    }

    /** {@code world} is gone: forget the copy, its areas and every place in it. */
    public synchronized void closed(@Nonnull String world) {
        forget(world.trim().toLowerCase(Locale.ROOT));
    }

    /** Every copy this router holds, in the order they opened, after forgetting the ones no longer live. */
    @Nonnull
    public synchronized List<CopyView> copies(@Nonnull WorldProbe probe) {
        prune(probe);
        List<CopyView> out = new ArrayList<>();
        for (Copy copy : copies.values()) {
            int busy = 0;
            int in = 0;
            int coming = 0;
            for (Area area : copy.areas) {
                if (!area.free()) {
                    busy++;
                }
                in += area.present.size();
                coming += area.onTheWay.size();
            }
            out.add(new CopyView(copy.world, copy.dungeon, copy.number, copy.areas.length, busy, in, coming,
                    copy.closing, copy.closesAtMs()));
        }
        return out;
    }

    // ==================== internals ====================

    /** Can {@code copy} take a new party now: live, not closing, not about to close, and not lagging? */
    private static boolean takesParties(@Nonnull Copy copy, long nowMs, @Nonnull WorldProbe probe) {
        if (copy.closing || !probe.isLive(copy.world)) {
            return false;
        }
        Long closesAt = copy.closesAtMs();
        if (closesAt != null && nowMs >= closesAt - TICKER_PERIOD_MS) {
            return false;
        }
        return !lagging(copy, probe.tickAverageMs(copy.world));
    }

    /**
     * Is {@code copy} over the tick budget once the parties on their way in count? Each area someone is on the
     * way into, and no one has reached yet, is taken to cost what an occupied area costs now: the average
     * scaled by (occupied + reserved) / occupied. With no area occupied there is nothing to scale by, and the
     * average stands alone. An average that cannot be read never lags.
     */
    private static boolean lagging(@Nonnull Copy copy, double averageMs) {
        if (!Double.isFinite(averageMs)) {
            return false;
        }
        int occupied = 0;
        int reserved = 0;
        for (Area area : copy.areas) {
            if (!area.present.isEmpty()) {
                occupied++;
            } else if (!area.onTheWay.isEmpty()) {
                reserved++;
            }
        }
        double projected = occupied == 0 ? averageMs : averageMs * (occupied + reserved) / occupied;
        return projected > LAG_BUDGET_MS;
    }

    /** The lowest free area of {@code copy}, or -1 when every area is busy. */
    private static int freeArea(@Nonnull Copy copy) {
        for (int i = 0; i < copy.areas.length; i++) {
            if (copy.areas[i].free()) {
                return i;
            }
        }
        return -1;
    }

    /** The lowest busy area of {@code copy} with room for one more player, or -1 when none has. */
    private static int areaWithRoom(@Nonnull Copy copy, int maxPlayers) {
        for (int i = 0; i < copy.areas.length; i++) {
            Area area = copy.areas[i];
            if (!area.free() && area.players() < maxPlayers) {
                return i;
            }
        }
        return -1;
    }

    /** The lowest copy number of {@code dungeon} no copy here holds and no world the server holds carries. */
    private int nextNumber(@Nonnull String dungeon, @Nonnull WorldProbe probe) {
        int number = 1;
        while (copies.containsKey(copyName(dungeon, number)) || probe.isRegistered(copyName(dungeon, number))) {
            number++;
        }
        return number;
    }

    /** Put {@code player} on the way into area {@code index} of {@code copy}, held by {@code partyKey}. */
    @Nonnull
    private CopyKey claim(@Nonnull Copy copy, int index, @Nonnull String partyKey, @Nonnull UUID player) {
        CopyKey key = new CopyKey(copy.world, index);
        Area area = copy.areas[index];
        area.parties.add(partyKey);
        area.onTheWay.add(player);
        onTheWay.put(player, key);
        held.computeIfAbsent(copy.dungeon, d -> new HashMap<>()).put(partyKey, key);
        copy.emptySinceMs = null;
        return key;
    }

    /** Take {@code player} out of whatever area they are in or on the way into. */
    private void drop(@Nonnull UUID player, long nowMs) {
        CopyKey key = present.remove(player);
        if (key == null) {
            key = onTheWay.remove(player);
        }
        Copy copy = key == null ? null : copies.get(key.world());
        if (copy == null) {
            return;
        }
        Area area = copy.areas[key.area()];
        area.present.remove(player);
        area.onTheWay.remove(player);
        settle(copy, key, nowMs);
    }

    /** After someone left {@code key}'s area: free it once it is empty, and mark the copy empty once all are. */
    private void settle(@Nonnull Copy copy, @Nonnull CopyKey key, long nowMs) {
        Area area = copy.areas[key.area()];
        if (!area.free()) {
            return;
        }
        Map<String, CopyKey> parties = held.get(copy.dungeon);
        for (String party : area.parties) {
            if (parties != null) {
                parties.remove(party, key);
            }
        }
        area.parties.clear();
        if (copy.empty()) {
            copy.emptySinceMs = nowMs;
        }
    }

    private void unhold(@Nonnull String dungeon, @Nonnull String partyKey) {
        Map<String, CopyKey> parties = held.get(dungeon);
        if (parties != null) {
            parties.remove(partyKey);
        }
    }

    /**
     * Forget every copy the server no longer holds that no player is on the way into. A stopping copy is kept
     * (it takes no party, not being live) until its name is free again.
     */
    private void prune(@Nonnull WorldProbe probe) {
        List<String> gone = new ArrayList<>();
        for (Copy copy : copies.values()) {
            if (!copy.anyoneOnTheWay() && !probe.isRegistered(copy.world)) {
                gone.add(copy.world);
            }
        }
        for (String world : gone) {
            forget(world);
        }
    }

    private void forget(@Nonnull String world) {
        Copy copy = copies.remove(world);
        if (copy == null) {
            return;
        }
        for (Area area : copy.areas) {
            for (UUID player : area.present) {
                present.remove(player);
            }
            for (UUID player : area.onTheWay) {
                onTheWay.remove(player);
            }
        }
        Map<String, CopyKey> parties = held.get(copy.dungeon);
        if (parties != null) {
            parties.values().removeIf(key -> key.world().equals(world));
        }
    }
}
