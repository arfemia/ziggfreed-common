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
 * ({@code null} for the library's "Confirm"); and the sound and particle system played where the player stands when
 * they confirm and it goes through ({@code null} for none). Plain values: the caller counts what the player holds, and
 * the page only shows it and asks.
 */
public record ItemCostConfirm(@Nonnull Message title, @Nullable Message description, @Nonnull List<Row> rows,
        @Nullable Message hint, @Nullable String confirmKey, @Nullable String sound, @Nullable String particle) {

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
    }

    /** Can the player pay all of it: no row is short. */
    public boolean affordable() {
        for (Row row : rows) {
            if (row.isShort()) {
                return false;
            }
        }
        return true;
    }
}
