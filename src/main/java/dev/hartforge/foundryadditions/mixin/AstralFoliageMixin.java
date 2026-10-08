package dev.hartforge.foundryadditions.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the focal point nodes sit thousands of blocks out, and every tick they poke blocks there,
// which makes the server thread generate the chunk and wait on it
@Mixin(targets = "hellfirepvp.astralsorcery.common.focal.node.BasicFocalPointNode", remap = false)
public abstract class AstralFoliageMixin {

    @Inject(method = "clearFoliageColumn", at = @At("HEAD"), cancellable = true)
    private void foundryadditions$skipUnloaded(ServerLevel level, int x, int z, CallbackInfo ci) {
        // hasChunk can block on a chunk that is still generating, getChunkNow never waits
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            ci.cancel();
        }
    }
}
