package dev.hartforge.foundryadditions.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinCancelsTest {

    @Test
    void cancelsGoetysPlacerOnlyWithTropicraft() {
        assertTrue(MixinCancels.shouldCancel(MixinCancels.GOETY_PLACER, true));
        assertFalse(MixinCancels.shouldCancel(MixinCancels.GOETY_PLACER, false));
    }

    @Test
    void leavesEveryOtherMixinAlone() {
        assertFalse(MixinCancels.shouldCancel("com.Vivideru.Goety.mixin.JigsawRotationWindShrineMixin", true));
        assertFalse(MixinCancels.shouldCancel("net.tropicraft.core.mixin.worldgen.JigsawPlacerMixin", true));
        assertFalse(MixinCancels.shouldCancel("", true));
    }

    @Test
    void mixinConfigParses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/foundryadditions.mixins.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("dev.hartforge.foundryadditions.mixin", o.get("package").getAsString());
            assertEquals("JAVA_21", o.get("compatibilityLevel").getAsString());
            assertEquals("AstralFoliageMixin", o.getAsJsonArray("mixins").get(0).getAsString());
            assertEquals("WaystoneLoadMixin", o.getAsJsonArray("mixins").get(1).getAsString());
            String plugin = o.get("plugin").getAsString();
            // the plugin has to sit outside the mixin package or mixin refuses to load it
            assertFalse(plugin.startsWith(o.get("package").getAsString() + "."));
            assertEquals("dev.hartforge.foundryadditions.compat.FoundryMixinPlugin", plugin);
        }
    }
}
