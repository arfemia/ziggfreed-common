package com.ziggfreed.common.encounter.run;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/**
 * Which entity a fight is ABOUT: the boss, read off the encounter's own target slots every time it
 * is asked, because an in-place role change reissues the boss's reference and only the slot (and its
 * uuid) survives.
 *
 * <p>The binding row's {@code Subject.TargetSlot} names the slot (default {@code Boss}); with
 * {@code AnyOccupiedSlot}, or with no row at all, the first occupied slot answers when the named
 * one is empty. A slot holding a PLAYER is never a subject.
 */
public final class EncounterSubjects {

    private EncounterSubjects() {
    }

    /**
     * The subject bound right now, or null.
     *
     * @param subject the row's Subject group, or null when the row authored none or there is no row
     * @param hasRow  whether a binding row exists at all; without one the slot walk is always on
     */
    @Nullable
    public static Ref<EntityStore> resolve(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> encounterRef, @Nullable EncounterBindingAsset.Subject subject, boolean hasRow) {
        if (!encounterRef.isValid()) {
            return null;
        }
        MarkedEntitySupport slots = accessor.getComponent(encounterRef, MarkedEntitySupport.getComponentType());
        if (slots == null) {
            return null;
        }
        String slotName = subject == null ? EncounterBindingAsset.DEFAULT_TARGET_SLOT : subject.targetSlot();
        Ref<EntityStore> named = nonPlayer(accessor, slots.getMarkedEntityRef(slotName));
        if (named != null) {
            return named;
        }
        boolean walk = subject == null ? !hasRow : subject.anyOccupiedSlot();
        if (!walk) {
            return null;
        }
        int count = slots.getMarkedEntitySlotCount();
        for (int i = 0; i < count; i++) {
            Ref<EntityStore> any = nonPlayer(accessor, slots.getMarkedEntityRef(i));
            if (any != null) {
                return any;
            }
        }
        return null;
    }

    /** The uuid of {@code ref}, or null when it carries none. */
    @Nullable
    public static UUID uuidOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent uuid = accessor.getComponent(ref, UUIDComponent.getComponentType());
        return uuid == null ? null : uuid.getUuid();
    }

    @Nullable
    private static Ref<EntityStore> nonPlayer(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return accessor.getComponent(ref, PlayerRef.getComponentType()) == null ? ref : null;
    }
}
