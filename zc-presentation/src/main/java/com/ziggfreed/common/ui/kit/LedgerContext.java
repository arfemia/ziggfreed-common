package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Who a contributed source is reading for, on the world thread of the page that asked, and when. */
public record LedgerContext(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef viewer, long nowMs) {

    public LedgerContext {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(viewer, "viewer");
    }
}
