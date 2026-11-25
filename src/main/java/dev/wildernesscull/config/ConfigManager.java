package dev.wildernesscull.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Handles loading, saving, atomic backups, and resetting of {@link CullConfig}.
 *
 * <p>Uses a human-readable, well-commented {@code wildernesscull.properties} file stored in
 * {@code .minecraft/config/wildernesscull.properties}. Having zero external serializer
 * dependencies guarantees compatibility across both standalone JVM verification tests and
 * the Minecraft 26.3 runtime.
 */
public final class ConfigManager {

    private static final String CONFIG_FILENAME = "wildernesscull.properties";
    private static Path configPath;
    private static final CullConfig CONFIG = new CullConfig();

    private ConfigManager() {
    }

    public static CullConfig getConfig() {
        return CONFIG;
    }

    public static void init(Path configDir) {
        configPath = configDir.resolve(CONFIG_FILENAME);
        load();
    }

    public static synchronized void load() {
        if (configPath == null) {
            return;
        }
        if (!Files.exists(configPath)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(reader);
            applyProperties(props, CONFIG);
            CONFIG.validateAndClamp();
        } catch (Exception e) {
            // Backup corrupt file and restore safe defaults
            try {
                Path backup = configPath.resolveSibling(CONFIG_FILENAME + ".corrupt.bak");
                Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            CONFIG.resetToDefaults();
            save();
        }
    }

    public static synchronized void save() {
        if (configPath == null) {
            return;
        }
        CONFIG.validateAndClamp();
        try {
            Files.createDirectories(configPath.getParent());
            Path tempPath = configPath.resolveSibling(CONFIG_FILENAME + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                writer.write("# ============================================================================\n");
                writer.write("# WildernessCull Configuration (Minecraft Java Edition 26.3 Fabric)\n");
                writer.write("# Conservative Entity Culling, Particle Optimization & Animation Throttling\n");
                writer.write("# Delete this file or run '/wcull reset' in-game to restore safe defaults.\n");
                writer.write("# ============================================================================\n\n");

                writer.write("# [Master & Diagnostics]\n");
                writeProp(writer, "modEnabled", CONFIG.modEnabled,
                        "Master toggle for all WildernessCull optimizations.");
                writeProp(writer, "collectTelemetry", CONFIG.collectTelemetry,
                        "Track frame/tick counters for /wcull stats and benchmarking.");

                writer.write("\n# [Conservative Entity Culling]\n");
                writeProp(writer, "entityCullingEnabled", CONFIG.entityCullingEnabled,
                        "Skip rendering entities confirmed to be completely behind opaque blocks.");
                writeProp(writer, "entityCullingMaxDistance", CONFIG.entityCullingMaxDistance,
                        "Maximum distance (blocks) for occlusion raycasts [8.0 - 512.0].");
                writeProp(writer, "hitboxExpansionMargin", CONFIG.hitboxExpansionMargin,
                        "AABB expansion margin (blocks) to prevent pop-in on armor/held items [0.05 - 4.0].");
                writeProp(writer, "occlusionGraceTicks", CONFIG.occlusionGraceTicks,
                        "Consecutive occluded ticks required before hiding an entity (prevents flicker) [1 - 40].");
                writeProp(writer, "occlusionCacheTtlTicks", CONFIG.occlusionCacheTtlTicks,
                        "Ticks to reuse occlusion results when camera and entity are stationary [1 - 20].");
                writeProp(writer, "fastCameraTurnThresholdDeg", CONFIG.fastCameraTurnThresholdDeg,
                        "Camera rotation speed (deg/tick) that forces immediate re-sampling [1.0 - 90.0].");
                writeProp(writer, "movingEntityVelocitySqThreshold", CONFIG.movingEntityVelocitySqThreshold,
                        "Squared velocity threshold above which entity bounds expand along motion vector.");
                writeProp(writer, "preserveGameplayCriticalEntities", CONFIG.preserveGameplayCriticalEntities,
                        "Never cull bosses, glowing entities, leashed entities, or nametagged entities.");
                writeProp(writer, "entityWhitelist", String.join(",", CONFIG.entityWhitelist),
                        "Comma-separated entity IDs that are never culled.");

                writer.write("\n# [Particle Optimization]\n");
                writeProp(writer, "particleOptimizationEnabled", CONFIG.particleOptimizationEnabled,
                        "Enable distance, frustum, and density optimizations for particles.");
                writeProp(writer, "cullDistantParticles", CONFIG.cullDistantParticles,
                        "Skip non-critical particles beyond maxParticleDistance.");
                writeProp(writer, "maxParticleDistance", CONFIG.maxParticleDistance,
                        "Maximum render distance (blocks) for non-critical particles [8.0 - 256.0].");
                writeProp(writer, "throttleOffscreenParticlePhysics", CONFIG.throttleOffscreenParticlePhysics,
                        "Reduce physics/collision work for distant particles outside the camera frustum.");
                writeProp(writer, "particlePhysicsThrottleDistance", CONFIG.particlePhysicsThrottleDistance,
                        "Distance (blocks) beyond which off-screen particle physics are throttled.");
                writeProp(writer, "maxParticlesPerGroup", CONFIG.maxParticlesPerGroup,
                        "Soft particle cap per render group before probabilistic thinning [256 - 16384].");
                writeProp(writer, "preserveGameplayParticles", CONFIG.preserveGameplayParticles,
                        "Always preserve explosions, combat indicators, boss effects, and block markers.");
                writeProp(writer, "criticalParticleWhitelist", String.join(",", CONFIG.criticalParticleWhitelist),
                        "Comma-separated particle class names exempt from culling.");

                writer.write("\n# [Animation Optimization]\n");
                writeProp(writer, "animationOptimizationEnabled", CONFIG.animationOptimizationEnabled,
                        "Enable off-screen/culled entity animation optimizations.");
                writeProp(writer, "skipCulledEntityModelSetup", CONFIG.skipCulledEntityModelSetup,
                        "Skip model bone setup when an entity is culled for the current frame.");
                writeProp(writer, "throttleOffscreenWalkAnimations", CONFIG.throttleOffscreenWalkAnimations,
                        "Throttle walk animation state updates for distant off-screen/occluded entities.");
                writeProp(writer, "animationThrottleMinDistance", CONFIG.animationThrottleMinDistance,
                        "Minimum distance (blocks) before off-screen animation throttling applies.");
                writeProp(writer, "offscreenAnimationTickInterval", CONFIG.offscreenAnimationTickInterval,
                        "Tick interval for off-screen walk animations (with exact phase catch-up) [1 - 20].");
            }
            Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static synchronized void resetToDefaultsAndSave() {
        CONFIG.resetToDefaults();
        save();
    }

    private static void writeProp(BufferedWriter writer, String key, Object value, String comment) throws IOException {
        writer.write("# " + comment + "\n");
        writer.write(key + "=" + value + "\n");
    }

    private static void applyProperties(Properties props, CullConfig cfg) {
        cfg.modEnabled = parseBoolean(props, "modEnabled", cfg.modEnabled);
        cfg.collectTelemetry = parseBoolean(props, "collectTelemetry", cfg.collectTelemetry);

        cfg.entityCullingEnabled = parseBoolean(props, "entityCullingEnabled", cfg.entityCullingEnabled);
        cfg.entityCullingMaxDistance = parseDouble(props, "entityCullingMaxDistance", cfg.entityCullingMaxDistance);
        cfg.hitboxExpansionMargin = parseDouble(props, "hitboxExpansionMargin", cfg.hitboxExpansionMargin);
        cfg.occlusionGraceTicks = parseInt(props, "occlusionGraceTicks", cfg.occlusionGraceTicks);
        cfg.occlusionCacheTtlTicks = parseInt(props, "occlusionCacheTtlTicks", cfg.occlusionCacheTtlTicks);
        cfg.fastCameraTurnThresholdDeg = parseDouble(props, "fastCameraTurnThresholdDeg", cfg.fastCameraTurnThresholdDeg);
        cfg.movingEntityVelocitySqThreshold = parseDouble(props, "movingEntityVelocitySqThreshold", cfg.movingEntityVelocitySqThreshold);
        cfg.preserveGameplayCriticalEntities = parseBoolean(props, "preserveGameplayCriticalEntities", cfg.preserveGameplayCriticalEntities);
        cfg.entityWhitelist = parseList(props, "entityWhitelist", cfg.entityWhitelist);

        cfg.particleOptimizationEnabled = parseBoolean(props, "particleOptimizationEnabled", cfg.particleOptimizationEnabled);
        cfg.cullDistantParticles = parseBoolean(props, "cullDistantParticles", cfg.cullDistantParticles);
        cfg.maxParticleDistance = parseDouble(props, "maxParticleDistance", cfg.maxParticleDistance);
        cfg.throttleOffscreenParticlePhysics = parseBoolean(props, "throttleOffscreenParticlePhysics", cfg.throttleOffscreenParticlePhysics);
        cfg.particlePhysicsThrottleDistance = parseDouble(props, "particlePhysicsThrottleDistance", cfg.particlePhysicsThrottleDistance);
        cfg.maxParticlesPerGroup = parseInt(props, "maxParticlesPerGroup", cfg.maxParticlesPerGroup);
        cfg.preserveGameplayParticles = parseBoolean(props, "preserveGameplayParticles", cfg.preserveGameplayParticles);
        cfg.criticalParticleWhitelist = parseList(props, "criticalParticleWhitelist", cfg.criticalParticleWhitelist);

        cfg.animationOptimizationEnabled = parseBoolean(props, "animationOptimizationEnabled", cfg.animationOptimizationEnabled);
        cfg.skipCulledEntityModelSetup = parseBoolean(props, "skipCulledEntityModelSetup", cfg.skipCulledEntityModelSetup);
        cfg.throttleOffscreenWalkAnimations = parseBoolean(props, "throttleOffscreenWalkAnimations", cfg.throttleOffscreenWalkAnimations);
        cfg.animationThrottleMinDistance = parseDouble(props, "animationThrottleMinDistance", cfg.animationThrottleMinDistance);
        cfg.offscreenAnimationTickInterval = parseInt(props, "offscreenAnimationTickInterval", cfg.offscreenAnimationTickInterval);
    }

    private static boolean parseBoolean(Properties props, String key, boolean fallback) {
        String val = props.getProperty(key);
        if (val == null) return fallback;
        return Boolean.parseBoolean(val.trim());
    }

    private static int parseInt(Properties props, String key, int fallback) {
        String val = props.getProperty(key);
        if (val == null) return fallback;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(Properties props, String key, double fallback) {
        String val = props.getProperty(key);
        if (val == null) return fallback;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> parseList(Properties props, String key, List<String> fallback) {
        String val = props.getProperty(key);
        if (val == null) return new ArrayList<>(fallback);
        if (val.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(val.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
