package dev.hartforge.foundryadditions.compat;

import com.bawnorton.mixinsquared.canceller.MixinCancellerRegistrar;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class FoundryMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("foundryadditions");

    @Override
    public void onLoad(String mixinPackage) {
        MixinCancellerRegistrar.register((targets, mixinClassName) -> {
            // looked up per call, the mod list may not exist yet when this plugin loads
            boolean tropicraft = LoadingModList.get().getModFileById("tropicraft") != null;
            boolean cancel = MixinCancels.shouldCancel(mixinClassName, tropicraft);
            if (cancel) {
                LOGGER.info("cancelling {} (clashes with tropicraft's jigsaw placer mixin)", mixinClassName);
            }
            return cancel;
        });
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("AstralFoliageMixin")) {
            return LoadingModList.get().getModFileById("astralsorcery") != null;
        }
        if (mixinClassName.endsWith("WaystoneLoadMixin")) {
            return LoadingModList.get().getModFileById("waystones") != null;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
