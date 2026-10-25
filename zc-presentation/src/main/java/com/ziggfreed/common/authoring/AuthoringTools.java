package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.util.SafeLog;

/**
 * The authoring tools this server has, by tool id, and what each opens. A tool is an item taking
 * {@code Zig_Author_Tool} as its {@code Parent} (which runs {@link ZigAuthoringToolInteraction} on a click) and naming
 * itself in its {@code ZigAuthorTool} tag ({@code "Tags": {"ZigAuthorTool": ["Quest"]}}), plus one registration
 * here at setup: {@link #wizardTool} for a tool that opens its own wizard on what it points at (the common case), or
 * {@link #register} for one that does something else.
 *
 * <p><b>Who may use one.</b> Every use is checked on the server at the click, never trusted from the client: the
 * player is in creative mode and holds the tool's permission, {@code ziggfreedcommon.author.<tool id, lower case>}
 * ({@link #permission}; {@code ziggfreedcommon.author.*} grants every tool). A registration puts the node in vanilla's
 * server editor group ({@code HytalePermissionsProvider.GROUP_SERVER_EDITOR}, the Asset Editor's), so a player allowed
 * into the Asset Editor may use every authoring tool; an operator may also grant the node alone. Register at setup:
 * the engine folds registered nodes into its groups when its permission module starts.
 */
public final class AuthoringTools {

    /** Every tool's permission node starts so; the tool id, lower-cased, follows. */
    public static final String PERMISSION_PREFIX = "ziggfreedcommon.author.";

    /** The item tag naming which tool an item is. */
    public static final String TOOL_TAG = "ZigAuthorTool";

    /** What a tool does when used, on the player's world thread. False when it opened nothing. */
    @FunctionalInterface
    public interface Opener {

        boolean open(@Nonnull AuthoringUse use);
    }

    private record Registered(@Nonnull String id, @Nonnull Opener opener) {
    }

    private static final Map<String, Registered> TOOLS = new ConcurrentHashMap<>();

    private AuthoringTools() {
    }

    /**
     * Register tool {@code toolId} (one per id; a second registration replaces the first) and its permission node.
     * Setup time.
     */
    public static void register(@Nonnull String toolId, @Nonnull Opener opener) {
        String trimmed = toolId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a tool id is blank");
        }
        Registered before = TOOLS.put(fold(trimmed), new Registered(trimmed, opener));
        if (before != null) {
            SafeLog.info("[authoring] tool " + trimmed + " was registered again; the newer one opens");
        }
        registerNode(trimmed);
    }

    /**
     * Register the author node of tool or wizard {@code id} ({@link #permission}) into vanilla's server editor group,
     * the Asset Editor's: whoever may edit the server's assets may author. A second registration of one node adds
     * nothing. Setup time.
     */
    static void registerNode(@Nonnull String id) {
        try {
            PermissionsModule.registerPermission(permission(id), HytalePermissionsProvider.GROUP_SERVER_EDITOR);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] the author node of " + id + " could not join the server editor group; "
                    + "granting it still works: " + t.getMessage());
        }
    }

    /**
     * Register wizard {@code id} ({@link AuthoringWizards#register}) and the tool of the same id that opens it on
     * what it points at: the one registration an editor's tool needs beside its item file.
     */
    public static void wizardTool(@Nonnull String id, @Nonnull AuthoringWizards.Factory factory) {
        AuthoringWizards.register(id, factory);
        String wizard = id.trim();
        register(wizard, use -> AuthoringWizards.open(wizard, use));
    }

    /** Every registered tool id, as registered, sorted. */
    @Nonnull
    public static List<String> ids() {
        TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Registered r : TOOLS.values()) {
            ids.add(r.id());
        }
        return new ArrayList<>(ids);
    }

    /** The author node a player needs to use tool {@code toolId}, or to save in the wizard of that id. */
    @Nonnull
    public static String permission(@Nonnull String toolId) {
        return PERMISSION_PREFIX + toolId.trim().toLowerCase(Locale.ROOT);
    }

    /** Why {@code player} may not use tool {@code toolId} now, or null when they may: creative mode, then the node. */
    @Nullable
    public static Message refusal(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull String toolId) {
        if (player.getGameMode() != GameMode.Creative) {
            return AuthoringText.line("tool.creative");
        }
        String node = permission(toolId);
        return playerRef.hasPermission(node) ? null : AuthoringText.line("tool.permission", node);
    }

    /** The tool {@code held} is, by its item's {@code ZigAuthorTool} tag; null for an item that is no tool. */
    @Nullable
    public static String toolOf(@Nullable ItemStack held) {
        try {
            Item item = held == null ? null : held.getItem();
            AssetExtraInfo.Data data = item == null ? null : item.getData();
            return toolOf(data == null ? null : data.getRawTags());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The first {@code ZigAuthorTool} value of a raw tag map (the key matched without regard to case). Pure. */
    @Nullable
    static String toolOf(@Nullable Map<String, String[]> rawTags) {
        if (rawTags == null) {
            return null;
        }
        for (Map.Entry<String, String[]> e : rawTags.entrySet()) {
            String[] values = e.getValue();
            if (TOOL_TAG.equalsIgnoreCase(e.getKey()) && values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- a click

    /**
     * A click with a tool, inside the interaction's tick: name the tool ({@code configured}, else the held item's
     * tag), check the player may use it, read what it points at, and act on the player's world thread once the tick
     * is done. False for a chain no player runs, a held item that names no tool, or no world: the chain's Failed
     * branch.
     */
    static boolean click(@Nonnull InteractionContext ctx, @Nullable String configured) {
        CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
        Ref<EntityStore> owner = InteractionCtx.owner(ctx);
        if (buffer == null || owner == null) {
            return false;
        }
        Player player = buffer.getComponent(owner, Player.getComponentType());
        PlayerRef playerRef = InteractionCtx.player(ctx, owner);
        if (player == null || playerRef == null) {
            return false;
        }
        String toolId = configured != null && !configured.isBlank() ? configured.trim()
                : toolOf(InteractionCtx.heldItem(ctx));
        if (toolId == null) {
            SafeLog.fine("[authoring] a ZigAuthoringTool step ran on an item with no " + TOOL_TAG + " tag");
            return false;
        }
        Message refused = refusal(player, playerRef, toolId);
        if (refused != null) {
            Notify.warning(playerRef, refused);
            return true;
        }
        WorldPick pick = WorldPick.read(ctx);
        World world = InteractionCtx.world(ctx);
        if (world == null) {
            return false;
        }
        String tool = toolId;
        world.execute(() -> act(owner, tool, pick));
        return true;
    }

    /** On the world thread, outside every system: a parked wizard takes the click, else the tool's own opener. */
    private static void act(@Nonnull Ref<EntityStore> owner, @Nonnull String toolId, @Nonnull WorldPick pick) {
        AuthoringUse use;
        try {
            Store<EntityStore> store = owner.getStore();
            use = owner.isValid() && store.isInThread() ? AuthoringUse.of(owner, store, pick) : null;
        } catch (Throwable t) {
            use = null;
        }
        if (use == null) {
            return;
        }
        if (AuthoringWizards.resolvePending(use)) {
            return;
        }
        Registered tool = TOOLS.get(fold(toolId));
        if (tool == null) {
            Notify.warning(use.playerRef(), AuthoringText.line("tool.nothing"));
            return;
        }
        try {
            if (!tool.opener().open(use)) {
                SafeLog.fine("[authoring] tool " + tool.id() + " opened nothing");
            }
        } catch (Throwable t) {
            SafeLog.warn("[authoring] tool " + tool.id() + " failed to open", t);
        }
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
