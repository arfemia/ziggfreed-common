package com.ziggfreed.common.commerce.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.subject.Subject;

/**
 * The commerce family's fill of the shared target-player walk ({@link AbstractTargetPlayerCommand}):
 * the resolved online player becomes a {@link Subject} directly.
 *
 * <p><b>The subject's handle is the live {@link Player}</b>, which is what every store and wallet in
 * this module resolves through - the component-backed state off the player's entity, and the
 * inventory behind an item-backed balance. Building one any other way would read neutral and drop
 * every write while reporting success.
 */
abstract class TargetPlayerSubCommand extends AbstractTargetPlayerCommand<Subject> {

    TargetPlayerSubCommand(@Nonnull String name) {
        super(name, CommerceAdminMessages.desc(name), CommerceAdminMessages.desc("arg.player"),
                CommerceAdminMessages::refused);
    }

    @Override
    @Nullable
    protected Subject buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return Subject.of(playerRef, store.getComponent(ref, Player.getComponentType()));
    }
}
