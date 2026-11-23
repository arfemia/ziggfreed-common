package com.ziggfreed.common.ui.owner;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.util.SafeLog;

/**
 * One tile of the owner menu ({@link OwnerMenu}): a screen it opens, or an action it takes in place (a reload) that
 * answers a status line on the menu. It sits in its owner's section, by {@code order} then id, shows its title, a line
 * and an item, and shows only to an owner its {@link #shownWhen} rule lets see it (an entitlement, a mod's own switch).
 * Immutable: each setter answers a copy.
 *
 * <p>The menu asks the owner audience before it asks a tile anything, on every press too, so a tile's own rule only
 * narrows who sees it; an opener that opens a page with its own audience (a library owner page) is still refused by
 * that page's seam. Every call into a tile is guarded: a rule that throws hides the tile, a press that throws
 * answers a failed status.
 */
public final class OwnerTile {

    /** A screen the tile opens: true when one went up (the menu then answers nothing more). */
    @FunctionalInterface
    public interface Opener {

        boolean open(@Nonnull OwnerViewer viewer);
    }

    /** Something the tile does in place: the status line the menu then shows, or null for none. */
    @FunctionalInterface
    public interface Action {

        @Nullable
        Message act(@Nonnull OwnerViewer viewer);
    }

    /**
     * What a press did: opened a screen; acted in place, with a status line or none (an error or not); or nothing
     * (a screen that did not open).
     */
    public record Press(boolean opened, boolean acted, @Nullable Message status, boolean error) {

        static final Press OPENED = new Press(true, false, null, false);

        static final Press NOTHING = new Press(false, false, null, false);
    }

    @Nonnull private final String owner;
    @Nonnull private final String id;
    @Nonnull private final Message title;
    private final int order;
    @Nullable private final Message line;
    @Nullable private final String icon;
    @Nonnull private final Predicate<OwnerViewer> shown;
    @Nullable private final Opener opener;
    @Nullable private final Action action;
    @Nonnull private final List<OwnerHelp> help;

    private OwnerTile(@Nonnull String owner, @Nonnull String id, @Nonnull Message title, int order,
            @Nullable Message line, @Nullable String icon, @Nonnull Predicate<OwnerViewer> shown,
            @Nullable Opener opener, @Nullable Action action, @Nonnull List<OwnerHelp> help) {
        this.owner = Objects.requireNonNull(owner, "owner").trim();
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = Objects.requireNonNull(title, "title");
        this.order = order;
        this.line = line;
        this.icon = icon == null || icon.isBlank() ? null : icon.trim();
        this.shown = Objects.requireNonNull(shown, "shown");
        this.opener = opener;
        this.action = action;
        this.help = List.copyOf(help);
        if (this.owner.isEmpty() || this.id.isEmpty()) {
            throw new IllegalArgumentException("an owner tile needs an owner and an id");
        }
    }

    /**
     * A tile in {@code owner}'s section that opens a screen.
     *
     * @param id a stable id, unique on the server (owner-prefixed, {@code yourmod:settings}); adding the same id
     *           again replaces the tile
     */
    @Nonnull
    public static OwnerTile screen(@Nonnull String owner, @Nonnull String id, @Nonnull Message title,
            @Nonnull Opener opener) {
        return new OwnerTile(owner, id, title, 100, null, null, viewer -> true, Objects.requireNonNull(opener), null,
                List.of());
    }

    /** A tile in {@code owner}'s section that acts in place and answers a status line. */
    @Nonnull
    public static OwnerTile action(@Nonnull String owner, @Nonnull String id, @Nonnull Message title,
            @Nonnull Action action) {
        return new OwnerTile(owner, id, title, 100, null, null, viewer -> true, null, Objects.requireNonNull(action),
                List.of());
    }

    /** Its place in the section, lowest first (100 unless set); a tie goes by id. */
    @Nonnull
    public OwnerTile order(int value) {
        return new OwnerTile(owner, id, title, value, line, icon, shown, opener, action, help);
    }

    /** The line under its title. */
    @Nonnull
    public OwnerTile line(@Nullable Message value) {
        return new OwnerTile(owner, id, title, order, value, icon, shown, opener, action, help);
    }

    /** The item drawn on it. */
    @Nonnull
    public OwnerTile icon(@Nullable String itemId) {
        return new OwnerTile(owner, id, title, order, line, itemId, shown, opener, action, help);
    }

    /** Who sees it, past the owner audience (asked at every paint and every press). */
    @Nonnull
    public OwnerTile shownWhen(@Nonnull Predicate<OwnerViewer> rule) {
        return new OwnerTile(owner, id, title, order, line, icon, rule, opener, action, help);
    }

    /**
     * The help topics it carries, in order (replacing any it had): the menu's Help lists it, and pressing it there shows
     * them over a button that opens its screen. None for a tile with no help.
     */
    @Nonnull
    public OwnerTile help(@Nonnull OwnerHelp... topics) {
        return new OwnerTile(owner, id, title, order, line, icon, shown, opener, action, List.of(topics));
    }

    @Nonnull
    public String owner() {
        return owner;
    }

    @Nonnull
    public String id() {
        return id;
    }

    @Nonnull
    public Message title() {
        return title;
    }

    public int order() {
        return order;
    }

    @Nullable
    public Message line() {
        return line;
    }

    @Nullable
    public String icon() {
        return icon;
    }

    /** The help topics it carries itself ({@link OwnerMenu#topics} adds those another mod gave it). */
    @Nonnull
    public List<OwnerHelp> help() {
        return help;
    }

    /** Does it open a screen (rather than act in place)? */
    public boolean opens() {
        return opener != null;
    }

    /** Does its rule let {@code viewer} see it? A rule that throws hides it. */
    public boolean shownTo(@Nonnull OwnerViewer viewer) {
        try {
            return shown.test(viewer);
        } catch (Throwable t) {
            SafeLog.warn("[owner] the tile '" + id + "' could not say whether it shows, so it is hidden: "
                    + t.getMessage());
            return false;
        }
    }

    /** Press it for {@code viewer}: open its screen or take its action. Guarded. */
    @Nonnull
    public Press press(@Nonnull OwnerViewer viewer) {
        try {
            if (opener != null) {
                return opener.open(viewer) ? Press.OPENED : Press.NOTHING;
            }
            Message status = action == null ? null : action.act(viewer);
            return new Press(false, true, status, false);
        } catch (Throwable t) {
            SafeLog.warn("[owner] the tile '" + id + "' failed when pressed", t);
            return new Press(false, true, OwnerText.line("menu.failed"), true);
        }
    }
}
