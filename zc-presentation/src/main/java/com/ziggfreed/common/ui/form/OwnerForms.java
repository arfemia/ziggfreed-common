package com.ziggfreed.common.ui.form;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.owner.OwnerViewer;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to an owner form ({@link OwnerFormAsset}) and the one rule for who sees one: the owner audience
 * ({@link OwnerAudience}) and the live part of the form's own {@code Requires}, asked of the owner. The owner menu
 * lists a form by it, and the form page asks it again on every event. A form of the Entries shape shows only while it
 * has something to offer: an entry it lists, or one an owner may add ({@link #offersAnything}), so a list whose every
 * entry is hidden (a feature switched off on this server) shows no tile and does not open. Deliberately not a
 * registered destination: a form writes an owner file. World thread.
 */
public final class OwnerForms {

    private OwnerForms() {
    }

    /**
     * May {@code viewer} see {@code form}: does its {@code Requires} pass for them (the audience asked apart), and does
     * it offer anything?
     */
    public static boolean shownTo(@Nonnull OwnerFormAsset form, @Nonnull OwnerViewer viewer) {
        return requiresPass(form, viewer.playerRef(), viewer.player()) && offersAnything(form);
    }

    /**
     * Does {@code form} offer anything: a form of settings always; a form of the Entries shape an entry it lists or
     * one an owner may add ({@link OwnerFormEntries#offersAnything}).
     */
    public static boolean offersAnything(@Nonnull OwnerFormAsset form) {
        if (form.entries() == null) {
            return true;
        }
        try {
            return OwnerFormEntries.read(form).offersAnything();
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner form '" + form.getId() + "' could not list its entries, so it is hidden: "
                    + t.getMessage());
            return false;
        }
    }

    /** Does {@code form}'s live {@code Requires} pass for this player? Nothing to ask passes; no player fails a gate. */
    public static boolean requiresPass(@Nonnull OwnerFormAsset form, @Nullable PlayerRef playerRef,
            @Nullable Player player) {
        GateSpec live = form.liveRequires();
        if (live == null) {
            return true;
        }
        Subject subject = Subject.of(playerRef, player);
        if (subject == null) {
            return false;
        }
        try {
            return StoreGates.passes(live, GateQuery.of(subject));
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner form '" + form.getId() + "' could not ask its Requires, so it is hidden: "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * Open the form {@code formId} names for {@code viewer}, its Back returning to the owner menu with {@code menuFirst}
     * at the front. False when the form is gone, the audience or the form's {@code Requires} refuses, it offers nothing,
     * or the open threw.
     */
    public static boolean open(@Nonnull OwnerViewer viewer, @Nonnull String formId, @Nullable String menuFirst) {
        return open(viewer.store(), viewer.ref(), viewer.player(), formId, menuFirst);
    }

    /** {@link #open(OwnerViewer, String, String)} for a consumer opening a form from its own owner screen. */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nonnull String formId, @Nullable String menuFirst) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        OwnerFormAsset form = OwnerFormConfig.getInstance().form(formId);
        if (playerRef == null || form == null || !OwnerAudience.mayAdminister(store, ref, player)
                || !requiresPass(form, playerRef, player) || !offersAnything(form)) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new OwnerFormPage(playerRef, form.getId(), menuFirst));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner form '" + formId + "' failed to open", t);
            return false;
        }
    }
}
