package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * What a paint sent, read the way the client applies it: the last write per selector wins, appends and clears in
 * order, and each binding by its selector (every binding kept, so a double binding shows).
 */
record Painted(Map<String, String> sets, List<String> appends, List<String> clears,
        Map<String, List<String>> bindings) {

    static Painted of(@Nonnull UICommandBuilder cmd) {
        return of(cmd, new UIEventBuilder());
    }

    static Painted of(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        Map<String, String> sets = new HashMap<>();
        List<String> appends = new ArrayList<>();
        List<String> clears = new ArrayList<>();
        for (CustomUICommand command : cmd.getCommands()) {
            if (command.type == CustomUICommandType.Set) {
                sets.put(command.selector, command.data);
            } else if (command.type == CustomUICommandType.Append) {
                appends.add(command.selector + " <- " + command.text);
            } else if (command.type == CustomUICommandType.Clear) {
                clears.add(command.selector);
            }
        }
        Map<String, List<String>> bindings = new HashMap<>();
        for (CustomUIEventBinding binding : events.getEvents()) {
            bindings.computeIfAbsent(binding.selector, s -> new ArrayList<>()).add(binding.data);
        }
        return new Painted(sets, appends, clears, bindings);
    }

    /** The value last written to {@code selector}; fails when it was never written. */
    @Nonnull
    String set(@Nonnull String selector) {
        String data = sets.get(selector);
        assertNotNull(data, selector + " is painted");
        return data;
    }

    boolean has(@Nonnull String selector) {
        return sets.containsKey(selector);
    }

    /** Whether a {@code .Visible} write said true. */
    boolean shown(@Nonnull String selector) {
        return set(selector).contains("true");
    }

    /** Whether the style written to {@code selector} is the reference to {@code name} in {@code document}. */
    boolean references(@Nonnull String selector, @Nonnull String document, @Nonnull String name) {
        String data = set(selector);
        return data.contains("\"$Document\"") && data.contains("\"" + document + "\"")
                && data.contains("\"@Value\"") && data.contains("\"" + name + "\"");
    }

    /** The one binding on {@code selector}, or null; fails when it was bound twice. */
    String binding(@Nonnull String selector) {
        List<String> all = bindings.get(selector);
        if (all == null) {
            return null;
        }
        if (all.size() != 1) {
            throw new AssertionError(selector + " is bound " + all.size() + " times");
        }
        return all.get(0);
    }

    int bindingCount() {
        int n = 0;
        for (List<String> all : bindings.values()) {
            n += all.size();
        }
        return n;
    }
}
