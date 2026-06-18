package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.preset.InstancePresetAsset;

/**
 * Forces each common Pattern-A asset CODEC to static-initialize, so a lower-case first
 * letter on any {@code KeyedCodec} field name (which the engine's {@code KeyedCodec}
 * constructor rejects at static init) fails the build here instead of at server start.
 * Mirrors hyMMO's {@code AssetCodecInitTest}.
 */
class AssetCodecInitTest {

    @Test
    void instancePresetAssetCodecInitializes() {
        assertNotNull(InstancePresetAsset.CODEC, "InstancePresetAsset.CODEC must static-init (PascalCase keys)");
    }
}
