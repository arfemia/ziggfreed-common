package com.ziggfreed.common.ui.hud.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.ui.hud.HudPreferences;
import com.ziggfreed.common.ui.hud.bar.HudBarLayout;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelConfig;
import com.ziggfreed.common.ui.hud.bar.HudBarPlacementAsset;
import com.ziggfreed.common.ui.hud.bar.HudBarPlacementConfig;
import com.ziggfreed.common.ui.hud.bar.HudBars;

/**
 * Put a panel at a named spot for the caller (or the named player): what the settings page's
 * picker does, for a script or a player who prefers typing. {@code --placement=server} clears the
 * pick so the server's own choice applies again.
 */
final class HudPlaceCommand extends AbstractTargetPlayerCommand<PlayerRef> {

    private final RequiredArg<String> panelArg;
    private final RequiredArg<String> placementArg;

    HudPlaceCommand() {
        super(HudCommandLine.PLACE, HudMessages.desc(HudCommandLine.PLACE), HudMessages.desc("arg.player"),
                HudMessages::refused);
        this.panelArg = withRequiredArg("panel", HudMessages.desc("arg.panel"), ArgTypes.STRING);
        this.placementArg = withRequiredArg("placement", HudMessages.desc("arg.placement"), ArgTypes.STRING);
    }

    @Override
    @Nullable
    protected PlayerRef buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return playerRef;
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull PlayerRef target) {
        String panelId = panelArg.get(ctx);
        HudBarLayout layout = HudBars.panel(panelId);
        if (layout == null) {
            HudMessages.refused(ctx, "panel.unknown", panelId == null ? "" : panelId);
            return;
        }
        var panelLabel = HudBarPanelConfig.getInstance().panel(layout.panelId()).label();
        String wanted = placementArg.get(ctx);
        boolean clear = wanted == null || wanted.isBlank()
                || HudCommandLine.SERVER_CHOICE.equalsIgnoreCase(wanted.trim());
        if (clear) {
            if (HudPreferences.setPlacementPick(target, layout.panelId(), null)) {
                HudMessages.done(ctx, "place.cleared", panelLabel);
            } else {
                HudMessages.detail(ctx, "place.unchanged");
            }
            return;
        }
        HudBarPlacementAsset spot = HudBarPlacementConfig.getInstance().placement(wanted);
        if (spot == null || !spot.enabled()) {
            HudMessages.refused(ctx, "placement.unknown", wanted.trim());
            return;
        }
        if (!spot.fits(layout.panelId())) {
            HudMessages.refused(ctx, "placement.unfit", spot.label(), panelLabel);
            return;
        }
        if (HudPreferences.setPlacementPick(target, layout.panelId(), spot.getId())) {
            HudMessages.done(ctx, "place.done", panelLabel, spot.label());
        } else {
            HudMessages.detail(ctx, "place.unchanged");
        }
    }
}
