package com.ziggfreed.common.ui.owner;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;

/**
 * Paints a tile's help ({@code Pages/ZigOwnerHelpPage.ui}): the frame's hero (the tile's title and line), then each
 * help topic the tile carries ({@code Pages/ZigOwnerHelpTopic.ui}: its title, its line, and its four parts each under the
 * library's heading), and the Open button only for a tile that opens a screen. The one place the page's selectors are
 * spelt; {@code KitSelectorsTest} walks every one.
 */
public final class OwnerHelpPaint {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigOwnerHelpPage.ui";

    /** One topic. */
    public static final String TOPIC_TEMPLATE = "Pages/ZigOwnerHelpTopic.ui";

    /** What an event round-trips. */
    public static final String ACTION = "Action";
    public static final String CLOSE = "close";
    public static final String BACK = "back";
    public static final String OPEN = "open";

    static final String TOPICS = "#OwnerHelpTopics";
    static final String STATUS = "#OwnerHelpStatus";
    static final String BACK_BUTTON = "#OwnerHelpBack";
    static final String OPEN_BUTTON = "#OwnerHelpOpen";

    /** Each part's paragraph id in a topic, by {@link OwnerHelp#PARTS} order, and its heading's beside it. */
    private static final String[] PARTS = {"#Overview", "#How", "#Examples", "#Tips"};

    private OwnerHelpPaint() {
    }

    /** The page's own bindings: the frame's close button, Back and Open (bound even where Open stays hidden). */
    public static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK_BUTTON, EventData.of(ACTION, BACK), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, OPEN_BUTTON, EventData.of(ACTION, OPEN), false);
    }

    /**
     * Paint the help of a tile titled {@code title} (its line under it, null for none), appended already: one topic
     * each, in order, and Open shown only when {@code opens} (the tile opens a screen).
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull Message title, @Nullable Message line,
            @Nonnull List<OwnerHelp> topics, boolean opens) {
        HeroPainter.paint(cmd, new Hero(null, title, line, null));
        cmd.set(OPEN_BUTTON + ".Visible", opens);
        for (int t = 0; t < topics.size(); t++) {
            OwnerHelp topic = topics.get(t);
            cmd.append(TOPICS, TOPIC_TEMPLATE);
            String at = TOPICS + "[" + t + "]";
            cmd.set(at + " #Title.TextSpans", topic.title());
            cmd.set(at + " #Subtitle.TextSpans", topic.subtitle());
            for (int p = 0; p < PARTS.length; p++) {
                String part = OwnerHelp.PARTS.get(p);
                cmd.set(at + " " + PARTS[p] + "Head.TextSpans", OwnerText.line("help." + part));
                cmd.set(at + " " + PARTS[p] + ".TextSpans", topic.part(part));
            }
        }
    }

    /** The help for a player who may not see it, or a tile gone from the menu: the hero saying so, Open hidden. */
    public static void refused(@Nonnull UICommandBuilder cmd, @Nonnull Message why) {
        HeroPainter.paint(cmd, new Hero(null, OwnerText.line("menu.title"), why, null));
        cmd.set(OPEN_BUTTON + ".Visible", false);
    }

    /** Show {@code status} on the status line (red for an error), or hide it for null. */
    public static void status(@Nonnull UICommandBuilder cmd, @Nullable Message status, boolean error) {
        SettingsUiUtil.setStatus(cmd, STATUS, status, error);
    }
}
