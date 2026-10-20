package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.page.PagePart;
import com.ziggfreed.common.ui.page.PageParts;
import com.ziggfreed.common.ui.page.PageSection;
import com.ziggfreed.common.ui.page.PartCanvas;

/**
 * The trainer page's parts on the page kit ({@code PageParts.of("Trainer")}): each kind of offer is one part, the
 * group a trainer block's {@code Sections} writes for it, and the {@link TrainerSource} that says what it lists. The
 * library registers {@link #LEARN}, {@link #QUESTS} and {@link #SERVICES} ({@link #registerBuiltIns}); a mod adds a
 * kind of its own with {@link #register}, a part made by {@link #part} and its source, from its setup before any
 * placement decodes. There is never a list of kinds to edit.
 *
 * <p>A part made by {@link #part} draws the library's way: its heading and one kit list row per entry, in
 * {@code Pages/ZigTrainerSection.ui}, the entry selected reading on the page's right.
 */
public final class TrainerParts {

    /** The trainer page's name on the part registry (the trainer block's {@code Sections} read against it). */
    public static final String PAGE = NpcPlacementAsset.Trainer.PAGE;

    /** The template a part made by {@link #part} appends into the page's {@code #Sections}. */
    public static final String TEMPLATE = "Pages/ZigTrainerSection.ui";

    /** The picks this trainer teaches. */
    public static final String LEARN = "Learn";

    /** The quests this character gives. */
    public static final String QUESTS = "Quests";

    /** A respec of each track it teaches, and the services mods add ({@link TrainerServices}). */
    public static final String SERVICES = "Services";

    /** The event a row's click carries, with the part's index on the page and the row's id. */
    static final String SELECT = "select";

    private static final Map<String, TrainerSource> SOURCES = new ConcurrentHashMap<>();

    private TrainerParts() {
    }

    /** A part named {@code name} with no knobs of its own, drawn the library's way. */
    @Nonnull
    public static PagePart<TrainerPartAsset, TrainerSection> part(@Nonnull String name,
            @Nonnull String documentation) {
        return part(name, TrainerPartAsset.CODEC, documentation);
    }

    /**
     * A part named {@code name} whose group {@code codec} reads (its source finds it on the {@code Sections} entry it
     * is handed), drawn the library's way. The name is a codec key, so it starts upper-case.
     */
    @Nonnull
    public static <A> PagePart<A, TrainerSection> part(@Nonnull String name, @Nonnull Codec<A> codec,
            @Nonnull String documentation) {
        return PagePart.named(name, codec).documentation(documentation)
                .paints(TrainerSection.class, TEMPLATE, TrainerParts::paint);
    }

    /**
     * Add {@code part} to the trainer page, listing what {@code source} offers. Registering a name again replaces it
     * in place. Call at setup, before any placement decodes.
     */
    public static void register(@Nonnull PagePart<?, TrainerSection> part, @Nonnull TrainerSource source) {
        PageParts.of(PAGE).register(part);
        SOURCES.put(part.name(), source);
    }

    /** The library's three parts, in their order: Learn, Quests, Services. */
    public static void registerBuiltIns() {
        register(part(LEARN, "What this trainer teaches: every pick whose Where names this placement, with the "
                + "level it opens at and what it costs."), LearnOffers::offers);
        register(part(QUESTS, "The quests this character gives, each opening its quest list on that quest."),
                QuestOffers::offers);
        register(part(SERVICES, "A respec of each track this trainer teaches, and the services other mods add."),
                ServiceOffers::offers);
    }

    /** The source registered with the part {@code name}, or null. */
    @Nullable
    static TrainerSource source(@Nonnull String name) {
        return SOURCES.get(name);
    }

    /** One part a trainer's page draws, and the {@code Sections} entry that placed it (null when none did). */
    record Placed(@Nonnull PagePart<?, ?> part, @Nullable PageSection entry) {
    }

    /**
     * The parts {@code trainer}'s page draws, in order: each part its {@code Sections} writes, once (the first place
     * it is written), an entry naming no registered part passed over; with no {@code Sections}, every registered
     * part in registration order.
     */
    @Nonnull
    static List<Placed> placed(@Nonnull NpcPlacementAsset.Trainer trainer) {
        PageParts parts = PageParts.of(PAGE);
        List<Placed> out = new ArrayList<>();
        List<PageSection> written = trainer.sections();
        if (written == null) {
            for (PagePart<?, ?> part : parts.parts()) {
                out.add(new Placed(part, null));
            }
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (PageSection entry : written) {
            String name = entry.part();
            PagePart<?, ?> part = name == null ? null : parts.part(name);
            if (part != null && seen.add(name)) {
                out.add(new Placed(part, entry));
            }
        }
        return out;
    }

    /** A section of entries as the kit's list section: open, every row shown, no fold. */
    @Nonnull
    static LedgerSection list(@Nonnull String id, @Nonnull TrainerOffers offers) {
        List<LedgerRow> rows = new ArrayList<>(offers.entries().size());
        for (TrainerEntry entry : offers.entries()) {
            rows.add(entry.row());
        }
        return new LedgerSection(id, offers.label(), rows, true, Math.max(1, rows.size()));
    }

    /** Paint {@code section}'s list into its template: one kit list section, each row selecting its entry. */
    static void paint(@Nonnull PartCanvas canvas, @Nonnull TrainerSection section) {
        String index = String.valueOf(canvas.index());
        LedgerBindings bindings = new LedgerBindings() {
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                return EventData.of("Action", SELECT).append("Section", index).append("Row", r.id());
            }

            @Override
            public EventData section(LedgerSection s) {
                return null;
            }

            @Override
            public EventData showMore(LedgerSection s) {
                return null;
            }
        };
        LedgerPainter.paint(canvas.cmd(), canvas.events(), canvas.at("#List"),
                new LedgerModel(List.of(section.list()), null), Set.of(), section.selected(), bindings,
                RowSize.STANDARD, canvas.viewer());
    }
}
