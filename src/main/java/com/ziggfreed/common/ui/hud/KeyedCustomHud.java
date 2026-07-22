package com.ziggfreed.common.ui.hud;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Mod-agnostic base for a custom in-world HUD overlay. Owns the machinery every HUD
 * otherwise re-derives: a {@link HudPosition}-driven anchor on a single root element, a
 * live reposition push, a per-HUD update throttle, and the native register/lookup/
 * broadcast over Update 5's keyed {@code HudManager}. Lifted (config-free) from the MMO
 * Skill Tree's {@code ui/hud/MmoHud} so every consumer HUD reads ONE base instead of a
 * per-mod copy; a consumer's own HUD base (the MMO's {@code MmoHud}) may re-parent onto
 * this and add only its own conveniences, or a HUD may extend this directly.
 *
 * <p>A concrete HUD supplies only its identity + layout via {@link #rootSelector()},
 * {@link #panelWidth()}, {@link #panelHeight()}, {@link #configuredPosition()} (where
 * its server-wide position is stored), and {@link #updateIntervalMs()}; it then calls
 * {@link #applyConfiguredPosition} in {@code build()} and one of the throttle helpers
 * from its own push path. Adding a new HUD costs a {@code .ui} + a thin subclass - the
 * position plumbing here is reused, not copied.
 *
 * <p>The static {@link #get} / {@link #refreshPositionForAllOnline} are keyed (not
 * per-type) so one implementation serves every HUD; each subclass exposes a thin,
 * typed delegate for clean call sites.
 */
public abstract class KeyedCustomHud extends CustomUIHud {

    private final AtomicLong lastPushedMs = new AtomicLong(0L);

    protected KeyedCustomHud(@Nonnull PlayerRef playerRef, @Nonnull String key) {
        super(playerRef, key);
    }

    // ---------------------------------------------------------------------------------
    // Subclass contract
    // ---------------------------------------------------------------------------------

    /** Selector of the single root element whose {@code Anchor} carries the position (e.g. {@code "#MmoAbilityHudRow"}). Mod-prefix it so it cannot collide with a co-installed mod's HUD doc on the client's flat UI namespace. */
    @Nonnull
    protected abstract String rootSelector();

    /** Root element width in pixels (must match the {@code .ui}); fed to {@link HudPosition#toAnchor}. */
    protected abstract int panelWidth();

    /** Root element height in pixels (must match the {@code .ui}); fed to {@link HudPosition#toAnchor}. */
    protected abstract int panelHeight();

    /** The server-wide configured position for this HUD (each HUD owns where this is stored). */
    @Nonnull
    protected abstract HudPosition configuredPosition();

    /** Minimum gap between pushes for this HUD's throttle. */
    protected abstract long updateIntervalMs();

    /**
     * When true, the position anchor omits {@code Height} so the panel sizes to its CONTENT (the native
     * objective-HUD behavior; see {@link HudPosition#toAnchorContentHeight}). Default false: the panel is
     * a fixed {@link #panelHeight()} box. A consumer with a HUD that hugs only its live content overrides
     * this.
     */
    protected boolean usesContentHeight() {
        return false;
    }

    /** The anchor for this HUD: content-sized height when {@link #usesContentHeight()}, else fixed. */
    @Nonnull
    private Anchor anchorFor(@Nonnull HudPosition position) {
        return usesContentHeight()
                ? position.toAnchorContentHeight(panelWidth())
                : position.toAnchor(panelWidth(), panelHeight());
    }

    // ---------------------------------------------------------------------------------
    // Shared position handling
    // ---------------------------------------------------------------------------------

    /**
     * Apply the {@link #configuredPosition()} to the root anchor during {@code build()}.
     * Try-guarded: a HUD is non-essential to gameplay, so any failure falls back to the
     * {@code .ui} file's static anchor rather than risk the player connect path.
     */
    protected final void applyConfiguredPosition(@Nonnull UICommandBuilder cmd) {
        try {
            cmd.setObject(rootSelector() + ".Anchor", anchorFor(configuredPosition()));
        } catch (Throwable t) {
            warn(getKey() + ": failed to apply configured position, using default: " + t.getMessage());
        }
    }

    /** Re-anchor this HUD to {@code position} live (partial update, no reconnect). */
    public final void pushPositionUpdate(@Nonnull HudPosition position) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.setObject(rootSelector() + ".Anchor", anchorFor(position));
        update(false, cmd);
    }

    // ---------------------------------------------------------------------------------
    // Shared throttle (two strategies; a subclass uses whichever fits its push path)
    // ---------------------------------------------------------------------------------

    /** Check-only gate: true when the interval since the last push has elapsed. Pair with {@link #markPushed()}. */
    public final boolean dueForPush(long now) {
        return now - lastPushedMs.get() >= updateIntervalMs();
    }

    /** Stamp the throttle as pushed-now. Call from a push that {@link #dueForPush} gated. */
    protected final void markPushed() {
        lastPushedMs.set(System.currentTimeMillis());
    }

    /**
     * Atomic acquire for per-frame callers: returns true if the caller should push now,
     * stamping the throttle in the same step. {@code force} bypasses the interval (and
     * still stamps). The CAS makes concurrent ticks emit at most one push per window.
     */
    protected final boolean acquirePush(boolean force) {
        long now = System.currentTimeMillis();
        if (force) {
            lastPushedMs.set(now);
            return true;
        }
        long prev = lastPushedMs.get();
        if (now - prev < updateIntervalMs()) {
            return false;
        }
        return lastPushedMs.compareAndSet(prev, now);
    }

    // ---------------------------------------------------------------------------------
    // Native register/lookup/broadcast (keyed; Update 5 coexists HUDs by string key)
    // ---------------------------------------------------------------------------------

    /**
     * Look up the HUD registered under {@code key} for {@code player}, cast to {@code type},
     * or {@code null} if absent / a different type. Reads the native {@code HudManager} map;
     * call on the world thread (the map is not concurrent).
     */
    @Nullable
    public static <T extends CustomUIHud> T get(@Nonnull Player player, @Nonnull String key, @Nonnull Class<T> type) {
        CustomUIHud hud = player.getHudManager().getCustomHud(key);
        return type.isInstance(hud) ? type.cast(hud) : null;
    }

    /**
     * Resolve the live {@link Player} component for {@code playerRef}, or {@code null} if the
     * reference is stale / the component is missing. The player-lookup seam every broadcast
     * iteration needs, kept mod-agnostic (no consumer world-resolution utility required).
     */
    @Nullable
    protected static Player resolvePlayer(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return null;
        }
        return ref.getStore().getComponent(ref, Player.getComponentType());
    }

    /**
     * Resolve any alive world from the first online player found. A HUD broadcast has no
     * per-command sender to resolve a world FROM, so this is the generic "any alive world"
     * fallback (kept here so the base carries no MMO-specific world-lookup dependency).
     */
    @Nullable
    private static World resolveAnyAliveWorld() {
        for (PlayerRef pRef : Universe.get().getPlayers()) {
            UUID worldUuid = pRef.getWorldUuid();
            if (worldUuid != null) {
                World world = Universe.get().getWorld(worldUuid);
                if (world != null && world.isAlive()) {
                    return world;
                }
            }
        }
        return null;
    }

    /**
     * Run {@code action} on every online player's HUD registered under {@code key}, on the
     * world thread with safe Store access (so the action may push a partial update). Resolves
     * a world, then iterates {@link Universe#getPlayers()} inside {@code world.execute}; a
     * failure on one player is logged without aborting the rest. This is the shared iteration
     * for every broadcast-to-all-online HUD op (live reposition, admin enable/disable).
     */
    protected static void forEachOnlineHud(@Nonnull String key, @Nonnull Consumer<KeyedCustomHud> action) {
        World world = resolveAnyAliveWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            for (PlayerRef pRef : Universe.get().getPlayers()) {
                try {
                    Player player = resolvePlayer(pRef);
                    if (player == null) {
                        continue;
                    }
                    CustomUIHud hud = player.getHudManager().getCustomHud(key);
                    if (hud instanceof KeyedCustomHud keyedHud) {
                        action.accept(keyedHud);
                    }
                } catch (Throwable t) {
                    warn("Failed to broadcast to HUD '" + key + "': " + t.getMessage());
                }
            }
        });
    }

    /**
     * Re-anchor every online player's HUD registered under {@code key} to {@code position}
     * so an admin layout change applies live without a reconnect.
     */
    public static void refreshPositionForAllOnline(@Nonnull String key, @Nonnull HudPosition position) {
        forEachOnlineHud(key, hud -> hud.pushPositionUpdate(position));
    }

    // ---------------------------------------------------------------------------------
    // Guarded logging (unit-JVM safe; mirrors the MMO's SafeLog guard, kept local so this
    // base has no consumer logging-facade dependency)
    // ---------------------------------------------------------------------------------

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // no Hytale log manager (unit JVM)
        }
    }
}
