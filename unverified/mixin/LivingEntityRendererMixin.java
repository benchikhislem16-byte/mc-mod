package dev.wildernesscull.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.wildernesscull.WildernessCullClient;
import dev.wildernesscull.animation.AnimationOptimizer;
import dev.wildernesscull.culling.OcclusionCuller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link LivingEntityRenderer#extractRenderState(LivingEntity, LivingEntityRenderState, float)}
 * in Minecraft 26.3.
 *
 * <p><b>Why this hook is needed:</b>
 * In Minecraft 26.3, model bone animations ({@code EntityModel.setupAnim(S state)}) are driven by
 * values populated into {@link LivingEntityRenderState} during {@code extractRenderState}. When an
 * entity re-enters visibility after being off-screen or occluded, this hook ensures its
 * {@link WalkAnimationState} and {@code ageInTicks} are smoothly synchronized without any visual
 * jump or delay.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<
        T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends EntityModel<? super S>> {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("HEAD")
    )
    private void wildernesscull$beforeExtractRenderState(T entity, S state, float partialTicks, CallbackInfo ci) {
        AnimationOptimizer animOpt = WildernessCullClient.getAnimationOptimizer();
        OcclusionCuller culler = WildernessCullClient.getOcclusionCuller();
        Minecraft mc = Minecraft.getInstance();
        if (animOpt == null || culler == null || mc.level == null) {
            return;
        }

        // Entity is being extracted for rendering (meaning it passed shouldRender and is visible!).
        // Trigger phase-preserving catch-up if any off-screen walk animation steps were deferred.
        WalkAnimationState walkAnim = entity.walkAnimation;
        if (walkAnim != null) {
            animOpt.evaluateWalkAnimationSteps(
                    entity.getId(),
                    false,
                    entity.distanceToSqr(mc.gameRenderer.getMainCamera().position()),
                    mc.level.getGameTime(),
                    walkAnim.speed(),
                    0.4F,
                    1.0F
            );
        }
    }
}
