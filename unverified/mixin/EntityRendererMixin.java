package dev.wildernesscull.mixin;

import dev.wildernesscull.WildernessCullClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link EntityRenderer#shouldRender(Entity, Frustum, double, double, double, float)}
 * in Minecraft 26.3.
 *
 * <p><b>Why this hook is needed:</b>
 * In Minecraft 26.3's render-state pipeline ({@code LevelRenderer} -> {@code EntityRenderDispatcher}
 * -> {@code EntityRenderer}), {@code shouldRender} is the single authoritative gate before
 * {@code extractRenderState(T, S, float)} and {@code submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)}
 * are invoked. Returning {@code false} here when an entity is confirmed occluded skips both
 * expensive state extraction and GPU node submission cleanly without altering entity tick logic.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {

    @Shadow
    protected abstract AABB getBoundingBoxForCulling(T entity);

    @Shadow
    protected abstract boolean affectedByCulling(T entity);

    @Inject(
            method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z",
            at = @At("RETURN"),
            cancellable = true
    )
    private void wildernesscull$onShouldRender(
            T entity,
            Frustum culler,
            double camX,
            double camY,
            double camZ,
            float partialTicks,
            CallbackInfoReturnable<Boolean> cir
    ) {
        boolean vanillaVisible = cir.getReturnValueZ();
        if (!vanillaVisible) {
            // Already rejected by vanilla distance or frustum check; record telemetry
            WildernessCullClient.evaluateEntityVisibility(entity, AABB.ofSize(entity.position(), 0.5, 0.5, 0.5), false, camX, camY, camZ);
            return;
        }

        // Respect vanilla's affectedByCulling(T) override (e.g. entities that explicitly opt out of culling)
        if (!this.affectedByCulling(entity)) {
            return;
        }

        AABB cullingBox = this.getBoundingBoxForCulling(entity);
        if (cullingBox == null || cullingBox.hasNaN() || cullingBox.getSize() == 0.0) {
            cullingBox = entity.getBoundingBox().inflate(0.5);
        }

        boolean shouldRender = WildernessCullClient.evaluateEntityVisibility(
                entity,
                cullingBox,
                true,
                camX, camY, camZ
        );

        if (!shouldRender) {
            cir.setReturnValue(false);
        }
    }
}
