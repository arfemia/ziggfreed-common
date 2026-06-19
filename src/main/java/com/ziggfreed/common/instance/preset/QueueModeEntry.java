package com.ziggfreed.common.instance.preset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The resolved, normalized form of ONE authored queue mode (a card on the
 * {@code PlayModePage}): whether it is offered, the item-glyph icon, the sort order, and
 * an optional label-key override. Built by {@link QueueModeSet} with the documented
 * defaults baked in, so the page never sees an unauthored/null field.
 *
 * @param mode       the fixed mode identity (drives launch behaviour)
 * @param enabled    whether this mode's card is shown for the preset
 * @param iconItemId the bare PascalCase item id rendered as the card glyph (an {@code ItemGrid} slot)
 * @param order      the left-to-right sort order among enabled cards (ascending)
 * @param labelKey   an optional lang-key override for the card label; {@code null} -> the consumer's default label
 */
public record QueueModeEntry(@Nonnull QueueModeId mode, boolean enabled, @Nonnull String iconItemId,
                             int order, @Nullable String labelKey) {
}
