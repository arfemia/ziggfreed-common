package com.ziggfreed.common.ui.owner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.ui.form.OwnerFormAsset;
import com.ziggfreed.common.ui.form.OwnerFormConfig;
import com.ziggfreed.common.ui.form.OwnerForms;
import com.ziggfreed.common.util.SafeLog;

/**
 * The owner menu ({@code /zigadmin}): one screen of tiles for every owner screen on the server, every mod's, in one
 * section per mod. A mod opens its section once ({@link #section}) and adds its tiles ({@link #add}: a screen it opens,
 * or an action it takes in place); every owner form ({@code Server/ZiggfreedCommon/OwnerForms/}) is a tile of its own
 * with no code. A consumer that keeps a command of its own opens the same menu with its section first
 * ({@link #open} naming it), so there is one menu, never a second copy.
 *
 * <p>Who sees it is {@link OwnerAudience}: asked when it opens and at every press. The menu is deliberately not a
 * registered destination, since every tile opens an owner screen and none may be pack-addressable. Sections show in
 * their own order (the library's first, then each mod's by the order it opened with), the one named first moved to the
 * front, and tiles from a section nobody opened gather under More. A section with no tile showing is not drawn.
 */
public final class OwnerMenu {

    /** The library's own section: CommonLib's owner screens and forms. */
    public static final String LIBRARY = OwnerFormAsset.DEFAULT_SECTION;

    /** A section's place when it is opened at none. */
    public static final int DEFAULT_SECTION_ORDER = 100;

    /** A section a mod opened: whose it is, its heading, its place. */
    private record SectionDef(@Nonnull String owner, @Nonnull Message heading, int order) {
    }

    /** One drawn section of the menu: whose it is, its heading, and its tiles in order. */
    public record Section(@Nonnull String owner, @Nonnull Message heading, @Nonnull List<OwnerTile> tiles) {

        public Section {
            tiles = List.copyOf(tiles);
        }
    }

    private static final Map<String, SectionDef> SECTIONS = new ConcurrentHashMap<>();
    private static final Map<String, OwnerTile> TILES = new ConcurrentHashMap<>();

    private static final String MORE = "\u0000more";

    private OwnerMenu() {
    }

    /** Open (or rename) {@code owner}'s section: its heading and its place among the sections, lowest first. */
    public static void section(@Nonnull String owner, @Nonnull Message heading, int order) {
        SECTIONS.put(key(owner), new SectionDef(owner.trim(), heading, order));
    }

    /** Add a tile to its owner's section; a tile of the same id is replaced. */
    public static void add(@Nonnull OwnerTile tile) {
        TILES.put(key(tile.id()), tile);
    }

    /** Take the tile {@code id} out. */
    public static void remove(@Nonnull String id) {
        TILES.remove(key(id));
    }

    /**
     * Open the menu for {@code player} with {@code first}'s section at the front (null for the usual order). False when
     * the entity is no player, the owner audience refuses, or the open threw; the caller still owes the client an
     * answer then.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nullable String first) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null || !OwnerAudience.mayAdminister(store, ref, player)) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new OwnerMenuPage(playerRef, first));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner menu failed to open", t);
            return false;
        }
    }

    /**
     * What the menu shows {@code viewer}: each section with a tile showing, the one {@code first} names at the front,
     * every tile in order (its rule asked of the viewer), the forms among them.
     */
    @Nonnull
    public static List<Section> plan(@Nonnull OwnerViewer viewer, @Nullable String first) {
        Map<String, List<OwnerTile>> byOwner = new LinkedHashMap<>();
        for (OwnerTile tile : tiles(first)) {
            if (tile.shownTo(viewer)) {
                String owner = SECTIONS.containsKey(key(tile.owner())) ? key(tile.owner()) : MORE;
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(tile);
            }
        }
        List<SectionDef> defs = new ArrayList<>(SECTIONS.values());
        defs.sort(Comparator.comparingInt(SectionDef::order)
                .thenComparing(SectionDef::owner, String.CASE_INSENSITIVE_ORDER));
        String front = first == null ? null : key(first);
        List<Section> out = new ArrayList<>();
        for (SectionDef def : defs) {
            List<OwnerTile> tiles = byOwner.get(key(def.owner()));
            if (tiles == null || tiles.isEmpty()) {
                continue;
            }
            Section section = new Section(def.owner(), def.heading(), sorted(tiles));
            if (key(def.owner()).equals(front)) {
                out.add(0, section);
            } else {
                out.add(section);
            }
        }
        List<OwnerTile> more = byOwner.get(MORE);
        if (more != null && !more.isEmpty()) {
            out.add(new Section(MORE, OwnerText.line("menu.section.more"), sorted(more)));
        }
        return out;
    }

    /** Every tile: the ones added in code, then one per owner form, each form opening with {@code first} kept for Back. */
    @Nonnull
    private static List<OwnerTile> tiles(@Nullable String first) {
        List<OwnerTile> out = new ArrayList<>(TILES.values());
        for (OwnerFormAsset form : OwnerFormConfig.getInstance().forms()) {
            String formId = form.getId();
            String title = form.title();
            OwnerTile tile = OwnerTile.screen(form.section(), "form:" + formId,
                            title == null ? OwnerText.line("form.untitled", formId) : ContentKeys.tr(title),
                            viewer -> OwnerForms.open(viewer, formId, first))
                    .order(form.order())
                    .icon(form.icon())
                    .shownWhen(viewer -> OwnerForms.shownTo(form, viewer));
            String description = form.description();
            out.add(description == null ? tile : tile.line(ContentKeys.tr(description)));
        }
        return out;
    }

    @Nonnull
    private static List<OwnerTile> sorted(@Nonnull List<OwnerTile> tiles) {
        List<OwnerTile> out = new ArrayList<>(tiles);
        out.sort(Comparator.comparingInt((OwnerTile tile) -> tile.order())
                .thenComparing(OwnerTile::id, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Forget every section and tile. Tests only. */
    public static void clearForTests() {
        SECTIONS.clear();
        TILES.clear();
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
