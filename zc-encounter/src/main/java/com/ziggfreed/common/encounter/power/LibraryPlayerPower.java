package com.ziggfreed.common.encounter.power;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.seam.PlayerPowerSource;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's own answer to a player's power, what the player power seam reads while no mod fills it: the
 * {@link PlayerPowerAsset} record's inputs read for the player through the factor vocabulary (the portable
 * {@code hytale:} readings, every contributed reading and every {@code Server/ZiggfreedCommon/Factors} definition),
 * weighted, summed and held on its scale. As shipped it reads worn gear, so a server running the library alone
 * still has a power that rises as a player gears up.
 */
public final class LibraryPlayerPower implements PlayerPowerSource {

    /** The one instance the seam falls back to. */
    public static final LibraryPlayerPower INSTANCE = new LibraryPlayerPower();

    /** What the reading is called in a log line or an owner's page. */
    public static final String OWNER = "ziggfreedcommon";

    /** The vocabulary an input resolves through: the portable set here, the rest through the shared layers. */
    @Nonnull
    private static final FactorRegistry REGISTRY = buildRegistry();

    private LibraryPlayerPower() {
    }

    @Nullable
    @Override
    public Double powerOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        if (!playerRef.isValid()) {
            return null;
        }
        FactorContext ctx = FactorContext.about(store, playerRef);
        return PlayerPowerConfig.getInstance().settings()
                .read((factorId, param) -> REGISTRY.resolve(factorId, ctx.withParam(param)));
    }

    @Nonnull
    @Override
    public String owner() {
        return OWNER;
    }

    @Nonnull
    private static FactorRegistry buildRegistry() {
        FactorRegistry registry = new FactorRegistry(OWNER + ":player_power");
        try {
            HytaleFactors.registerInto(registry, OWNER);
        } catch (Throwable t) {
            // A unit JVM without the engine classes still gets a registry; the portable ids read nothing there.
            SafeLog.warn("[encounter] the portable factors could not be registered for the player power reading: "
                    + t.getMessage());
        }
        return registry;
    }
}
