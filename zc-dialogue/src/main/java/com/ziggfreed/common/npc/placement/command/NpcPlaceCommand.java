package com.ziggfreed.common.npc.placement.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.placement.NpcPlacementAuthoring;
import com.ziggfreed.common.npc.placement.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.NpcPlacementService;

/**
 * Stand a new NPC where the caller is standing, and keep it there.
 *
 * <p>Authoring a placement by hand means opening a file, knowing the world's exact name, and reading
 * three coordinates off somewhere - for the commonest case of all, which is "put this character
 * HERE". This verb takes them off the caller instead and writes the same file an author would.
 *
 * <p>What it writes is an ordinary placement in the server owner's own
 * {@code mods/ziggfreedcommon/npc-placements.json}, in exactly the shape a pack ships, so the result
 * is readable, editable and removable by hand afterwards. It is not a separate kind of placement and
 * gets no privileged path.
 *
 * <p>It REFUSES an id that already names a placement rather than overwriting one: an id collision
 * here would silently replace a pack's character with a copy of whoever ran the command, and the
 * only sign in game would be the wrong NPC in the right spot.
 */
final class NpcPlaceCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> roleArg;
    private final OptionalArg<String> idArg;
    private final OptionalArg<String> dialogueArg;
    private final OptionalArg<String> worldArg;

    NpcPlaceCommand() {
        super(NpcCommandLine.PLACE, NpcAdminMessages.desc(NpcCommandLine.PLACE));
        this.roleArg = withRequiredArg("role", NpcAdminMessages.desc("arg.role"), ArgTypes.STRING);
        this.idArg = withOptionalArg("id", NpcAdminMessages.desc("arg.id"), ArgTypes.STRING);
        this.dialogueArg = withOptionalArg("dialogue", NpcAdminMessages.desc("arg.dialogue"),
                ArgTypes.STRING);
        this.worldArg = withOptionalArg("world", NpcAdminMessages.desc("arg.world"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A position is the whole point of the verb, and only a player has one.
        if (!ctx.isPlayer()) {
            NpcAdminMessages.refused(ctx, "place.needsPlayer");
            return CompletableFuture.completedFuture(null);
        }
        Ref<EntityStore> ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            NpcAdminMessages.refused(ctx, "place.needsPlayer");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref, world), world);
    }

    /** Reading a transform and sweeping both want the world thread, so the whole verb runs there. */
    private void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull World world) {

        String role = trimToNull(roleArg.get(ctx));
        if (role == null) {
            NpcAdminMessages.refused(ctx, "place.needsRole");
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            NpcAdminMessages.refused(ctx, "place.noPosition");
            return;
        }
        var position = transform.getPosition();

        String worldName = worldArg.provided(ctx)
                ? trimToNull(worldArg.get(ctx))
                : NpcPlacementService.worldName(world);
        if (worldName == null) {
            NpcAdminMessages.refused(ctx, "place.needsWorld");
            return;
        }

        NpcPlacementAuthoring.Result result = NpcPlacementAuthoring.place(world, store, role,
                idOf(ctx, role),
                dialogueArg.provided(ctx) ? trimToNull(dialogueArg.get(ctx)) : null,
                worldName,
                NpcPlacementAuthoring.round(position.x(), 2),
                NpcPlacementAuthoring.round(position.y(), 2),
                NpcPlacementAuthoring.round(position.z(), 2),
                NpcPlacementAuthoring.round(transform.getRotation().yaw(), 1));

        report(ctx, result);
    }

    /** Say what happened, in the reader's own language. */
    private static void report(@Nonnull CommandContext ctx,
            @Nonnull NpcPlacementAuthoring.Result result) {
        String file = NpcPlacementOverrides.getInstance().getFile().toString();
        switch (result.outcome()) {
            case ID_TAKEN -> NpcAdminMessages.refused(ctx, "place.idTaken", result.id());
            case ROLE_NOT_SPAWNABLE ->
                    NpcAdminMessages.refused(ctx, "place.roleNotSpawnable", result.role());
            case WRITE_FAILED -> NpcAdminMessages.refused(ctx, "place.writeFailed", file);
            case PLACED -> {
                NpcAdminMessages.done(ctx, "place.done", result.id(), result.role(),
                        result.worldName());
                NpcAdminMessages.detail(ctx, "place.at", result.x(), result.y(), result.z(),
                        result.yaw());
                NpcAdminMessages.detail(ctx, "place.file", file);
            }
        }
    }

    /**
     * The placement id to write: the authored one, else the role in lower case. A character standing
     * in one spot rarely needs a name of its own, and the role already is one - and an id that IS the
     * role is what makes the placement answer to that character for quest content, with no
     * {@code Identity.NpcId} to author.
     */
    @Nonnull
    private String idOf(@Nonnull CommandContext ctx, @Nonnull String role) {
        String authored = idArg.provided(ctx) ? trimToNull(idArg.get(ctx)) : null;
        String chosen = authored != null ? authored : role;
        return chosen.toLowerCase(Locale.ROOT);
    }

    /** {@code value} without surrounding blanks, or null when there was nothing but blanks. */
    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
