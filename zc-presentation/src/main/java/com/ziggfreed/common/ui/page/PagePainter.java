package com.ziggfreed.common.ui.page;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Paints a composed page's body: one template per section, appended into the page's section host in the
 * plan's order, each filled by its part's painter under {@code host[i]}. The page's own document declares the
 * host (a {@code Group} laid out {@code Top}); every id inside a section is its part's template's.
 */
public final class PagePainter {

    private PagePainter() {
    }

    /** Append and paint {@code sections} into {@code host}, in order; section {@code i} is {@code host[i]}. */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull List<? extends PartPlan> sections, @Nullable PlayerRef viewer) {
        for (int i = 0; i < sections.size(); i++) {
            PartPlan section = sections.get(i);
            PagePart<?, ?> part = section.part();
            cmd.append(host, part.template());
            part.paint(new PartCanvas(cmd, events, host + "[" + i + "]", i, viewer), section);
        }
    }
}
