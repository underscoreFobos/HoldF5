package dev.fobium.holdf5.mixin.client;

import dev.fobium.holdf5.client.Holdf5MotionBlur;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;drawEntityOutlinesFramebuffer()V", shift = At.Shift.AFTER)
    )
    private void holdf5$applyMotionBlur(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        Holdf5MotionBlur.render();
    }
}
