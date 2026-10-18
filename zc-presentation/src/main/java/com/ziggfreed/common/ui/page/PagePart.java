package com.ziggfreed.common.ui.page;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * One kind of section a composed page lays out: the group an author writes for it in the page's
 * {@code Sections} ({@code { "Banner": { ... } }}: the group IS the part, there is no {@code Type}), the
 * codec that reads that group, the sentence the Asset Editor shows for it, and how it is drawn: the template
 * appended into the page's section host for it and the painter that fills that template from the part's
 * plan. A module defines its parts as constants and registers them on a page in its {@code *Bootstrap}
 * ({@link PageParts#register}); a page's plan names the part that paints each of its sections
 * ({@link PartPlan}), so painting needs no lookup.
 *
 * @param <A> what the part's group decodes to
 * @param <P> the plan its painter paints
 */
public final class PagePart<A, P> {

    private final KeyedCodec<A> key;
    private final String documentation;
    private final Class<P> planType;
    private final String template;
    private final PartPainter<P> painter;

    private PagePart(@Nonnull KeyedCodec<A> key, @Nonnull String documentation, @Nonnull Class<P> planType,
            @Nonnull String template, @Nonnull PartPainter<P> painter) {
        this.key = key;
        this.documentation = documentation;
        this.planType = planType;
        this.template = template;
        this.painter = painter;
    }

    /**
     * A part named {@code name}, read by {@code codec}. The name is a codec key, so it starts with an upper-case
     * letter ({@code Banner}); a lower-case one throws here, where the part is declared, as a field's key does.
     */
    @Nonnull
    public static <A> Named<A> named(@Nonnull String name, @Nonnull Codec<A> codec) {
        return new Named<>(new KeyedCodec<>(name, Objects.requireNonNull(codec, "codec"), false));
    }

    /** The JSON group name an author writes, and the part's id on its page. */
    @Nonnull
    public String name() {
        return key.getKey();
    }

    /** The codec its group decodes with. */
    @Nonnull
    public Codec<A> codec() {
        return key.getChildCodec();
    }

    /** The key its group is read under: its name and its codec, for a schema built field by field. */
    @Nonnull
    KeyedCodec<A> key() {
        return key;
    }

    /** What the Asset Editor says about the part. */
    @Nonnull
    public String documentation() {
        return documentation;
    }

    /** The template appended into the page's section host for one of these sections. */
    @Nonnull
    public String template() {
        return template;
    }

    /** Whether {@code plan} is one this part paints. */
    public boolean paints(@Nonnull Object plan) {
        return planType.isInstance(plan);
    }

    /** Paint {@code plan} into the template appended at {@code canvas.root()}. */
    public void paint(@Nonnull PartCanvas canvas, @Nonnull Object plan) {
        painter.paint(canvas, planType.cast(plan));
    }

    @Override
    public String toString() {
        return "PagePart{" + name() + "}";
    }

    /** A part named and read, waiting for its editor sentence and how it is drawn. */
    public static final class Named<A> {

        private final KeyedCodec<A> key;
        private String documentation = "";

        private Named(@Nonnull KeyedCodec<A> key) {
            this.key = key;
        }

        /** The sentence the Asset Editor shows for the part's group. */
        @Nonnull
        public Named<A> documentation(@Nonnull String documentation) {
            this.documentation = documentation;
            return this;
        }

        /** The part, drawn from {@code template} by {@code painter}, which paints a {@code planType} plan. */
        @Nonnull
        public <P> PagePart<A, P> paints(@Nonnull Class<P> planType, @Nonnull String template,
                @Nonnull PartPainter<P> painter) {
            return new PagePart<>(key, documentation, Objects.requireNonNull(planType, "planType"),
                    Objects.requireNonNull(template, "template"), Objects.requireNonNull(painter, "painter"));
        }
    }
}
