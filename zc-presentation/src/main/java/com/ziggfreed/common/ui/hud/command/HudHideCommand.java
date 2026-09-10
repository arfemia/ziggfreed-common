package com.ziggfreed.common.ui.hud.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanelLayout;
import com.ziggfreed.common.ui.hud.panel.HudPanels;

/**
 * Hide a panel, or every panel, for the caller (or the named player), and the verb that shows it
 * again: one class, because the two are the same walk with the switch thrown the other way. With no
 * {@code --panel}, or {@code --panel=all}, it is the switch over every panel that moves, and the
 * per-panel switches are left as they were.
 */
final class HudHideCommand extends AbstractTargetPlayerCommand<PlayerRef> {

    private final boolean hide;
    private final OptionalArg<String> panelArg;

    HudHideCommand(boolean hide) {
        super(hide ? HudCommandLine.HIDE : HudCommandLine.SHOW,
                HudMessages.desc(hide ? HudCommandLine.HIDE : HudCommandLine.SHOW),
                HudMessages.desc("arg.player"), HudMessages::refused);
        this.hide = hide;
        this.panelArg = withOptionalArg("panel", HudMessages.desc("arg.panel_or_all"), ArgTypes.STRING);
    }

    @Override
    @Nullable
    protected PlayerRef buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return playerRef;
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull PlayerRef target) {
        String panelId = panelArg.provided(ctx) ? panelArg.get(ctx) : null;
        boolean every = panelId == null || panelId.isBlank()
                || HudCommandLine.ALL.equalsIgnoreCase(panelId.trim());
        if (every) {
            HudPreferences.setHideAll(target, hide);
            HudMessages.done(ctx, hide ? "hide.all" : "show.all");
            return;
        }
        HudPanelLayout layout = HudPanels.panel(panelId);
        if (layout == null) {
            HudMessages.refused(ctx, "panel.unknown", panelId);
            return;
        }
        HudPreferences.setHidden(target, layout.panelId(), hide);
        HudMessages.done(ctx, hide ? "hide.done" : "show.done",
                HudPanelConfig.getInstance().panel(layout.panelId()).label());
    }
}
