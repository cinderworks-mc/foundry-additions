package dev.hartforge.foundryadditions.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

// setChanged reads the four neighbours for the comparator update, and on a waystone sitting on a
// chunk edge that makes the server thread generate the next chunk over and wait on it
@Mixin(targets = "net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase", remap = false)
public abstract class WaystoneLoadMixin {

    @Redirect(
        method = "loadBackingWaystone",
        at = @At(value = "INVOKE", target = "Lnet/blay09/mods/waystones/block/entity/WaystoneBlockEntityBase;setChanged()V")
    )
    private void foundryadditions$markWithoutLoading(@Coerce Object self) {
        BlockEntity be = (BlockEntity) self;
        if (!(be.getLevel() instanceof ServerLevel level)) {
            be.setChanged();
            return;
        }
        BlockPos pos = be.getBlockPos();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            if (level.getChunkSource().getChunkNow(n.getX() >> 4, n.getZ() >> 4) == null) {
                level.blockEntityChanged(pos);
                return;
            }
        }
        be.setChanged();
    }
}
