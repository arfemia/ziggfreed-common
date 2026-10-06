package com.ziggfreed.common.reputation.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.ReputationText;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;

/**
 * When the shared menu offers Reputation: while the module is on and the player has met at least one
 * switched-on reputation (an entry of their own, or a standing other than 0). Asked on every paint.
 */
public final class ReputationMenuTab {

    /** The tab's picture: a vanilla banner, so the tab has one on a server running no pack. */
    public static final String ICON_ITEM = "Furniture_Human_Ruins_Banner";

    private ReputationMenuTab() {
    }

    /** The rule. */
    public static boolean visible(@Nonnull ReputationService service, @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref) {
        return service.isOn() && service.anyMet(store, ref);
    }

    /** The Reputation tab in the shared menu. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.REPUTATION.entry(ReputationText.line("menu.tab"), IconSpec.ofItem(ICON_ITEM),
                ReputationDestinations.REPUTATION,
                viewer -> visible(ReputationRuntime.service(), viewer.store(), viewer.playerReference()));
    }
}
