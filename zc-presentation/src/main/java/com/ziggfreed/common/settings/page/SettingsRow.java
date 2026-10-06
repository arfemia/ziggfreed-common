package com.ziggfreed.common.settings.page;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One row of the Settings tab, as data the page renders and a test reads: a sub-heading, a switch, a
 * dropdown, or a tile that opens another screen. Every row has an id (unique within its section), the
 * words a player reads, and a rule saying whether it shows for this viewer, asked when the page is built
 * and again after every change, since one choice can hide another (a child switch under its parent).
 *
 * <p>Switches and dropdowns act in place: their {@code set} keeps the choice and answers true when it is
 * kept (or already so), false when it could not be kept. Only a tile opens another screen. A rule, a
 * reading or a write that throws costs its own row, never the page.
 */
public final class SettingsRow {

    public enum Kind { HEADING, TOGGLE, CHOICE, TILE }

    /** A switch's state and how to keep a new one. */
    public interface Toggle {

        boolean on(@Nonnull SettingsViewer viewer);

        boolean set(@Nonnull SettingsViewer viewer, boolean on);
    }

    /** A dropdown's entries, its value and how to keep a new one. */
    public interface Choice {

        @Nonnull
        List<SettingsOption> options(@Nonnull SettingsViewer viewer);

        @Nonnull
        String value(@Nonnull SettingsViewer viewer);

        boolean set(@Nonnull SettingsViewer viewer, @Nonnull String value);
    }

    /** A tile's one line under its title, and the screen it opens (true when that screen took over). */
    public interface Tile {

        @Nonnull
        Message line(@Nonnull SettingsViewer viewer);

        boolean open(@Nonnull SettingsViewer viewer);
    }

    @Nonnull private final String id;
    @Nonnull private final Kind kind;
    @Nonnull private final Message label;
    @Nullable private final Message hint;
    @Nonnull private final Predicate<SettingsViewer> visible;
    @Nullable private final Toggle toggle;
    @Nullable private final Choice choice;
    @Nullable private final Tile tile;
    @Nullable private final String icon;

    private SettingsRow(@Nonnull String id, @Nonnull Kind kind, @Nonnull Message label, @Nullable Message hint,
            @Nonnull Predicate<SettingsViewer> visible, @Nullable Toggle toggle, @Nullable Choice choice,
            @Nullable Tile tile, @Nullable String icon) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a settings row needs an id");
        }
        this.id = id;
        this.kind = kind;
        this.label = Objects.requireNonNull(label, "label");
        this.hint = hint;
        this.visible = Objects.requireNonNull(visible, "visible");
        this.toggle = toggle;
        this.choice = choice;
        this.tile = tile;
        this.icon = icon;
    }

    /** A sub-heading inside a section's card. */
    @Nonnull
    public static SettingsRow heading(@Nonnull String id, @Nonnull Message label,
            @Nonnull Predicate<SettingsViewer> visible) {
        return new SettingsRow(id, Kind.HEADING, label, null, visible, null, null, null, null);
    }

    /** A switch, kept the moment it is pressed. */
    @Nonnull
    public static SettingsRow toggle(@Nonnull String id, @Nonnull Message label, @Nullable Message hint,
            @Nonnull Predicate<SettingsViewer> visible, @Nonnull Toggle toggle) {
        return new SettingsRow(id, Kind.TOGGLE, label, hint, visible,
                Objects.requireNonNull(toggle, "toggle"), null, null, null);
    }

    /** A dropdown, kept the moment it changes. */
    @Nonnull
    public static SettingsRow choice(@Nonnull String id, @Nonnull Message label, @Nullable Message hint,
            @Nonnull Predicate<SettingsViewer> visible, @Nonnull Choice choice) {
        return new SettingsRow(id, Kind.CHOICE, label, hint, visible, null,
                Objects.requireNonNull(choice, "choice"), null, null);
    }

    /** A tile opening another screen: a picture (an item id, or null for none), a title and one line. */
    @Nonnull
    public static SettingsRow tile(@Nonnull String id, @Nonnull Message title, @Nullable String iconItemId,
            @Nonnull Predicate<SettingsViewer> visible, @Nonnull Tile tile) {
        return new SettingsRow(id, Kind.TILE, title, null, visible, null, null,
                Objects.requireNonNull(tile, "tile"), iconItemId);
    }

    @Nonnull
    public String id() {
        return id;
    }

    @Nonnull
    public Kind kind() {
        return kind;
    }

    /** The row's words: a heading's text, a switch's or dropdown's label, a tile's title. */
    @Nonnull
    public Message label() {
        return label;
    }

    @Nullable
    public Message hint() {
        return hint;
    }

    @Nonnull
    public Predicate<SettingsViewer> visible() {
        return visible;
    }

    @Nullable
    public Toggle toggle() {
        return toggle;
    }

    @Nullable
    public Choice choice() {
        return choice;
    }

    @Nullable
    public Tile tile() {
        return tile;
    }

    /** A tile's picture, as an item id, or null. */
    @Nullable
    public String icon() {
        return icon;
    }
}
