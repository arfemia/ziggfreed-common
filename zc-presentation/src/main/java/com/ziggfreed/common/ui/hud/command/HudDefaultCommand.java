package com.ziggfreed.common.ui.hud.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.ziggfreed.common.ui.hud.panel.HudOwnerLayers;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudPanelLayout;
import com.ziggfreed.common.ui.hud.panel.HudPanelOwnerWriter;
import com.ziggfreed.common.ui.hud.panel.HudPanels;
import com.ziggfreed.common.ui.hud.panel.HudSpotAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotConfig;

/**
 * Set the spot a panel sits at for everyone: what the settings page's Server tab does, from a
 * startup script or a console. It writes the same owner file the page writes, through the same
 * writer, and {@code --placement=server} takes the panel back to whatever its shipped file says.
 * Usable from the console, since nothing here needs a screen.
 */
final class HudDefaultCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> panelArg;
    private final RequiredArg<String> placementArg;

    HudDefaultCommand() {
        super(HudCommandLine.DEFAULT, HudMessages.desc(HudCommandLine.DEFAULT));
        // An owner verb, not a player's: the family's adventurer group does not reach it.
        setPermissionGroups(HytalePermissionsProvider.GROUP_WORLD_EDITOR);
        this.panelArg = withRequiredArg("panel", HudMessages.desc("arg.panel"), ArgTypes.STRING);
        this.placementArg = withRequiredArg("placement", HudMessages.desc("arg.placement"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String panelId = panelArg.get(ctx);
        HudPanelLayout layout = HudPanels.panel(panelId);
        if (layout == null) {
            HudMessages.refused(ctx, "panel.unknown", panelId == null ? "" : panelId);
            return CompletableFuture.completedFuture(null);
        }
        var panelLabel = HudPanelConfig.getInstance().panel(layout.panelId()).label();
        String file = HudOwnerLayers.panelsFile().toString();
        String wanted = placementArg.get(ctx);
        boolean clear = wanted == null || wanted.isBlank()
                || HudCommandLine.SERVER_CHOICE.equalsIgnoreCase(wanted.trim());
        if (clear) {
            if (HudPanelOwnerWriter.setPlacement(layout.panelId(), null)) {
                HudMessages.done(ctx, "default.cleared", panelLabel, file);
            } else {
                HudMessages.refused(ctx, "default.failed", file);
            }
            return CompletableFuture.completedFuture(null);
        }
        HudSpotAsset spot = HudSpotConfig.getInstance().spot(wanted);
        if (spot == null || !spot.enabled()) {
            HudMessages.refused(ctx, "placement.unknown", wanted.trim());
            return CompletableFuture.completedFuture(null);
        }
        if (!spot.fits(layout.panelId())) {
            HudMessages.refused(ctx, "placement.unfit", spot.label(), panelLabel);
            return CompletableFuture.completedFuture(null);
        }
        if (HudPanelOwnerWriter.setPlacement(layout.panelId(), spot.getId())) {
            HudMessages.done(ctx, "default.done", panelLabel, spot.label(), file);
        } else {
            HudMessages.refused(ctx, "default.failed", file);
        }
        return CompletableFuture.completedFuture(null);
    }
}
