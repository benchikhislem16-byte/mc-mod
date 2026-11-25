package dev.wildernesscull.mixin;

import dev.wildernesscull.WildernessCullClient;
import dev.wildernesscull.particle.ParticleOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Hooks {@link ParticleEngine#add(Particle)} in Minecraft 26.3.
 *
 * <p><b>Why this hook is needed:</b>
 * In Minecraft 26.3, all newly spawned particles pass through {@code ParticleEngine.add(Particle)}
 * before being queued into {@code particlesToAdd} and distributed to their {@link ParticleGroup}.
 * Filtering distant non-critical particles and applying configurable soft density thinning here
 * prevents both the {@code ParticleGroup.tickParticles()} CPU cost and the GPU buffer upload cost,
 * while whitelisting gameplay-critical particles (explosions, boss attacks, combat indicators).
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    @Shadow
    @Final
    private Map<ParticleRenderType, ParticleGroup<?>> particles;

    @Inject(
            method = "add(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void wildernesscull$onAddParticle(Particle particle, CallbackInfo ci) {
        if (particle == null) {
            return;
        }
        ParticleOptimizer optimizer = WildernessCullClient.getParticleOptimizer();
        Minecraft mc = Minecraft.getInstance();
        if (optimizer == null || mc.gameRenderer == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }

        Vec3 camPos = camera.position();
        var box = particle.getBoundingBox();
        double px = (box.minX + box.maxX) * 0.5;
        double py = box.minY;
        double pz = (box.minZ + box.maxZ) * 0.5;

        ParticleGroup<?> group = this.particles.get(particle.getGroup());
        int currentGroupSize = group != null ? group.size() : 0;

        float rand = ((ParticleEngine) (Object) this).getRandom().nextFloat();

        boolean keep = optimizer.shouldRenderOrSpawnParticle(
                particle.getClass().getSimpleName(),
                px, py, pz,
                camPos.x, camPos.y, camPos.z,
                true, // Spawn check does not reject by frustum so particles just behind the player still exist when turning
                currentGroupSize,
                rand
        );

        if (!keep) {
            ci.cancel();
        }
    }
}
