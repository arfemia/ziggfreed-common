package com.ziggfreed.common.owner;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Every owner file an in-game editor may write, by id ({@link OwnerFileBinding}): the mod that reads a file
 * registers its binding once at setup, and an owner form names the id in its {@code File}. Ids match without regard
 * to case; registering an id again replaces the binding. A form naming an id nobody registered writes nothing.
 */
public final class OwnerFileBindings {

    private static final Map<String, OwnerFileBinding> BINDINGS = new ConcurrentHashMap<>();

    private OwnerFileBindings() {
    }

    /** Register (or replace) {@code binding} under its id. */
    public static void register(@Nonnull OwnerFileBinding binding) {
        BINDINGS.put(key(binding.id()), binding);
    }

    /** The binding {@code id} names, or null when no installed mod registered one. */
    @Nullable
    public static OwnerFileBinding find(@Nullable String id) {
        return id == null || id.isBlank() ? null : BINDINGS.get(key(id));
    }

    /** Every registered id, sorted. */
    @Nonnull
    public static List<String> ids() {
        return BINDINGS.values().stream().map(OwnerFileBinding::id).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Forget every binding. Tests only. */
    public static void clearForTests() {
        BINDINGS.clear();
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
