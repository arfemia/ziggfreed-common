package com.ziggfreed.common.objectives.title;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;

/**
 * The ONE write path onto a player's titles ({@link ZigTitleComponent}): unlock, revoke, show one
 * (activate), show none (deactivate), and read back. The {@code Title} reward kind, every
 * {@code /zigtitle} verb and the picker come through here, so a change is announced the same way
 * whoever made it: one native event, the off-thread mirror ({@link ActiveTitles}) refreshed, and
 * for a new unlock one notice. A write that changed nothing announces nothing.
 *
 * <p><b>World thread</b>, like every component write: the live forms take the player's own
 * {@code (store, ref)} and their {@link PlayerRef}.
 */
public final class TitleUnlocks {

    /** What a write did, and whether it was a REAL change (the only kind announced). */
    public enum Outcome {

        /** The title was not there and is now. Announced, with a notice. */
        UNLOCKED(true),

        /** The player already had it; nothing changed. */
        ALREADY_UNLOCKED(false),

        /** The title was there and is gone, and no longer shown. Announced. */
        REVOKED(true),

        /** The player never had it (a revoke, or showing a title they have not earned). */
        NOT_UNLOCKED(false),

        /** The player shows this title now. Announced. */
        ACTIVATED(true),

        /** The player already showed it; nothing changed. */
        ALREADY_ACTIVE(false),

        /** The player shows no title now. Announced. */
        DEACTIVATED(true),

        /** The player showed no title; nothing changed. */
        NONE_ACTIVE(false),

        /** The id is blank or carries a character the save format reserves; nothing was written. */
        REFUSED(false),

        /** The player carries no title record (registration failed, or not attached). */
        NO_RECORD(false);

        private final boolean changed;

        Outcome(boolean changed) {
            this.changed = changed;
        }

        /** True for the four outcomes that changed the record, which are the four announced. */
        public boolean changed() {
            return changed;
        }
    }

    private TitleUnlocks() {
    }

    /** Unlock {@code titleId}; one the player already has is a successful no-op. */
    @Nonnull
    public static Outcome unlock(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef, @Nullable String titleId) {
        return write(peek(store, ref), subjectOf(playerRef), titleId, true);
    }

    /** Take {@code titleId} away (and stop showing it); one they never had is a no-op. */
    @Nonnull
    public static Outcome revoke(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef, @Nullable String titleId) {
        return write(peek(store, ref), subjectOf(playerRef), titleId, false);
    }

    /** Show {@code titleId}, which must be unlocked. */
    @Nonnull
    public static Outcome activate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                   @Nonnull PlayerRef playerRef, @Nullable String titleId) {
        return activateWrite(peek(store, ref), subjectOf(playerRef), titleId);
    }

    /** Show no title. */
    @Nonnull
    public static Outcome deactivate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef) {
        return deactivateWrite(peek(store, ref), subjectOf(playerRef));
    }

    /** Every title the player has unlocked, sorted; empty when they carry no record. */
    @Nonnull
    public static List<String> unlocked(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return unlockedOf(peek(store, ref));
    }

    /** The title the player shows, or null for none or no record. */
    @Nullable
    public static String active(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ZigTitleComponent titles = peek(store, ref);
        return titles == null ? null : titles.activeTitle();
    }

    /** The player's record, or null when the type never registered or this entity carries none. */
    @Nullable
    public static ZigTitleComponent peek(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return ZigTitleComponent.TYPE == null ? null : store.getComponent(ref, ZigTitleComponent.TYPE);
    }

    // ==================== the writes themselves (a test drives these directly) ====================

    @Nonnull
    static Outcome write(@Nullable ZigTitleComponent titles, @Nonnull Subject who,
                         @Nullable String titleId, boolean unlock) {
        if (titles == null) {
            return Outcome.NO_RECORD;
        }
        String id = usable(titleId);
        if (id == null) {
            return Outcome.REFUSED;
        }
        boolean done = unlock ? titles.unlock(id) : titles.revoke(id);
        if (!done) {
            return unlock ? Outcome.ALREADY_UNLOCKED : Outcome.NOT_UNLOCKED;
        }
        Outcome outcome = unlock ? Outcome.UNLOCKED : Outcome.REVOKED;
        changed(titles, who, id, outcome);
        if (unlock) {
            TitleText.announceUnlocked(who, id);
        }
        return outcome;
    }

    @Nonnull
    static Outcome activateWrite(@Nullable ZigTitleComponent titles, @Nonnull Subject who,
                                 @Nullable String titleId) {
        if (titles == null) {
            return Outcome.NO_RECORD;
        }
        String id = usable(titleId);
        if (id == null) {
            return Outcome.REFUSED;
        }
        if (!titles.hasTitle(id)) {
            return Outcome.NOT_UNLOCKED;
        }
        if (!titles.activate(id)) {
            return Outcome.ALREADY_ACTIVE;
        }
        changed(titles, who, id, Outcome.ACTIVATED);
        return Outcome.ACTIVATED;
    }

    @Nonnull
    static Outcome deactivateWrite(@Nullable ZigTitleComponent titles, @Nonnull Subject who) {
        if (titles == null) {
            return Outcome.NO_RECORD;
        }
        String was = titles.activeTitle();
        if (was == null || !titles.deactivate()) {
            return Outcome.NONE_ACTIVE;
        }
        changed(titles, who, was, Outcome.DEACTIVATED);
        return Outcome.DEACTIVATED;
    }

    @Nonnull
    static List<String> unlockedOf(@Nullable ZigTitleComponent titles) {
        if (titles == null || titles.unlockedTitles == null || titles.unlockedTitles.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(titles.unlockedTitles);
        Collections.sort(ids);
        return ids;
    }

    /** A real change: refresh the mirror first (a listener may read it), then announce. */
    private static void changed(@Nonnull ZigTitleComponent titles, @Nonnull Subject who,
                                @Nonnull String titleId, @Nonnull Outcome change) {
        String shown = titles.activeTitle();
        ActiveTitles.put(who.id(), shown);
        TitleEvents.fireChanged(who, titleId, change, shown);
    }

    @Nullable
    private static String usable(@Nullable String titleId) {
        if (titleId == null || titleId.isBlank() || ZigTitleComponent.usesReservedDelimiter(titleId)) {
            return null;
        }
        return titleId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The subject a live write is announced for: {@link PlayerRefSubjectHandle} answers the
     * {@link PlayerRef} the event and the notice ask for. Built here, so a title write needs no
     * progression runtime.
     */
    @Nonnull
    private static Subject subjectOf(@Nonnull PlayerRef playerRef) {
        return PlayerRefSubjectHandle.subjectFor(playerRef, playerRef.getUsername());
    }
}
