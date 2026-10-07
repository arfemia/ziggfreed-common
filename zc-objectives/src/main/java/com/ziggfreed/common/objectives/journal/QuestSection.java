package com.ziggfreed.common.objectives.journal;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * Where a quest sits in the journal, declared in the order the list reads: what the player can do about it, most
 * actionable first. Ready to collect, In progress and Available open by default; Not yet, Waiting and Completed
 * start closed. Each section carries its own tone, so a row's accent and state word say the same thing as its head.
 *
 * <p>The journal's sections are about the quest alone. The NPC quest page keeps its own place-aware grouping
 * ({@code questlist/NpcQuestSections}: a hand-in this character settles, a reward collected somewhere else), since
 * those questions only make sense in front of somebody.
 */
public enum QuestSection {

    /** Finished; the reward waits to be collected. */
    READY("ready", true, Tone.COLLECT),
    /** Being carried. */
    IN_PROGRESS("progress", true, Tone.ACTIVE),
    /** Not started, and the player may take it now (or at its giver). */
    AVAILABLE("available", true, Tone.AVAILABLE),
    /** Visible, but a gate refuses it. */
    NOT_YET("not_yet", false, Tone.BLOCKED),
    /** Finished, and coming back on its own: a repeat waiting out its cooldown or its calendar window. */
    WAITING("waiting", false, Tone.WAITING),
    /** Finished and collected. */
    COMPLETED("completed", false, Tone.DONE);

    /** The status filter that shows every section. */
    public static final String STATUS_ALL = "all";
    /** The status filter for what the player is carrying or has yet to collect. */
    public static final String STATUS_PROGRESS = "progress";
    /** The status filter for what can be taken. */
    public static final String STATUS_AVAILABLE = "available";
    /** The status filter for what is finished, waiting or not. */
    public static final String STATUS_DONE = "done";

    /** The four status segments, in the toolbar's order. */
    public static final String[] STATUSES = {STATUS_ALL, STATUS_PROGRESS, STATUS_AVAILABLE, STATUS_DONE};

    private final String id;
    private final boolean openByDefault;
    private final Tone tone;

    QuestSection(@Nonnull String id, boolean openByDefault, @Nonnull Tone tone) {
        this.id = id;
        this.openByDefault = openByDefault;
        this.tone = tone;
    }

    /** The section's id in the list (and in the book's open-sections state). */
    @Nonnull
    public String id() {
        return id;
    }

    /** Whether the section shows its rows before the player says otherwise. */
    public boolean openByDefault() {
        return openByDefault;
    }

    /** The section's tone: its rows' accent and state word. */
    @Nonnull
    public Tone tone() {
        return tone;
    }

    /** The section head's key in {@code ziggfreedcommon.journal.lang}, without the file's prefix. */
    @Nonnull
    public String labelKey() {
        return "section." + id;
    }

    /**
     * The section a quest belongs in, from what it effectively is for the player and whether the accept gate passes
     * now ({@code acceptable} is read only for an unstarted quest). A giver-bound quest the gate lets through is
     * Available: it is taken at its giver, but it can be taken.
     */
    @Nonnull
    public static QuestSection of(@Nonnull QuestStatus status, boolean acceptable) {
        return switch (status) {
            case COMPLETED_UNCLAIMED -> READY;
            case ACTIVE -> IN_PROGRESS;
            case NOT_STARTED -> acceptable ? AVAILABLE : NOT_YET;
            case ON_COOLDOWN -> WAITING;
            case COMPLETED -> COMPLETED;
        };
    }

    /**
     * Whether this section shows under a status filter. All shows every section; In progress, Ready and In
     * progress; Available, Available; Done, Waiting and Completed. Not yet shows only under All.
     */
    public boolean shownFor(@Nullable String status) {
        return switch (normalizeStatus(status)) {
            case STATUS_PROGRESS -> this == READY || this == IN_PROGRESS;
            case STATUS_AVAILABLE -> this == AVAILABLE;
            case STATUS_DONE -> this == WAITING || this == COMPLETED;
            default -> true;
        };
    }

    /**
     * A status filter as the journal reads it: one of {@link #STATUSES}. The pre-redesign book's ids still read
     * ({@code active} as In progress, {@code completed} as Done), and anything else reads as All.
     */
    @Nonnull
    public static String normalizeStatus(@Nullable String status) {
        if (status == null) {
            return STATUS_ALL;
        }
        String id = status.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case STATUS_PROGRESS, "active" -> STATUS_PROGRESS;
            case STATUS_AVAILABLE -> STATUS_AVAILABLE;
            case STATUS_DONE, "completed" -> STATUS_DONE;
            default -> STATUS_ALL;
        };
    }

    /** The section whose {@link #id} this is, or null. */
    @Nullable
    public static QuestSection byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (QuestSection section : values()) {
            if (section.id.equalsIgnoreCase(id.trim())) {
                return section;
            }
        }
        return null;
    }
}
