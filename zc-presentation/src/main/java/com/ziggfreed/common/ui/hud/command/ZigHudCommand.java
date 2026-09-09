package com.ziggfreed.common.ui.hud.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * {@code /zighud} - the shared HUD bar panels, from the command line.
 *
 * <h2>The verbs</h2>
 *
 * <pre>
 * /zighud open                                              open the HUD settings page
 * /zighud place --panel=&lt;id&gt; --placement=&lt;id|server&gt; [--player=&lt;name&gt;]
 *                                                           put a panel at a spot for yourself
 * /zighud hide [--panel=&lt;id|all&gt;] [--player=&lt;name&gt;]        hide a panel, or every panel
 * /zighud show [--panel=&lt;id|all&gt;] [--player=&lt;name&gt;]        show it again
 * /zighud default --panel=&lt;id&gt; --placement=&lt;id|server&gt;   set a panel's spot for everyone
 * </pre>
 *
 * <p>Arguments bind by NAME, never by position - that is the engine's parser, not a house style.
 *
 * <h2>Permissions</h2>
 *
 * <p>The family and its first four verbs are a PLAYER's: they change the caller's own screen, so
 * the family sits in the engine's adventurer group and every player holds it, exactly as a
 * consumer's own page-opening commands do. {@code default} writes the owner file for everyone and
 * is not: it sits in the world-editor group instead and otherwise needs its own engine-derived node
 * ({@code ziggfreed.ziggfreedcommon.command.zighud.default}), which nobody holds until a server
 * grants it. Naming another player with {@code --player} is governed the same way: an ordinary
 * player runs the verb on themselves, and a server that wants moderators moving other people's HUDs
 * grants the verb's node.
 *
 * <h2>Why this family belongs to the library</h2>
 *
 * <p>The module that owns the panels owns the commands that drive them, and the page they open. A
 * server running this library and nothing else still gets a way to move its bars, and a consumer
 * mod wanting {@code /myhud} registers an alias that calls straight through.
 */
public final class ZigHudCommand extends AbstractCommandCollection {

    public ZigHudCommand() {
        super(HudCommandLine.FAMILY, HudMessages.desc("family"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new HudOpenCommand());
        addSubCommand(new HudPlaceCommand());
        addSubCommand(new HudHideCommand(true));
        addSubCommand(new HudHideCommand(false));
        addSubCommand(new HudDefaultCommand());
    }
}
