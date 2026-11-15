package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.ziggfreed.common.asset.EditorPick;
import com.ziggfreed.common.asset.KindSelectorCodec;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * How the in-game Asset Editor offers what an objective kind targets, for any group whose {@code Kind} names
 * one: a quest step's {@code Target} and {@code Targets}, a moment table's {@code When.Match}. Each kind's own
 * file says it ({@code Pick}); a group declared through {@link #selector} turns that into the editor's form,
 * one arm per kind, plus the any-other-kind arm every selector carries.
 *
 * <p>A target the matcher reads as a name pattern or a set key takes a suggestion box (a kind file's
 * {@code Pick.List}, such as {@code ziggfreedcommon:ids/BlockType}), so a pattern stays typeable; a kind file
 * naming a strict {@code Pick.Asset} gets the editor's own picker. Schema only: nothing here changes what a
 * group decodes or matches.
 */
public final class ObjectiveKindPicks {

    private ObjectiveKindPicks() {
    }

    /**
     * {@code codec} as a group whose {@code kindKey} picks its form: {@code kinds} asked each time the editor
     * makes its schemas, and, for a kind whose file names a {@code Pick}, each of {@code textLeaves} a text box
     * and each of {@code listLeaves} a list of them carrying it.
     */
    @Nonnull
    public static <T> KindSelectorCodec<T> selector(@Nonnull BuilderCodec<T> codec, @Nonnull String kindKey,
            @Nonnull Supplier<? extends Collection<String>> kinds, @Nonnull List<String> textLeaves,
            @Nonnull List<String> listLeaves) {
        return new KindSelectorCodec<>(codec, kindKey, () -> arms(kinds.get(), textLeaves, listLeaves));
    }

    /** One arm per kind, carrying its kind file's {@code Pick} on the named leaves where it names one. */
    @Nonnull
    public static List<KindSelectorCodec.Arm> arms(@Nonnull Collection<String> kinds, @Nonnull List<String> textLeaves,
            @Nonnull List<String> listLeaves) {
        List<KindSelectorCodec.Arm> arms = new ArrayList<>(kinds.size());
        for (String id : kinds) {
            EditorPick pick = targetPick(id);
            arms.add(new KindSelectorCodec.Arm(id, null,
                    pick == null ? null : (arm, context) -> applyPick(arm, pick, textLeaves, listLeaves)));
        }
        return arms;
    }

    /** The pick {@code kindId}'s own file names for what it targets, or null when it names none. */
    @Nullable
    public static EditorPick targetPick(@Nonnull String kindId) {
        ObjectiveKindAsset file = ObjectiveKindConfig.getInstance().resolve(kindId);
        return file == null ? null : file.getPick();
    }

    /**
     * Every kind a moment table may answer: each kind the shared vocabulary registers, producible or not (a
     * table may answer a kind only a consumer's own table fires), and each alias, as the vocabulary spells
     * them, sorted.
     */
    @Nonnull
    public static Collection<String> answerableKindIds() {
        ObjectiveKindRegistry registry = ProgressionRuntime.objectiveKinds();
        Map<String, String> ids = new TreeMap<>();
        for (String id : registry.ids()) {
            ObjectiveKind kind = registry.kind(id);
            if (kind != null) {
                ids.putIfAbsent(kind.id().toLowerCase(Locale.ROOT), kind.id());
            }
        }
        for (String alias : registry.aliasIds()) {
            ids.putIfAbsent(alias.toLowerCase(Locale.ROOT), alias);
        }
        return List.copyOf(ids.values());
    }

    /** Replace each named leaf with a text box (or a list of them) carrying {@code pick}, its sentence kept. */
    private static void applyPick(@Nonnull ObjectSchema arm, @Nonnull EditorPick pick, @Nonnull List<String> textLeaves,
            @Nonnull List<String> listLeaves) {
        Map<String, Schema> properties = arm.getProperties();
        for (String key : textLeaves) {
            Schema written = properties.get(key);
            if (written != null) {
                StringSchema leaf = pick.textLeaf();
                leaf.setTypes(new String[] {"string", "null"});
                leaf.setMarkdownDescription(written.getMarkdownDescription());
                properties.put(key, leaf);
            }
        }
        for (String key : listLeaves) {
            Schema written = properties.get(key);
            if (written != null) {
                ArraySchema list = new ArraySchema(pick.textLeaf());
                list.setTypes(new String[] {"array", "null"});
                list.setMarkdownDescription(written.getMarkdownDescription());
                properties.put(key, list);
            }
        }
    }
}
