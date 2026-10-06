package com.ziggfreed.common.settings.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * What the Settings tab lists: one consumer's sections (one call, {@link #consumer}) above the library's
 * three slots ({@link SettingsSlot}), each filled by the module that owns it. Suppliers are asked on every
 * open, so a section always reflects the server's current files. Written at setup, read on the world
 * thread.
 */
public final class ZigSettings {

    private static final Map<SettingsSlot, Supplier<SettingsSection>> SLOTS = new ConcurrentHashMap<>();
    private static final AtomicReference<Supplier<List<SettingsSection>>> CONSUMER = new AtomicReference<>();

    private ZigSettings() {
    }

    /** Fill one of the library's slots. Once, at setup, by the module that owns it. */
    public static void fill(@Nonnull SettingsSlot slot, @Nonnull Supplier<SettingsSection> section) {
        SLOTS.put(slot, section);
    }

    /** Say what a consumer adds above the library's sections. Last write wins; null empties it. */
    public static void consumer(@Nullable Supplier<List<SettingsSection>> supplier) {
        CONSUMER.set(supplier);
    }

    /** The sections to draw now: the consumer's, then each filled slot in order. Guarded per section. */
    @Nonnull
    public static List<SettingsSection> sections() {
        List<SettingsSection> out = new ArrayList<>(consumerSections());
        for (SettingsSlot slot : SettingsSlot.values()) {
            Supplier<SettingsSection> supplier = SLOTS.get(slot);
            if (supplier == null) {
                continue;
            }
            try {
                SettingsSection section = supplier.get();
                if (section != null) {
                    out.add(section);
                }
            } catch (Throwable t) {
                SafeLog.warn("[settings] the " + slot + " section failed to build, so it is left out: "
                        + t.getMessage());
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<SettingsSection> consumerSections() {
        Supplier<List<SettingsSection>> supplier = CONSUMER.get();
        if (supplier == null) {
            return List.of();
        }
        try {
            List<SettingsSection> sections = supplier.get();
            return sections == null ? List.of() : sections.stream().filter(Objects::nonNull).toList();
        } catch (Throwable t) {
            SafeLog.warn("[settings] the consumer's sections failed to build, so they are left out: "
                    + t.getMessage());
            return List.of();
        }
    }

    /** Drop every slot and the consumer. Tests only. */
    public static void clearForTests() {
        SLOTS.clear();
        CONSUMER.set(null);
    }
}
