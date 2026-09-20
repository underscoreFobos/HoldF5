package dev.fobium.holdf5.mixin.client;

import dev.fobium.holdf5.client.Holdf5Client;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public class CameraMixin {
    @ModifyArg(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F")
    )
    private float holdf5$modifyClipDistance(float distance) {
        if (Holdf5Client.isActive()) {
            float progress = Holdf5Client.updateTransitionProgress();
            return distance * progress;
        }
        return distance;
    }
}
