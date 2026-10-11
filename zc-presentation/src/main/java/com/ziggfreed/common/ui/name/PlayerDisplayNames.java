package com.ziggfreed.common.ui.name;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * caller stored, else the first eight characters of the id, then WRAPPED by whatever higher modules
 * contributed (titles contribute: "Ziggfreed the Bold").
 *
 * <p><b>Contributions.</b> A module {@link #contribute}s one {@link Contribution} under its own owner
 * id with an order: each wraps the name built so far, the lowest order first (innermost), equal orders
 * by owner id, so the answer is the same on every boot. The default slot ({@link #fillDecorator})
 * sits at {@link #DECORATOR_ORDER}, ahead of any contribution of the same order; it is asked with the
 * plain name, as it always was, and its answer replaces the name built so far, so a contribution that
 * must wrap a decorator's answer takes an order above it.
 *
 * <p><b>Where it goes.</b> Paint the answer on a Label's {@code .TextSpans}: a wrapped name is a
 * parameterized {@link Message}, which a {@code .Text} sink prints with its {@code {0}} showing.
 *
 * <p><b>Threads.</b> A page builds on the VIEWER's world thread and names players who may stand in
 * other worlds, so a contribution reads process-wide state only, never an entity store. Each one is
 * guarded on its own: one that throws is skipped, so the row keeps its name and every other wrap, and
 * warns once until its owner contributes again.
 *
 * <p><b>Scope.</b> Menus and leaderboards always. A chat line goes through {@link #displayName} only
 * where a module formats chat that way (zc-objectives' title chat, off unless a server owner switches
 * it on), and then the whole name shows there, every contribution included. A notice names a player
 * plainly: {@link #plainName} is that plain name, never wrapped. The seam ships filled: with nothing
 * contributed, every name is its plain text.
 *
 * <p><b>Presence.</b> A contribution answers the same whether the player is online or not, and a row
 * names every player through {@link #displayName}, online or not, so a wrap never tells a viewer who
 * is online.
 */
public final class PlayerDisplayNames {

    /** How many characters of a player's id stand in for a name nothing else can give. */
    static final int ID_PREFIX = 8;

    /** The order the default slot ({@link #fillDecorator}) wraps at. */
    public static final int DECORATOR_ORDER = 0;

    /** What a decorated name is, given who the player is and the plain name a row would show. */
    @FunctionalInterface
    public interface Decorator {

        /** The decorated name, or null to show the plain one. Any thread; never touches an entity store. */
        @Nullable
        Message decorate(@Nonnull UUID playerId, @Nonnull String plainName);
    }

    /** One module's wrap of a player's name. */
    @FunctionalInterface
    public interface Contribution {

        /**
         * {@code name} (the name built so far) wrapped, or null to leave it as it is. Any thread; never
         * touches an entity store.
         */
        @Nullable
        Message wrap(@Nonnull UUID playerId, @Nonnull Message name);
    }

    /** Where a live username comes from; the default asks the universe. */
    @FunctionalInterface
    public interface LiveNames {

        /** The player's username while online, else null. */
        @Nullable
        String usernameOf(@Nonnull UUID playerId);
    }

    /** What a slot runs: a contribution sees the name built so far, the default slot the plain name. */
    @FunctionalInterface
    private interface Step {

        @Nullable
        Message apply(@Nonnull UUID playerId, @Nonnull String plainName, @Nonnull Message soFar);
    }

    /** One filled slot. {@code warned} is its own, so a fresh contribution warns afresh. */
    private record Slot(@Nonnull String owner, int order, boolean isDefault, @Nonnull Step step,
            @Nonnull AtomicBoolean warned) {

        Slot(@Nonnull String owner, int order, boolean isDefault, @Nonnull Step step) {
            this(owner, order, isDefault, step, new AtomicBoolean());
        }
    }

    private static final Comparator<Slot> WRAP_ORDER = Comparator.comparingInt(Slot::order)
            .thenComparing(slot -> !slot.isDefault())
            .thenComparing(Slot::owner);

    private static final LiveNames UNIVERSE = PlayerDisplayNames::universeUsername;

    private static final AtomicReference<LiveNames> LIVE_NAMES = new AtomicReference<>(UNIVERSE);

    private static final Object LOCK = new Object();

    /** The contributions by owner; written under {@link #LOCK}. */
    private static final Map<String, Slot> CONTRIBUTED = new LinkedHashMap<>();

    /** The default slot, or null; written under {@link #LOCK}. */
    @Nullable
    private static Slot defaultSlot;

    /** Every filled slot in wrap order, swapped whole on each change so a reader never sees half of one. */
    private static volatile List<Slot> slots = List.of();

    private PlayerDisplayNames() {
    }

    /**
     * Fill the default slot, at {@link #DECORATOR_ORDER}; null empties it. Called once from a module's
     * setup. The decorator is asked with the plain name and its answer replaces the name built so far.
     */
    public static void fillDecorator(@Nullable Decorator decorator) {
        synchronized (LOCK) {
            defaultSlot = decorator == null ? null
                    : new Slot("decorator", DECORATOR_ORDER, true,
                            (playerId, plainName, soFar) -> decorator.decorate(playerId, plainName));
            publish();
        }
    }

    /**
     * Wrap every name {@code owner}'s way, at {@code order} (lower wraps first); null withdraws it. A
     * second call under the same owner replaces the first. Called from a module's setup.
     */
    public static void contribute(@Nonnull String owner, int order, @Nullable Contribution contribution) {
        Objects.requireNonNull(owner, "owner");
        synchronized (LOCK) {
            if (contribution == null) {
                CONTRIBUTED.remove(owner);
            } else {
                CONTRIBUTED.put(owner, new Slot(owner, order, false,
                        (playerId, plainName, soFar) -> contribution.wrap(playerId, soFar)));
            }
            publish();
        }
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

    /** The name a menu paints, on a Label's {@code .TextSpans}: the plain name, wrapped by every slot in order. */
    @Nonnull
    public static Message displayName(@Nonnull UUID playerId, @Nullable String storedName) {
        String plain = plainName(playerId, storedName);
        Message name = Msg.raw(plain);
        for (Slot slot : slots) {
            try {
                Message wrapped = slot.step().apply(playerId, plain, name);
                if (wrapped != null) {
                    name = wrapped;
                }
            } catch (Throwable t) {
                if (slot.warned().compareAndSet(false, true)) {
                    SafeLog.warn("[names] the display-name " + (slot.isDefault() ? "decorator" : "contribution '"
                            + slot.owner() + "'") + " failed, so names show without it: " + t.getMessage());
                }
            }
        }
        return name;
    }

    /** Rebuild the read-side list. Under {@link #LOCK}. */
    private static void publish() {
        List<Slot> next = new ArrayList<>(CONTRIBUTED.values());
        if (defaultSlot != null) {
            next.add(defaultSlot);
        }
        next.sort(WRAP_ORDER);
        slots = List.copyOf(next);
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
