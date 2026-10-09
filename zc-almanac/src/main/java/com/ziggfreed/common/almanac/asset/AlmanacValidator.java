package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.almanac.AlmanacKeepsakeCheck;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded season pages, filed under the {@value #DOMAIN} domain: what an author
 * got wrong in a page, said while the file is still open. While the owner has the Almanac switched off the
 * engine walk reports nothing, since off means absent.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the engine walk (it never throws,
 * and asks the calendar through zc-core's occurrence slot, never zc-calendar) and the second {@code audit}
 * is the pure core a test drives with fakes for the calendar, the item store, the objective vocabulary and
 * the lang catalogue.
 *
 * <p>A page's own findings ({@link AlmanacEntryAsset#findings()}: {@code COLOUR_NOT_HEX},
 * {@code LINK_LEFT_OUT}, {@code HERO_ITEMS_OVER_CAP}, each a warning) are folded in as they are. The
 * codes this audit adds, each a stable machine token a consumer may filter on:
 * <ul>
 *   <li>ERROR {@link #PAGE_ID_UNUSABLE} (the page is skipped whole, so nothing more is asked of it),
 *       {@link #STAT_ID_UNUSABLE}, {@link #MISSING_KIND} and {@link #UNPRODUCIBLE_KIND} (the tally line can
 *       never count; the last two are the tokens the quest and achievement pools use);</li>
 *   <li>WARNING {@link #UNKNOWN_EVENT} (the page is never listed), {@link #UNKNOWN_ICON},
 *       {@link #UNKNOWN_HERO_ITEM}, {@link #UNKNOWN_KIND}, and {@link TextKeyAudit#UNKNOWN_TEXT_KEY} for a
 *       key the page shows that no loaded lang file ships.</li>
 * </ul>
 *
 * <p>A page's {@code Keepsake} is asked here only against the cross-season ladders: the engine walk carries
 * {@code AlmanacKeepsakeCheck}'s findings ({@code KEEPSAKE_NOT_PICKED}, {@code PICKED_NOT_A_KEEPSAKE}), so
 * zc's boot audit counts them in this pass. Whether the Keepsake names a loaded achievement, and the page's
 * {@code Hero.Art} picture, are not asked.
 */
public final class AlmanacValidator {

    public static final String DOMAIN = "almanac";

    /** The label the Almanac audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[almanac] audit";

    public static final String PAGE_ID_UNUSABLE = "PAGE_ID_UNUSABLE";
    public static final String UNKNOWN_EVENT = "UNKNOWN_EVENT";
    public static final String UNKNOWN_ICON = "UNKNOWN_ICON";
    public static final String UNKNOWN_HERO_ITEM = "UNKNOWN_HERO_ITEM";
    public static final String STAT_ID_UNUSABLE = "STAT_ID_UNUSABLE";
    public static final String MISSING_KIND = "MISSING_KIND";
    public static final String UNKNOWN_KIND = "UNKNOWN_KIND";
    public static final String UNPRODUCIBLE_KIND = "UNPRODUCIBLE_KIND";

    private AlmanacValidator() {
    }

    /**
     * The engine walk over every folded page, against the calendar (through the occurrence slot), the live
     * item store, the shared objective vocabulary and the lang catalogue; then the keepsake check over the
     * loaded pages and achievement files ({@code AlmanacKeepsakeCheck}, which prints nothing itself on a boot
     * that runs this walk as zc's boot audit).
     */
    @Nonnull
    public static List<Finding> audit() {
        try {
            if (!AlmanacSwitch.isOn()) {
                return List.of();
            }
            List<Finding> out = new ArrayList<>(audit(AlmanacEntryConfig.getInstance().all().values(),
                    AlmanacValidator::eventLoaded, ItemIds::exists, ProgressionRuntime.objectiveKinds(),
                    TextKeyAudit.liveCatalogue()));
            out.addAll(AlmanacKeepsakeCheck.findings());
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the Almanac audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core, pages in id order and each page's lines in name order, so a report reads the same
     * twice running.
     *
     * @param eventLoaded whether the calendar has loaded an event of that id, switched on or off
     * @param itemKnown   whether an item id names a loaded item
     * @param kinds       the objective vocabulary a tally line's Kind is asked of; null asks nothing
     * @param keyShipped  whether a loaded lang file ships a key
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<AlmanacEntryAsset> pages,
            @Nonnull Predicate<String> eventLoaded, @Nonnull Predicate<String> itemKnown,
            @Nullable ObjectiveKindRegistry kinds, @Nonnull Predicate<String> keyShipped) {
        List<AlmanacEntryAsset> ordered = new ArrayList<>();
        for (AlmanacEntryAsset page : pages) {
            if (page != null && page.getId() != null) {
                ordered.add(page);
            }
        }
        ordered.sort(Comparator.comparing(AlmanacEntryAsset::getId));
        List<Finding> out = new ArrayList<>();
        for (AlmanacEntryAsset page : ordered) {
            auditPage(page, eventLoaded, itemKnown, kinds, keyShipped, out);
        }
        return out;
    }

    private static void auditPage(@Nonnull AlmanacEntryAsset page, @Nonnull Predicate<String> eventLoaded,
            @Nonnull Predicate<String> itemKnown, @Nullable ObjectiveKindRegistry kinds,
            @Nonnull Predicate<String> keyShipped, @Nonnull List<Finding> out) {
        String id = page.getId();
        String where = "the season page '" + id + "'";
        if (!AlmanacKeys.usableId(id)) {
            out.add(Finding.error(DOMAIN, PAGE_ID_UNUSABLE, where + " is named with a character the tally format "
                    + "reserves (/ @ | : or a leading $), so the Almanac skips it whole; rename its file", id));
            return;
        }
        out.addAll(page.findings());
        if (!eventLoaded.test(id)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_EVENT, where + " is named for no calendar event this server "
                    + "loads, so the Almanac never lists it; a page's file name is its event's id", id));
        }
        checkItem(where + " Icon", page.getIcon(), UNKNOWN_ICON, "it shows no picture", itemKnown, id, out);
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.TitleKey", page.titleKey(), keyShipped,
                "the season's name shows the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.FlavorKey", page.flavorKey(), keyShipped,
                "the line about the season shows the raw key");
        AlmanacHeroAsset hero = page.hero();
        AlmanacHeroAsset.Composition composition = hero == null ? null : hero.composition();
        if (composition != null) {
            for (AlmanacHeroAsset.Placement placement : composition.items()) {
                checkItem(where + " Hero.Composition.Items", placement.item(), UNKNOWN_HERO_ITEM,
                        "that picture is left off the top of the page", itemKnown, id, out);
            }
        }
        for (Map.Entry<String, AlmanacStatAsset> stat : new TreeMap<>(page.getStats()).entrySet()) {
            auditStat(where + " tally line '" + stat.getKey() + "'", stat.getKey(), stat.getValue(), itemKnown, kinds,
                    keyShipped, id, out);
        }
        for (AlmanacLinkAsset link : page.links()) {
            TextKeyAudit.check(out, DOMAIN, id, where + " link", link.textKey(), keyShipped,
                    "the link shows the raw key");
        }
    }

    private static void auditStat(@Nonnull String where, @Nonnull String name, @Nonnull AlmanacStatAsset stat,
            @Nonnull Predicate<String> itemKnown, @Nullable ObjectiveKindRegistry kinds,
            @Nonnull Predicate<String> keyShipped, @Nonnull String id, @Nonnull List<Finding> out) {
        String statId = AlmanacKeys.normalize(name);
        if (!AlmanacKeys.usableId(statId)) {
            out.add(Finding.error(DOMAIN, STAT_ID_UNUSABLE, where + " is named with a character the tally format "
                    + "reserves (/ @ | : or a leading $), so the line is skipped; rename it", id));
            return;
        }
        if (stat.isBlank()) {
            out.add(Finding.error(DOMAIN, MISSING_KIND, where + " names no Kind, so nothing could ever count it "
                    + "and the line is skipped", id));
            return;
        }
        if (kinds != null) {
            String kind = stat.toDefBuilder(statId, kinds).build().kind();
            if (!kinds.isRegistered(kind)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_KIND, where + " counts '" + stat.getKind().trim()
                        + "', which nothing registered, so it counts nothing until whichever mod fires it is "
                        + "installed", id));
            } else if (!kinds.isProducible(kind)) {
                out.add(Finding.error(DOMAIN, UNPRODUCIBLE_KIND, where + " counts '" + stat.getKind().trim()
                        + "', which is registered as something nothing ever fires, so it never counts", id));
            }
        }
        checkItem(where + " Icon", stat.getIcon(), UNKNOWN_ICON, "it shows no picture", itemKnown, id, out);
        TextKeyAudit.check(out, DOMAIN, id, where + " TextKey", stat.getTextKey(), keyShipped,
                "the line shows the raw key");
    }

    private static void checkItem(@Nonnull String where, @Nullable String itemId, @Nonnull String code,
            @Nonnull String cost, @Nonnull Predicate<String> itemKnown, @Nonnull String id,
            @Nonnull List<Finding> out) {
        if (itemId != null && !itemKnown.test(itemId)) {
            out.add(Finding.warning(DOMAIN, code, where + " names the item '" + itemId + "', which is no loaded "
                    + "item, so " + cost, id));
        }
    }

    /**
     * Has the calendar loaded {@code eventId}, switched on or off? Asked of the occurrence slot itself rather
     * than {@code OccurrenceAlmanacCalendar}, whose answer is absence for an event switched off: a year answers
     * for any loaded event ({@code OccurrenceSource.currentYear}). Before the calendar fills the slot, or when
     * the source throws, nothing can be told, and cannot tell is not "missing".
     */
    private static boolean eventLoaded(@Nonnull String eventId) {
        try {
            return !Occurrences.isFilled()
                    || Occurrences.source().currentYear(eventId, System.currentTimeMillis()) != null;
        } catch (Throwable t) {
            return true;
        }
    }
}
