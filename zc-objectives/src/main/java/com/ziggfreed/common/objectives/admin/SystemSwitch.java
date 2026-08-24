package com.ziggfreed.common.objectives.admin;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.progress.runtime.ProgressionSystem;

/**
 * One SERVER-WIDE progression-system switch a consumer registers so the admin page can show and
 * flip it: which system it belongs to, what it is called, how to read its current value, and -
 * when the consumer allows it - how to write one.
 *
 * <p>The library owns no consumer namespace, so the {@code label} (and the optional {@code hint})
 * arrive as pre-built, client-resolved {@link Message}s the consumer keyed in its own lang family.
 * The {@code read} answers the server-wide value, never a per-player one - a per-player refusal is
 * a {@link com.ziggfreed.common.progress.runtime.ProgressionSystemGate} contribution, and this
 * switch is typically what such a gate reads.
 *
 * <p>A switch with a null {@link #write} is a READ-ONLY row: the page shows its label and live
 * state, paints the toggle locked, and says the value is governed elsewhere - for a consumer whose
 * switch lives in a config file its own reload owns.
 *
 * @param id     owner-namespaced (e.g. {@code "mmoskilltree:quests"}); the registry key
 * @param system which of the runtime's two peer systems this switch governs
 * @param label  what the row is called, pre-built and client-resolved
 * @param hint   an optional row sub-label; null hides the line
 * @param read   the switch's current SERVER-WIDE value
 * @param write  how to set it, or null for a read-only row
 * @param order  render order, ascending; ties broken by id
 */
public record SystemSwitch(@Nonnull String id, @Nonnull ProgressionSystem system,
                           @Nonnull Message label, @Nullable Message hint,
                           @Nonnull BooleanSupplier read, @Nullable Writer write, int order) {

    /** How a writable switch is set. Absent (null on the record) means the row is read-only. */
    @FunctionalInterface
    public interface Writer {

        void set(boolean enabled);
    }

    public SystemSwitch {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a system switch needs a non-blank id");
        }
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(read, "read");
    }

    /** Whether this row shows its state without offering to change it. */
    public boolean readOnly() {
        return write == null;
    }
}
