package com.ziggfreed.common.ui.confirm;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * What an item-cost confirm page shows ({@link ItemCostConfirmPage}): the thing being paid for, by its title and an
 * optional description; each item it takes, with how many the player holds and how many it needs; a hint read only
 * while the player holds too few (where the items are found); the words on its button, as an authored content key
 * ({@code null} for the library's "Confirm"); the sound and particle system played where the player stands when
 * they confirm and it goes through ({@code null} for none); and an optional key, the id of an item the player holds
 * that pays it all without being used up ({@code null} for none). Plain values: the caller counts what the player
 * holds and decides whether a key pays, and the page only shows it and asks.
 *
 * <p>With a key, the page shows that key's slot alone (held, one of one) and a line saying it is not used up, in place
 * of the rows and the line saying what the button takes; the button is on and the hint stays hidden.
 */
public record ItemCostConfirm(@Nonnull Message title, @Nullable Message description, @Nonnull List<Row> rows,
        @Nullable Message hint, @Nullable String confirmKey, @Nullable String sound, @Nullable String particle,
        @Nullable String key) {

    /** One item the page asks for: its id, how many the player holds, and how many it takes (at least one). */
    public record Row(@Nonnull String itemId, int held, int needed) {

        public Row {
            Objects.requireNonNull(itemId, "itemId");
            held = Math.max(0, held);
            needed = Math.max(1, needed);
        }

        /** Does the player hold too few of it? */
        public boolean isShort() {
            return held < needed;
        }
    }

    public ItemCostConfirm {
        Objects.requireNonNull(title, "title");
        rows = rows == null ? List.of() : List.copyOf(rows);
        key = key == null || key.isBlank() ? null : key.trim();
    }

    /** A page with no key: the player pays the rows. */
    public ItemCostConfirm(@Nonnull Message title, @Nullable Message description, @Nonnull List<Row> rows,
            @Nullable Message hint, @Nullable String confirmKey, @Nullable String sound, @Nullable String particle) {
        this(title, description, rows, hint, confirmKey, sound, particle, null);
    }

    /** Can the player go ahead: they hold the key, or no row is short. */
    public boolean affordable() {
        if (key != null) {
            return true;
        }
        for (Row row : rows) {
            if (row.isShort()) {
                return false;
            }
        }
        return true;
    }
}
