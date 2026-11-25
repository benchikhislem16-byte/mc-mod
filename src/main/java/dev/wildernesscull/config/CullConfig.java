package dev.wildernesscull.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration model for WildernessCull.
 *
 * <p>Every optimization subsystem (Entity Culling, Particle Optimization, Animation Optimization)
 * is independently configurable with conservative defaults that preserve vanilla visual behavior
 * and gameplay feedback out-of-the-box.
 */
public final class CullConfig {

    // -------------------------------------------------------------------------
    // 1. Master Toggle & Diagnostics
    // -------------------------------------------------------------------------
    /** Master switch for all WildernessCull optimizations. */
    public boolean modEnabled = true;

    /** Enables live telemetry counters for benchmark & /wcull stats reporting. */
    public boolean collectTelemetry = true;

    // -------------------------------------------------------------------------
    // 2. Conservative Entity Culling Settings
    // -------------------------------------------------------------------------
    /** Master toggle for conservative occlusion entity culling. */
    public boolean entityCullingEnabled = true;

    /** Maximum distance (blocks) at which 3D raycast occlusion checks run. */
    public double entityCullingMaxDistance = 96.0;

    /**
     * Bounding-box inflation margin (blocks) added around an entity's AABB before sampling
     * visibility target points. Protects held items, armor layers, and slight model overhangs
     * from popping in late.
     */
    public double hitboxExpansionMargin = 0.35;

    /**
     * Number of consecutive occluded client ticks required before an previously-visible entity
     * is hidden (temporal hysteresis). Prevents single-frame flicker around corners or fences.
     */
    public int occlusionGraceTicks = 4;

    /**
     * Maximum number of ticks an occlusion cache entry remains valid when neither camera nor
     * entity has moved significantly.
     */
    public int occlusionCacheTtlTicks = 2;

    /**
     * Camera angular velocity threshold (degrees per tick) above which occlusion cache TTL
     * is bypassed and expanded sample points are tested immediately to prevent whip-pan pop-in.
     */
    public double fastCameraTurnThresholdDeg = 12.0;

    /**
     * Entity velocity threshold squared (blocks/tick)^2 above which the entity's velocity vector
     * is used to expand its culling AABB one tick ahead.
     */
    public double movingEntityVelocitySqThreshold = 0.0225; // (0.15 blocks/tick)^2

    /** Never cull boss entities, glowing entities, leashed entities, or entities with custom nametags. */
    public boolean preserveGameplayCriticalEntities = true;

    /** Entity type IDs that are always exempt from occlusion culling. */
    public List<String> entityWhitelist = new ArrayList<>(Arrays.asList(
            "minecraft:ender_dragon",
            "minecraft:wither",
            "minecraft:warden",
            "minecraft:elder_guardian",
            "minecraft:guardian",
            "minecraft:end_crystal",
            "minecraft:lightning_bolt",
            "minecraft:fishing_bobber",
            "minecraft:beacon"
    ));

    // -------------------------------------------------------------------------
    // 3. Particle Optimization Settings
    // -------------------------------------------------------------------------
    /** Master toggle for particle optimizations. */
    public boolean particleOptimizationEnabled = true;

    /**
     * Safe default: false. When false, newly spawned particles beyond max distance are not dropped
     * unless they are purely ambient; when true, non-critical particles beyond maxParticleDistance
     * are culled at spawn/extract time.
     */
    public boolean cullDistantParticles = true;

    /** Maximum render/extract distance (blocks) for non-critical particles. */
    public double maxParticleDistance = 48.0;

    /**
     * Skip frustum-outside particles during QuadParticleGroup.extractRenderState and optionally
     * throttle physics collisions for distant off-screen particles.
     */
    public boolean throttleOffscreenParticlePhysics = true;

    /** Distance (blocks) beyond which off-screen particle block-collision checks are simplified. */
    public double particlePhysicsThrottleDistance = 24.0;

    /**
     * Soft cap per ParticleGroup before probabilistic thinning begins (vanilla hard cap is 16384,
     * reservoir starts at 12288). Default 8192 preserves vanilla look under normal play while
     * preventing lag spikes during massive explosions or spore/leaf storms.
     */
    public int maxParticlesPerGroup = 8192;

    /**
     * Always preserve gameplay-critical particles (explosions, sonic boom, potion/effect indicators,
     * Elder Guardian curse, totem of undying, trial spawner, sweep attack, crit, heart, barrier/block_marker).
     */
    public boolean preserveGameplayParticles = true;

    /** Particle class simple names or registry identifiers exempt from distance/density culling. */
    public List<String> criticalParticleWhitelist = new ArrayList<>(Arrays.asList(
            "HugeExplosionParticle",
            "HugeExplosionSeedParticle",
            "ExplodeParticle",
            "SonicBoomParticle",
            "ElderGuardianParticle",
            "TotemParticle",
            "AttackSweepParticle",
            "CritParticle",
            "HeartParticle",
            "BlockMarker",
            "SpellParticle",
            "ShriekParticle",
            "SculkChargeParticle",
            "GeyserEruptionParticle",
            "NoxiousGasCloudParticle"
    ));

    // -------------------------------------------------------------------------
    // 4. Animation Optimization Settings
    // -------------------------------------------------------------------------
    /** Master toggle for animation optimizations. */
    public boolean animationOptimizationEnabled = true;

    /**
     * Skip redundant model bone trig/matrix setup (LivingEntityRenderer / EntityModel.setupAnim)
     * when an entity is confirmed culled for the frame.
     */
    public boolean skipCulledEntityModelSetup = true;

    /**
     * Throttle WalkAnimationState and limb interpolation updates for distant entities that are
     * outside the camera frustum or occluded, while maintaining exact phase catch-up when they
     * re-enter view so visible animations are never choppy or delayed.
     */
    public boolean throttleOffscreenWalkAnimations = true;

    /** Distance (blocks) beyond which off-screen entity walk animation updates are throttled. */
    public double animationThrottleMinDistance = 24.0;

    /** Tick interval (e.g. 4 = update every 4th tick with 4x step catch-up) for off-screen distant entities. */
    public int offscreenAnimationTickInterval = 4;

    public CullConfig() {
    }

    /**
     * Resets all fields to conservative, gameplay-safe default values.
     */
    public void resetToDefaults() {
        CullConfig defaults = new CullConfig();
        this.modEnabled = defaults.modEnabled;
        this.collectTelemetry = defaults.collectTelemetry;

        this.entityCullingEnabled = defaults.entityCullingEnabled;
        this.entityCullingMaxDistance = defaults.entityCullingMaxDistance;
        this.hitboxExpansionMargin = defaults.hitboxExpansionMargin;
        this.occlusionGraceTicks = defaults.occlusionGraceTicks;
        this.occlusionCacheTtlTicks = defaults.occlusionCacheTtlTicks;
        this.fastCameraTurnThresholdDeg = defaults.fastCameraTurnThresholdDeg;
        this.movingEntityVelocitySqThreshold = defaults.movingEntityVelocitySqThreshold;
        this.preserveGameplayCriticalEntities = defaults.preserveGameplayCriticalEntities;
        this.entityWhitelist = new ArrayList<>(defaults.entityWhitelist);

        this.particleOptimizationEnabled = defaults.particleOptimizationEnabled;
        this.cullDistantParticles = defaults.cullDistantParticles;
        this.maxParticleDistance = defaults.maxParticleDistance;
        this.throttleOffscreenParticlePhysics = defaults.throttleOffscreenParticlePhysics;
        this.particlePhysicsThrottleDistance = defaults.particlePhysicsThrottleDistance;
        this.maxParticlesPerGroup = defaults.maxParticlesPerGroup;
        this.preserveGameplayParticles = defaults.preserveGameplayParticles;
        this.criticalParticleWhitelist = new ArrayList<>(defaults.criticalParticleWhitelist);

        this.animationOptimizationEnabled = defaults.animationOptimizationEnabled;
        this.skipCulledEntityModelSetup = defaults.skipCulledEntityModelSetup;
        this.throttleOffscreenWalkAnimations = defaults.throttleOffscreenWalkAnimations;
        this.animationThrottleMinDistance = defaults.animationThrottleMinDistance;
        this.offscreenAnimationTickInterval = defaults.offscreenAnimationTickInterval;
    }

    /**
     * Validates and clamps configuration values after loading from disk so corrupt or extreme
     * user edits cannot cause divide-by-zero, negative distances, or visual breakage.
     */
    public void validateAndClamp() {
        this.entityCullingMaxDistance = clamp(this.entityCullingMaxDistance, 8.0, 512.0);
        this.hitboxExpansionMargin = clamp(this.hitboxExpansionMargin, 0.05, 4.0);
        this.occlusionGraceTicks = clampInt(this.occlusionGraceTicks, 1, 40);
        this.occlusionCacheTtlTicks = clampInt(this.occlusionCacheTtlTicks, 1, 20);
        this.fastCameraTurnThresholdDeg = clamp(this.fastCameraTurnThresholdDeg, 1.0, 90.0);
        this.movingEntityVelocitySqThreshold = clamp(this.movingEntityVelocitySqThreshold, 0.0001, 4.0);

        this.maxParticleDistance = clamp(this.maxParticleDistance, 8.0, 256.0);
        this.particlePhysicsThrottleDistance = clamp(this.particlePhysicsThrottleDistance, 8.0, 128.0);
        this.maxParticlesPerGroup = clampInt(this.maxParticlesPerGroup, 256, 16384);

        this.animationThrottleMinDistance = clamp(this.animationThrottleMinDistance, 8.0, 256.0);
        this.offscreenAnimationTickInterval = clampInt(this.offscreenAnimationTickInterval, 1, 20);

        if (this.entityWhitelist == null) {
            this.entityWhitelist = new ArrayList<>();
        }
        if (this.criticalParticleWhitelist == null) {
            this.criticalParticleWhitelist = new ArrayList<>();
        }
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
