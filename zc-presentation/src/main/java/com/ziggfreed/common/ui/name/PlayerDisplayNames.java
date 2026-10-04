package com.ziggfreed.common.ui.name;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ONE way a menu or a leaderboard row names a player: the live username, else the name the
 * caller stored, else the first eight characters of the id, then DECORATED by whatever a higher
 * module filled (titles fill it: "Ziggfreed the Bold").
 *
 * <p><b>Where it goes.</b> Paint the answer on a Label's {@code .TextSpans}: a decorated name is a
 * parameterized {@link Message}, which a {@code .Text} sink prints with its {@code {0}} showing.
 *
 * <p><b>Threads.</b> A page builds on the VIEWER's world thread and names players who may stand in
 * other worlds, so a decorator reads process-wide state only, never an entity store. A decorator
 * that throws costs the row its decoration, never its name, and warns once until filled again.
 *
 * <p><b>Scope.</b> Menus and leaderboards only: a chat line or a notice names a player plainly and
 * never calls this. The seam ships filled: unfilled, every name is its plain text.
 */
public final class PlayerDisplayNames {

    /** How many characters of a player's id stand in for a name nothing else can give. */
    static final int ID_PREFIX = 8;

    /** What a decorated name is, given who the player is and the plain name a row would show. */
    @FunctionalInterface
    public interface Decorator {

        /** The decorated name, or null to show the plain one. Any thread; never touches an entity store. */
        @Nullable
        Message decorate(@Nonnull UUID playerId, @Nonnull String plainName);
    }

    /** Where a live username comes from; the default asks the universe. */
    @FunctionalInterface
    public interface LiveNames {

        /** The player's username while online, else null. */
        @Nullable
        String usernameOf(@Nonnull UUID playerId);
    }

    private static final LiveNames UNIVERSE = PlayerDisplayNames::universeUsername;

    private static final AtomicReference<Decorator> DECORATOR = new AtomicReference<>();

    private static final AtomicReference<LiveNames> LIVE_NAMES = new AtomicReference<>(UNIVERSE);

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private PlayerDisplayNames() {
    }

    /** Fill the decorator; null goes back to plain names. Called once from a module's setup. */
    public static void fillDecorator(@Nullable Decorator decorator) {
        DECORATOR.set(decorator);
        WARNED.set(false);
    }

    /** Replace the live-name lookup; null restores the universe. For a host outside a server and for a test. */
    public static void fillLiveNames(@Nullable LiveNames names) {
        LIVE_NAMES.set(names != null ? names : UNIVERSE);
    }

    /** The plain name: the live username, else {@code storedName}, else the start of the id. */
    @Nonnull
    public static String plainName(@Nonnull UUID playerId, @Nullable String storedName) {
        String live = live(playerId);
        if (live != null) {
            return live;
        }
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        String id = playerId.toString();
        return id.substring(0, Math.min(ID_PREFIX, id.length()));
    }

    /** The name a menu paints, on a Label's {@code .TextSpans}: decorated when the decorator says so. */
    @Nonnull
    public static Message displayName(@Nonnull UUID playerId, @Nullable String storedName) {
        String plain = plainName(playerId, storedName);
        Decorator decorator = DECORATOR.get();
        if (decorator == null) {
            return Msg.raw(plain);
        }
        try {
            Message decorated = decorator.decorate(playerId, plain);
            return decorated != null ? decorated : Msg.raw(plain);
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[names] the display-name decorator failed, so plain names show: " + t.getMessage());
            }
            return Msg.raw(plain);
        }
    }

    @Nullable
    private static String live(@Nonnull UUID playerId) {
        try {
            String name = LIVE_NAMES.get().usernameOf(playerId);
            return name == null || name.isBlank() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static String universeUsername(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerId);
        return player == null ? null : player.getUsername();
    }
}
