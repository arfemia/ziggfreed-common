package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.i18n.Msg;

/**
 * What a wallet is CALLED and what it looks like, in the player's own language.
 *
 * <p>A three-rung ladder, and each rung is there because the one above it can be absent on a real
 * server:
 *
 * <ol>
 *   <li>a consumer's {@link Source}, for a mod that names its currencies somewhere of its own;</li>
 *   <li>the currency's authored {@code NameKey}, which is one file saying it once for every
 *       locale;</li>
 *   <li>the BACKING ITEM's own engine display name, which an item-backed wallet already has in
 *       every language without anybody writing a key.</li>
 * </ol>
 *
 * <p>The raw id is the last resort and reads as itself. That is deliberate rather than a gap: an
 * untranslated word a player can read beats a blank, and a counter-backed wallet with no name key is
 * an authoring omission the id makes visible instead of hiding.
 */
public final class CurrencyText {

    /** A consumer's own name for a wallet; null falls through to the authored ladder. */
    @FunctionalInterface
    public interface Source {

        @Nullable
        Message nameFor(@Nonnull CurrencyDef def);
    }

    /** No consumer opinion, so every wallet reads by its own file. */
    public static final Source AUTHORED_ONLY = def -> null;

    private CurrencyText() {
    }

    /** What this wallet is called, guarded: a consumer's naming failing costs the word, not the row. */
    @Nonnull
    public static Message nameOf(@Nonnull CurrencyDef def, @Nullable Source source) {
        if (source != null) {
            try {
                Message named = source.nameFor(def);
                if (named != null) {
                    return named;
                }
            } catch (Throwable ignored) {
                // Fall through to what the file itself says.
            }
        }
        String key = CommerceText.trimToNull(def.nameKey());
        if (key != null) {
            return Msg.key(key);
        }
        String item = CommerceText.trimToNull(def.backingItemId());
        if (item != null) {
            try {
                return new ItemStack(item, 1).getDisplayName();
            } catch (Throwable ignored) {
                // An id nothing answers to: the raw word below is still readable.
            }
        }
        return Msg.raw(def.id());
    }

    /** The picture beside a balance or a price, or null when this wallet has none. */
    @Nullable
    public static String iconOf(@Nonnull CurrencyDef def) {
        return CommerceText.trimToNull(def.iconItemId());
    }
}
