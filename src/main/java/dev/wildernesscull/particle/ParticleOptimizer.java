package dev.wildernesscull.particle;

import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.util.CullTelemetry;

/**
 * Optimizes particle spawning, per-frame render extraction, and off-screen physics updates
 * while preserving gameplay-critical visual cues.
 */
public final class ParticleOptimizer {

    private final CullConfig config;
    private final CullTelemetry telemetry;

    public ParticleOptimizer(CullConfig config, CullTelemetry telemetry) {
        this.config = config;
        this.telemetry = telemetry;
    }

    /**
     * Checks whether a particle class name is gameplay-critical (e.g. explosions, sonic boom,
     * elder guardian curse, totem, sweep attack, block markers, trial/geyser/noxious gas warnings).
     */
    public boolean isGameplayCriticalParticle(String particleClassSimpleName) {
        if (!config.preserveGameplayParticles || particleClassSimpleName == null) {
            return false;
        }
        for (int i = 0, size = config.criticalParticleWhitelist.size(); i < size; i++) {
            if (config.criticalParticleWhitelist.get(i).equalsIgnoreCase(particleClassSimpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates whether a newly created or extracted particle should be kept/rendered.
     *
     * @param particleClassSimpleName Simple class name of the Particle subclass
     * @param x                       Particle X coordinate
     * @param y                       Particle Y coordinate
     * @param z                       Particle Z coordinate
     * @param camX                    Camera X coordinate
     * @param camY                    Camera Y coordinate
     * @param camZ                    Camera Z coordinate
     * @param inFrustum               Whether the particle is inside the active camera Frustum
     * @param currentGroupCount       Current number of active particles in the particle's group
     * @param randomFloat0To1         Deterministic or engine float in [0.0, 1.0) for reservoir thinning
     * @return {@code true} if the particle should be rendered/added; {@code false} to skip it
     */
    public boolean shouldRenderOrSpawnParticle(
            String particleClassSimpleName,
            double x, double y, double z,
            double camX, double camY, double camZ,
            boolean inFrustum,
            int currentGroupCount,
            float randomFloat0To1
    ) {
        if (config.collectTelemetry) {
            telemetry.recordParticleEvaluated();
        }

        if (!config.modEnabled || !config.particleOptimizationEnabled) {
            if (inFrustum) {
                if (config.collectTelemetry) telemetry.recordParticleRendered();
                return true;
            } else {
                if (config.collectTelemetry) telemetry.recordParticleCulledFrustum();
                return false;
            }
        }

        // 1. Gameplay-critical particles always bypass distance and density thinning
        boolean critical = isGameplayCriticalParticle(particleClassSimpleName);

        // 2. Frustum check during render state extraction
        if (!inFrustum) {
            if (config.collectTelemetry) telemetry.recordParticleCulledFrustum();
            return false;
        }

        // 3. Distance check for non-critical particles
        if (!critical && config.cullDistantParticles) {
            double dx = x - camX;
            double dy = y - camY;
            double dz = z - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            double maxDist = config.maxParticleDistance;
            if (distSq > maxDist * maxDist) {
                if (config.collectTelemetry) telemetry.recordParticleCulledDistance();
                return false;
            }
        }

        // 4. Soft density cap per ParticleGroup (quadratic thinning curve starting at 75% of soft cap)
        if (!critical) {
            int cap = config.maxParticlesPerGroup;
            if (currentGroupCount >= cap) {
                if (config.collectTelemetry) telemetry.recordParticleThinnedDensity();
                return false;
            }
            int softThreshold = (cap * 3) / 4;
            if (currentGroupCount >= softThreshold) {
                float freeRatio = (float) (cap - currentGroupCount) / (float) Math.max(1, cap - softThreshold);
                if (randomFloat0To1 >= freeRatio * freeRatio) {
                    if (config.collectTelemetry) telemetry.recordParticleThinnedDensity();
                    return false;
                }
            }
        }

        if (config.collectTelemetry) {
            telemetry.recordParticleRendered();
        }
        return true;
    }

    /**
     * Determines whether an active particle can skip expensive voxel collision checks on this tick.
     * Particles that are outside the view frustum and farther than {@code particlePhysicsThrottleDistance}
     * only run full collision on even ticks.
     */
    public boolean shouldThrottleParticleCollision(
            double x, double y, double z,
            double camX, double camY, double camZ,
            boolean inFrustum,
            int particleAge
    ) {
        if (!config.modEnabled || !config.particleOptimizationEnabled || !config.throttleOffscreenParticlePhysics) {
            return false;
        }
        if (inFrustum) {
            return false;
        }
        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double minThrottleDist = config.particlePhysicsThrottleDistance;
        if (distSq < minThrottleDist * minThrottleDist) {
            return false;
        }
        return (particleAge & 1) == 1;
    }
}
