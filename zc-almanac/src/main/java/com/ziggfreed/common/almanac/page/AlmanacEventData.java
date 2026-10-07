package com.ziggfreed.common.almanac.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.ui.menu.ZigMenu;

/**
 * What the Almanac page round-trips on a binding. {@code action} is {@code select} (open the page on
 * {@code season}, read in {@code year}: a year, {@link #EVERY} for every season, absent for the season's
 * default), {@code month} (open the page on the first season the year at a glance marked in {@code month},
 * 1 to 12), {@code link} (open the season's {@code link}-th link, counted in the order the last build
 * painted them), or {@code close}; a rail click carries {@code menu} instead.
 */
public class AlmanacEventData {

    /** The year chip that reads every season together. */
    public static final String EVERY = "every";

    public String action;
    public String season;
    /** A year chip's pick: a year as digits, or {@link #EVERY}; null for every other event. */
    public String year;
    /** A month row of the year at a glance, 1 to 12; null for every other event. */
    public String month;
    /** Which of the season's links, from 0; null for every other event. */
    public String link;
    /** The rail row a click came from ({@code ZigMenu.EVENT_KEY}); null for every event of the page's own. */
    public String menu;

    public static final BuilderCodec<AlmanacEventData> CODEC =
            BuilderCodec.builder(AlmanacEventData.class, AlmanacEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Season", Codec.STRING),
                            (data, value, info) -> data.season = value,
                            (data, info) -> data.season)
                    .add()
                    .append(new KeyedCodec<>("Year", Codec.STRING),
                            (data, value, info) -> data.year = value,
                            (data, info) -> data.year)
                    .add()
                    .append(new KeyedCodec<>("Month", Codec.STRING),
                            (data, value, info) -> data.month = value,
                            (data, info) -> data.month)
                    .add()
                    .append(new KeyedCodec<>("Link", Codec.STRING),
                            (data, value, info) -> data.link = value,
                            (data, info) -> data.link)
                    .add()
                    .append(new KeyedCodec<>(ZigMenu.EVENT_KEY, Codec.STRING),
                            (data, value, info) -> data.menu = value,
                            (data, info) -> data.menu)
                    .add()
                    .build();
}
