package dev.hartforge.foundryadditions.compat;

public final class MixinCancels {

    // goety and tropicraft both @Redirect StructurePoolElement.getBoundingBox in
    // JigsawPlacement$Placer.tryPlacingChildren, and only one redirect per call is allowed
    public static final String GOETY_PLACER = "com.Vivideru.Goety.mixin.JigsawPlacerWindShrineMixin";

    private MixinCancels() {}

    public static boolean shouldCancel(String mixinClassName, boolean tropicraftPresent) {
        return tropicraftPresent && GOETY_PLACER.equals(mixinClassName);
    }
}
