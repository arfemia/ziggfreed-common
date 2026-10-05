package com.ziggfreed.common.commerce.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;
import com.ziggfreed.common.world.WorldSelector;

/**
 * The WORLD axis a storefront and a board read off their own {@code Where}: whether one exists
 * where the player looking at it is standing right now. It sits beside {@link HideAxis}, which
 * answers whether the thing exists on this server at all; a storefront or a board is open to a
 * player only when both say yes.
 *
 * <p>The match is the shared {@link WorldSelector#match(String, String)}, the one world grammar
 * every file in the family speaks ({@code Match}, {@code GameplayConfig}, {@code ExcludeMatch}),
 * never a second matcher. The default is this read site's own, since the selector carries none: an
 * unauthored or empty {@code Where} is every world.
 *
 * <p>The viewer is the world as a {@code Where} scores it, its name and its authored
 * {@code GameplayConfig} key ({@link LoadedWorld}), so the decision stays pure and a test needs no
 * server. {@link #viewer(World)} and {@link #viewer(Store)} read it off a live world; a world that
 * cannot be read comes back with neither axis, which no {@code Where} matches, so that player sees
 * only what exists everywhere.
 *
 * <p>There is no viewer-less form here on purpose: a listing with nobody looking (an admin verb, a
 * server-wide question such as "does this server sell anything?") reads the configs' plain
 * {@code listed()}, which ignores {@code Where}.
 */
public final class WhereAxis {

    /** A viewer whose world cannot be read: no name, no config, so no {@code Where} matches it. */
    private static final LoadedWorld UNREADABLE = new LoadedWorld(null, null);

    private WhereAxis() {
    }

    /**
     * Does content with this {@code Where} exist in {@code viewer}'s world? True when nothing is
     * authored; otherwise the shared matcher decides, and a null viewer reads as one whose world
     * cannot be read.
     */
    public static boolean present(@Nullable WorldSelector where, @Nullable LoadedWorld viewer) {
        if (where == null || where.isBlank()) {
            return true;
        }
        return viewer != null && where.match(viewer.name(), viewer.gameplayConfig()) != null;
    }

    /**
     * The viewer standing in {@code world}: its name and its {@code GameplayConfig} key. Never null;
     * a null world or a failed read gives the viewer no {@code Where} matches.
     */
    @Nonnull
    public static LoadedWorld viewer(@Nullable World world) {
        if (world == null) {
            return UNREADABLE;
        }
        try {
            return new LoadedWorld(world.getName(), world.getWorldConfig().getGameplayConfig());
        } catch (Throwable unreadable) {
            return UNREADABLE;
        }
    }

    /**
     * The viewer standing in the world {@code store} belongs to, read the established way
     * ({@code store.getExternalData().getWorld()}). World thread, like every page and open that
     * holds a store.
     */
    @Nonnull
    public static LoadedWorld viewer(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return UNREADABLE;
        }
        try {
            return viewer(store.getExternalData().getWorld());
        } catch (Throwable unreadable) {
            return UNREADABLE;
        }
    }
}
