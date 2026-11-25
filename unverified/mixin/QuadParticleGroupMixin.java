package dev.wildernesscull.mixin;

import dev.wildernesscull.WildernessCullClient;
import dev.wildernesscull.config.ConfigManager;
import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.particle.ParticleOptimizer;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link QuadParticleGroup#extractRenderState(Frustum, Camera, float)} in Minecraft 26.3.
 *
 * <p><b>Why this hook is needed:</b>
 * In Minecraft 26.3 (verified from {@code QuadParticleGroup.java}), vanilla iterates over
 * {@code this.particles} and calls {@code particle.extract(this.particleTypeRenderState, camera, partialTickTime)}
 * for every particle whose point is in the frustum, regardless of distance. This hook adds configurable
 * distance culling during render extraction so distant ambient particles that are already alive do not
 * consume vertex extraction or translucent sorting time.
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin extends ParticleGroup<SingleQuadParticle> {

    @Shadow
    @Final
    private ParticleRenderType particleType;

    @Shadow
    @Final
    private QuadParticleRenderState particleTypeRenderState;

    protected QuadParticleGroupMixin(ParticleEngine engine) {
        super(engine);
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void wildernesscull$extractRenderState(
            Frustum frustum,
            Camera camera,
            float partialTickTime,
            CallbackInfoReturnable<ParticleGroupRenderState> cir
    ) {
        CullConfig cfg = ConfigManager.getConfig();
        ParticleOptimizer optimizer = WildernessCullClient.getParticleOptimizer();
        if (!cfg.modEnabled || !cfg.particleOptimizationEnabled || !cfg.cullDistantParticles || optimizer == null) {
            return;
        }

        Vec3 camPos = camera.position();
        double maxDistSq = cfg.maxParticleDistance * cfg.maxParticleDistance;

        for (SingleQuadParticle particle : this.particles) {
            AABB box = particle.getBoundingBox();
            double px = (box.minX + box.maxX) * 0.5;
            double py = box.minY;
            double pz = (box.minZ + box.maxZ) * 0.5;

            if (!frustum.pointInFrustum(px, py, pz)) {
                if (cfg.collectTelemetry) {
                    WildernessCullClient.getTelemetry().recordParticleEvaluated();
                    WildernessCullClient.getTelemetry().recordParticleCulledFrustum();
                }
                continue;
            }

            double dx = px - camPos.x;
            double dy = py - camPos.y;
            double dz = pz - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > maxDistSq && !optimizer.isGameplayCriticalParticle(particle.getClass().getSimpleName())) {
                if (cfg.collectTelemetry) {
                    WildernessCullClient.getTelemetry().recordParticleEvaluated();
                    WildernessCullClient.getTelemetry().recordParticleCulledDistance();
                }
                continue;
            }

            if (cfg.collectTelemetry) {
                WildernessCullClient.getTelemetry().recordParticleEvaluated();
                WildernessCullClient.getTelemetry().recordParticleRendered();
            }

            try {
                particle.extract(this.particleTypeRenderState, camera, partialTickTime);
            } catch (Throwable throwable) {
                CrashReport report = CrashReport.forThrowable(throwable, "Rendering Particle");
                CrashReportCategory category = report.addCategory("Particle being rendered");
                category.setDetail("Particle", particle::toString);
                category.setDetail("Particle Type", this.particleType::toString);
                throw new ReportedException(report);
            }
        }

        cir.setReturnValue(this.particleTypeRenderState);
    }
}
