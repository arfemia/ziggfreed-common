package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One tier as a card, as a reader builds it ({@link TierCardPainter}): an id the page's binding carries; an optional
 * tag over the title ("Today's daily"); the title ("Depth 2"); an optional copper line under it ("Inside now: Ana.");
 * an optional line of what it meets this week; the head over what it pays ("Pays", null for none) and the pay slots
 * themselves ({@link PaySlot}, the first {@link TierCardPainter#PAYS_SHOWN} drawn); an optional note under them
 * ("Sometimes a gem"); its pay mark ({@link PayMark}: what the next clear pays, null for none); the go-in plate's word
 * ("Go in", null for a card shown only to be read, a week board's); its tooltip (the pay line in words, the card's own,
 * since nothing inside a card that is one button carries one); and {@code lock}, null for an open card, else what keeps
 * it shut. A locked card draws its title in the dim ink, dims its pay pictures, shows a padlock over the lock's lines
 * in place of its mark and plate, carries no tooltip, and is switched off, so it takes no click.
 */
public record TierCard(@Nonnull String id, @Nullable Message tag, @Nonnull Message title, @Nullable Message sub,
        @Nullable Message week, @Nullable Message paysHead, @Nonnull List<PaySlot> pays, @Nullable Message payNote,
        @Nullable PayMark mark, @Nullable Message go, @Nullable Message tooltip, @Nullable Lock lock) {

    public TierCard {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        pays = pays == null ? List.of() : List.copyOf(pays);
    }

    /** What keeps a card shut: a line saying so, and an optional line saying how it opens. */
    public record Lock(@Nonnull Message line, @Nullable Message how) {

        public Lock {
            Objects.requireNonNull(line, "line");
        }
    }

    /** True for a locked card. */
    public boolean locked() {
        return lock != null;
    }
}
