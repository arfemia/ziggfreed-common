package com.ziggfreed.common.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What the authoring wizard page round-trips on every binding: {@code Action} names what happened
 * ({@link WizardActions}), {@code Field} which row, {@code @Value} the row's live value (the settings form's own
 * binding shape), and {@code @Search} the search row's live text.
 *
 * <p>The {@code @} on the two value keys is load-bearing: it is the client's directive to resolve the binding's value
 * as an element path and ship what the control holds. Declared bare, the page would hear the path string instead.
 */
public class AuthoringWizardEventData {

    public String action;
    public String field;
    public String value;
    public String search;

    public static final BuilderCodec<AuthoringWizardEventData> CODEC =
            BuilderCodec.builder(AuthoringWizardEventData.class, AuthoringWizardEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, v, info) -> data.action = v,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Field", Codec.STRING),
                            (data, v, info) -> data.field = v,
                            (data, info) -> data.field)
                    .add()
                    .append(new KeyedCodec<>(AuthoringWizardPage.VALUE_KEY, Codec.STRING),
                            (data, v, info) -> data.value = v,
                            (data, info) -> data.value)
                    .add()
                    .append(new KeyedCodec<>(AuthoringWizardPage.SEARCH_KEY, Codec.STRING),
                            (data, v, info) -> data.search = v,
                            (data, info) -> data.search)
                    .add()
                    .build();
}
