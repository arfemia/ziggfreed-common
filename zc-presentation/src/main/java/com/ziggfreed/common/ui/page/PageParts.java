package com.ziggfreed.common.ui.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.ziggfreed.common.util.SafeLog;

/**
 * The parts one composed page lays out, by the page's name ({@code PageParts.of("Almanac")}): the part
 * registry. A module registers its parts on a page in its {@code *Bootstrap}, at setup, before any page file
 * decodes; the page's {@code Sections} codec ({@link #sectionCodec}) reads each entry against the parts
 * registered when the file decodes, so a part another module brings needs no edge from the page's module to
 * it. Registration order is the order that picks an entry's drawn part when it names two.
 *
 * <p>Registering a part again under a name already taken replaces it in place (its place in the order kept);
 * the same part again changes nothing, so a test may register a page's parts as often as it likes.
 */
public final class PageParts {

    private static final Map<String, PageParts> PAGES = new ConcurrentHashMap<>();

    private final String page;
    private volatile Map<String, PagePart<?, ?>> parts = Map.of();

    private PageParts(@Nonnull String page) {
        this.page = page;
    }

    /** {@code page}'s parts, its set made on first ask. */
    @Nonnull
    public static PageParts of(@Nonnull String page) {
        return PAGES.computeIfAbsent(page, PageParts::new);
    }

    /** The page's name. */
    @Nonnull
    public String page() {
        return page;
    }

    /** Add {@code part} to the page, after every part already registered (or in place of one of its name). */
    public synchronized void register(@Nonnull PagePart<?, ?> part) {
        PagePart<?, ?> before = parts.get(part.name());
        if (before == part) {
            return;
        }
        if (before != null) {
            SafeLog.warn("[page] the " + page + " page's part '" + part.name() + "' is registered again; the later "
                    + "one reads and draws it");
        }
        Map<String, PagePart<?, ?>> next = new LinkedHashMap<>(parts);
        next.put(part.name(), part);
        parts = next;
    }

    /** Every registered part, in registration order. */
    @Nonnull
    public List<PagePart<?, ?>> parts() {
        return List.copyOf(parts.values());
    }

    /** The part named {@code name} (as written: a group name matches exactly, as a codec key does), or null. */
    @Nullable
    public PagePart<?, ?> part(@Nonnull String name) {
        return parts.get(name);
    }

    /** The names of every registered part, in registration order. */
    @Nonnull
    public List<String> names() {
        return new ArrayList<>(parts.keySet());
    }

    /**
     * The codec of one entry of this page's {@code Sections}, decoding into {@code factory}'s sections: each
     * group named for a registered part read with that part's codec, any other name kept for the page's audit
     * and its value skipped, {@code $} keys skipped as everywhere. The Asset Editor is offered every part
     * registered when the schema is exported, titled with {@code type}'s name.
     */
    @Nonnull
    public <S extends PageSection> Codec<S> sectionCodec(@Nonnull Class<S> type, @Nonnull Supplier<S> factory) {
        return new PageSectionCodec<>(this, type, factory);
    }
}
