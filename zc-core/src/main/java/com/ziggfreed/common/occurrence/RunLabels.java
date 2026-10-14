package com.ziggfreed.common.occurrence;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.OwnedSources;

/**
 * How a run of a recurring event is labelled where content names it, such as a keepsake named for the run it
 * was won in. A run is (event, year, number), and the event's own kind decides its label: a kind whose runs are
 * better told apart some other way (by date, say) registers a {@link Labeler} that answers for its own events,
 * and every other run is labelled by the year it starts in, which is how a yearly window of dates tells its runs
 * apart. That default is this class's own, so the seam answers on a server where nothing registered.
 *
 * <p>Labelers are held by owner ({@link OwnedSources}: an owner matched without regard to case, registering again
 * replaces, asked in owner id order); the first non-blank answer wins, and a labeler that throws answers nothing
 * and is reported once. A label is raw text, never a translation key: it fills a reward parameter as written, so
 * a labeler writes what every language reads, such as a year or a date in digits.
 */
public final class RunLabels {

    /** One event kind's way of labelling its runs. */
    @FunctionalInterface
    public interface Labeler {

        /**
         * The label of run {@code number} of {@code year} of {@code eventId} (the id as content names it, so
         * compare it without regard to case), or null when this labeler does not label that event's runs.
         */
        @Nullable
        String label(@Nonnull String eventId, int year, int number);
    }

    private static volatile OwnedSources<Labeler> labelers = new OwnedSources<>();

    private RunLabels() {
    }

    /** Register {@code labeler} under {@code owner}, replacing any labeler that owner registered. */
    public static void register(@Nonnull String owner, @Nonnull Labeler labeler) {
        labelers.register(owner, labeler);
    }

    /** Remove {@code owner}'s labeler. A no-op when it has none. */
    public static void unregister(@Nonnull String owner) {
        labelers.unregister(owner);
    }

    /**
     * The label of run {@code number} of {@code year} of {@code eventId}: the first registered labeler's non-blank
     * answer in owner id order, trimmed, else the year the run starts in.
     */
    @Nonnull
    public static String of(@Nonnull String eventId, int year, int number) {
        OwnedSources<Labeler> held = labelers;
        if (!held.isEmpty()) {
            List<String> answers = held.collect(labeler -> {
                String label = labeler.label(eventId, year, number);
                return label == null || label.isBlank() ? null : List.of(label.trim());
            }, owner -> "the run labeler of '" + owner + "'");
            if (!answers.isEmpty()) {
                return answers.get(0);
            }
        }
        return String.valueOf(year);
    }

    /** Forget every labeler; for a test starting from nothing. */
    public static void resetForTests() {
        labelers = new OwnedSources<>();
    }
}
