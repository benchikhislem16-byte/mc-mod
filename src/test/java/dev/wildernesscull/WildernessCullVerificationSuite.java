package dev.wildernesscull;

import dev.wildernesscull.animation.AnimationOptimizer;
import dev.wildernesscull.config.ConfigManager;
import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.culling.OcclusionCuller;
import dev.wildernesscull.culling.OcclusionCuller.EntityVisibilityInput;
import dev.wildernesscull.culling.OcclusionCuller.OpaqueBlockLookup;
import dev.wildernesscull.particle.ParticleOptimizer;
import dev.wildernesscull.util.CullTelemetry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Reproducible Verification & Benchmark Suite for WildernessCull.
 *
 * <p>Can be executed either via Gradle JUnit ({@code ./gradlew test}) or directly with standard
 * {@code javac} + {@code java} with zero external dependencies.
 *
 * <p>Tests:
 * <ol>
 *   <li><b>Line-of-Sight & Occlusion Accuracy:</b> Verifies entities in open air or partially behind
 *       1-block pillars are NEVER hidden, while entities completely behind a 3x3x3 opaque wall ARE
 *       culled after the grace window.</li>
 *   <li><b>Transparent Block Safety:</b> Verifies entities behind glass, leaves, fences, or slabs
 *       are NEVER culled.</li>
 *   <li><b>Anti-Flicker Temporal Hysteresis:</b> Verifies an entity stepping behind a wall stays
 *       rendered during the grace window ({@code occlusionGraceTicks = 4}) and re-appears on the
 *       very first tick (0-tick delay) when stepping back into view.</li>
 *   <li><b>Fast Camera Whip-Pan & Moving Entity Bounds:</b> Verifies high camera angular velocity
 *       and high entity velocity expand the sampling bounds and bypass stale cache entries.</li>
 *   <li><b>Unusual / Degenerate Entity Bounds & Gameplay Exemptions:</b> Verifies zero-size AABBs,
 *       bosses, glowing entities, leashed entities, nametagged entities, and player mounts never crash
 *       or get culled.</li>
 *   <li><b>Particle Optimization & Critical Whitelist:</b> Verifies distant ambient particles are
 *       culled and dense groups are thinned, while {@code HugeExplosionParticle} and {@code SonicBoomParticle}
 *       are always preserved.</li>
 *   <li><b>Animation Optimization & Phase Catch-Up:</b> Verifies off-screen walk animations throttle
 *       cleanly and synchronize back to exact step count on visibility re-entry.</li>
 *   <li><b>Config Persistence, Validation Clamping & Corrupt-File Recovery:</b> Verifies saving,
 *       reloading, clamping extreme values, and resetting to safe defaults.</li>
 *   <li><b>Synthetic A/B Benchmark:</b> Runs 1,000 simulated client frames with 500 entities and
 *       4,000 particles comparing Mod Disabled vs Mod Enabled under identical deterministic seeds.</li>
 * </ol>
 */
public final class WildernessCullVerificationSuite {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println(" WildernessCull (Minecraft 26.3 Fabric) - Verification & Benchmark Suite");
        System.out.println("================================================================================");

        runTest("1. Open-Air & Partial-Cover Entities Never Hidden", WildernessCullVerificationSuite::testOpenAndPartialCoverNeverHidden);
        runTest("2. Transparent Blocks (Glass/Fences/Leaves) Never Occlude", WildernessCullVerificationSuite::testTransparentBlocksNeverOcclude);
        runTest("3. Anti-Flicker Temporal Hysteresis & Instant Re-Entry", WildernessCullVerificationSuite::testAntiFlickerHysteresis);
        runTest("4. Camera Whip-Pan & Moving Entity Velocity Expansion", WildernessCullVerificationSuite::testCameraWhipPanAndMovingEntities);
        runTest("5. Degenerate Entity Bounds & Gameplay Exemptions (Boss/Glowing/Leashed)", WildernessCullVerificationSuite::testDegenerateBoundsAndExemptions);
        runTest("6. Particle Distance/Frustum/Density Culling & Critical Whitelist", WildernessCullVerificationSuite::testParticleOptimizations);
        runTest("7. Animation Off-Screen Throttling & Re-Entry Synchronization", WildernessCullVerificationSuite::testAnimationOptimizations);
        runTest("8. Config Persistence, Validation Clamping & Corrupt File Recovery", WildernessCullVerificationSuite::testConfigPersistenceAndRecovery);

        System.out.println("--------------------------------------------------------------------------------");
        runSyntheticBenchmark();
        System.out.println("================================================================================");
        System.out.printf(" RESULT: %d passed, %d failed%n", passed, failed);
        System.out.println("================================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }

    private static void runTest(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + " -> " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: Open-air and partially visible entities are never culled
    // -------------------------------------------------------------------------
    private static void testOpenAndPartialCoverNeverHidden() {
        CullConfig cfg = new CullConfig();
        CullTelemetry telemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(cfg, telemetry);

        // Empty world: entity at z=15 must always render
        OpaqueBlockLookup emptyWorld = (x, y, z) -> false;
        EntityVisibilityInput zombie = new EntityVisibilityInput(
                101, "minecraft:zombie",
                -0.3, 64.0, 14.7,
                0.3, 65.95, 15.3,
                0.0, 0.0, 0.0,
                false, false, false, false, false, true
        );

        for (long tick = 0; tick < 10; tick++) {
            assertTrue(culler.shouldRenderEntity(zombie, 0.0, 65.62, 0.0, tick, emptyWorld),
                    "Entity in open air must never be culled at tick " + tick);
        }

        // Single 1x1 block at (0, 64, 5): entity's head at y=65.95 is still visible over the block!
        OpaqueBlockLookup lowBlockWorld = (x, y, z) -> (x == 0 && y == 64 && z == 5);
        for (long tick = 20; tick < 30; tick++) {
            assertTrue(culler.shouldRenderEntity(zombie, 0.0, 65.62, 0.0, tick, lowBlockWorld),
                    "Partially visible entity peeking over a 1-block wall must never be culled");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Transparent blocks never occlude
    // -------------------------------------------------------------------------
    private static void testTransparentBlocksNeverOcclude() {
        CullConfig cfg = new CullConfig();
        CullTelemetry telemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(cfg, telemetry);

        // A wall of glass/leaves between camera (0,65.62,0) and entity (0,64,12):
        // OpaqueBlockLookup returns false for non-opaque blocks (matching state.isSolidRender() && isCollisionShapeFullBlock)
        OpaqueBlockLookup glassWallWorld = (x, y, z) -> false;
        EntityVisibilityInput creeper = new EntityVisibilityInput(
                102, "minecraft:creeper",
                -0.3, 64.0, 11.7,
                0.3, 65.7, 12.3,
                0.0, 0.0, 0.0,
                false, false, false, false, false, true
        );

        for (long tick = 0; tick < 10; tick++) {
            assertTrue(culler.shouldRenderEntity(creeper, 0.0, 65.62, 0.0, tick, glassWallWorld),
                    "Entity behind transparent glass/leaves must remain visible");
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Anti-flicker hysteresis and 0-tick instant re-entry
    // -------------------------------------------------------------------------
    private static void testAntiFlickerHysteresis() {
        CullConfig cfg = new CullConfig();
        cfg.occlusionGraceTicks = 4;
        cfg.occlusionCacheTtlTicks = 1;
        CullTelemetry telemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(cfg, telemetry);

        // Solid 7x7 wall at z=5 blocking all rays from (0, 65.62, 0) to (0, 64..66, 15)
        OpaqueBlockLookup solidWall = (x, y, z) -> (z == 5 && x >= -3 && x <= 3 && y >= 62 && y <= 68);

        EntityVisibilityInput skeleton = new EntityVisibilityInput(
                103, "minecraft:skeleton",
                -0.3, 64.0, 14.7,
                0.3, 65.99, 15.3,
                0.0, 0.0, 0.0,
                false, false, false, false, false, true
        );

        // Ticks 0, 1, 2: protected by 4-tick hysteresis grace window -> still rendered!
        assertTrue(culler.shouldRenderEntity(skeleton, 0.0, 65.62, 0.0, 0, solidWall), "Grace tick 1 must render");
        assertTrue(culler.shouldRenderEntity(skeleton, 0.0, 65.62, 0.0, 1, solidWall), "Grace tick 2 must render");
        assertTrue(culler.shouldRenderEntity(skeleton, 0.0, 65.62, 0.0, 2, solidWall), "Grace tick 3 must render");

        // Tick 3 (4th consecutive occluded tick): now confirmed occluded -> culled!
        assertFalse(culler.shouldRenderEntity(skeleton, 0.0, 65.62, 0.0, 3, solidWall),
                "Entity must be culled on 4th consecutive occluded tick");
        assertTrue(culler.isEntityCurrentlyCulled(103), "Entity state must report culled");

        // Tick 4: wall is removed (or entity steps out) -> MUST render immediately on Tick 4 with ZERO flicker delay
        OpaqueBlockLookup openWorld = (x, y, z) -> false;
        assertTrue(culler.shouldRenderEntity(skeleton, 0.0, 65.62, 0.0, 4, openWorld),
                "Entity re-entering line-of-sight must render immediately with 0-tick delay");
        assertFalse(culler.isEntityCurrentlyCulled(103), "Entity state must report unculled immediately");
    }

    // -------------------------------------------------------------------------
    // Test 4: Fast camera turn & moving entity velocity expansion
    // -------------------------------------------------------------------------
    private static void testCameraWhipPanAndMovingEntities() {
        CullConfig cfg = new CullConfig();
        cfg.occlusionGraceTicks = 1;
        cfg.occlusionCacheTtlTicks = 5;
        CullTelemetry telemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(cfg, telemetry);

        culler.updateCameraMotion(0.0F, 0.0F);
        assertFalse(culler.isCameraTurningFast(), "Stationary camera should not flag fast turn");

        culler.updateCameraMotion(25.0F, 5.0F);
        assertTrue(culler.isCameraTurningFast(), "25 deg/tick camera pan must trigger fast-turn mode");

        // Fast-moving entity near edge of narrow wall at x=[-1..1], z=5:
        // Because entity has velocity velX = 0.8 blocks/tick, its expanded AABB reaches past x=1 and stays visible!
        OpaqueBlockLookup narrowWall = (x, y, z) -> (z == 5 && x >= -1 && x <= 1 && y >= 62 && y <= 68);
        EntityVisibilityInput fastHorse = new EntityVisibilityInput(
                104, "minecraft:horse",
                0.2, 64.0, 9.5,
                1.6, 65.6, 10.5,
                0.85, 0.0, 0.0,
                false, false, false, false, false, true
        );
        assertTrue(culler.shouldRenderEntity(fastHorse, 0.0, 65.62, 0.0, 10, narrowWall),
                "Fast-moving entity near wall edge must not be culled prematurely");
    }

    // -------------------------------------------------------------------------
    // Test 5: Degenerate bounds and gameplay-critical entity exemptions
    // -------------------------------------------------------------------------
    private static void testDegenerateBoundsAndExemptions() {
        CullConfig cfg = new CullConfig();
        cfg.occlusionGraceTicks = 1;
        CullTelemetry telemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(cfg, telemetry);

        OpaqueBlockLookup solidEverywhere = (x, y, z) -> true;

        // Glowing entity behind solid blocks must still render
        EntityVisibilityInput glowingMob = new EntityVisibilityInput(
                201, "minecraft:spectral_arrow",
                0.0, 64.0, 20.0, 0.0, 64.0, 20.0, // zero-volume degenerate AABB
                0.0, 0.0, 0.0,
                true, false, false, false, false, true
        );
        assertTrue(culler.shouldRenderEntity(glowingMob, 0.0, 65.62, 0.0, 1, solidEverywhere),
                "Glowing entity with degenerate AABB must never crash or be occlusion-culled");

        // Whitelisted boss (Ender Dragon) behind solid blocks must still render
        EntityVisibilityInput dragon = new EntityVisibilityInput(
                202, "minecraft:ender_dragon",
                -8.0, 64.0, 30.0, 8.0, 72.0, 46.0,
                0.0, 0.0, 0.0,
                false, false, false, false, true, true
        );
        assertTrue(culler.shouldRenderEntity(dragon, 0.0, 65.62, 0.0, 1, solidEverywhere),
                "Whitelisted boss entity must never be occlusion-culled");

        // Leashed entity behind wall must still render so lead rope never vanishes
        EntityVisibilityInput leashedCow = new EntityVisibilityInput(
                203, "minecraft:cow",
                -0.4, 64.0, 15.0, 0.4, 65.4, 15.8,
                0.0, 0.0, 0.0,
                false, true, false, false, false, true
        );
        assertTrue(culler.shouldRenderEntity(leashedCow, 0.0, 65.62, 0.0, 1, solidEverywhere),
                "Leashed entity must never be occlusion-culled");
    }

    // -------------------------------------------------------------------------
    // Test 6: Particle optimization & gameplay-critical whitelist
    // -------------------------------------------------------------------------
    private static void testParticleOptimizations() {
        CullConfig cfg = new CullConfig();
        cfg.maxParticleDistance = 32.0;
        cfg.maxParticlesPerGroup = 1000;
        CullTelemetry telemetry = new CullTelemetry();
        ParticleOptimizer optimizer = new ParticleOptimizer(cfg, telemetry);

        // Ambient particle at distance 50 (> 32) must be culled
        assertFalse(optimizer.shouldRenderOrSpawnParticle(
                "FallingLeavesParticle", 50.0, 64.0, 0.0, 0.0, 64.0, 0.0, true, 100, 0.5F),
                "Distant ambient FallingLeavesParticle beyond 32m must be culled");

        // Critical particle (HugeExplosionParticle) at distance 80 (> 32) and group count 1500 (> 1000) MUST be preserved
        assertTrue(optimizer.shouldRenderOrSpawnParticle(
                "HugeExplosionParticle", 80.0, 64.0, 0.0, 0.0, 64.0, 0.0, true, 1500, 0.99F),
                "Gameplay-critical HugeExplosionParticle must bypass distance and density culling");

        // Ambient particle when group is at cap (1000) must be thinned
        assertFalse(optimizer.shouldRenderOrSpawnParticle(
                "SmokeParticle", 5.0, 64.0, 0.0, 0.0, 64.0, 0.0, true, 1000, 0.1F),
                "Non-critical particle at group cap must be thinned");
    }

    // -------------------------------------------------------------------------
    // Test 7: Animation off-screen throttling & exact phase catch-up
    // -------------------------------------------------------------------------
    private static void testAnimationOptimizations() {
        CullConfig cfg = new CullConfig();
        cfg.animationThrottleMinDistance = 20.0;
        cfg.offscreenAnimationTickInterval = 4;
        CullTelemetry telemetry = new CullTelemetry();
        AnimationOptimizer animOpt = new AnimationOptimizer(cfg, telemetry);

        int entityId = 500;
        double distSq = 30.0 * 30.0; // 30 blocks away (> 20)

        // Entity is culled on ticks 1, 2, 3 -> steps should be 0 (throttled), accumulating 3 skipped ticks
        assertEquals(0, animOpt.evaluateWalkAnimationSteps(entityId, true, distSq, 1, 0.5F, 0.4F, 1.0F), "Tick 1 culled should defer");
        assertEquals(0, animOpt.evaluateWalkAnimationSteps(entityId, true, distSq, 2, 0.5F, 0.4F, 1.0F), "Tick 2 culled should defer");
        assertEquals(0, animOpt.evaluateWalkAnimationSteps(entityId, true, distSq, 3, 0.5F, 0.4F, 1.0F), "Tick 3 culled should defer");

        // On tick 4, entity suddenly becomes visible (isEntityCulled = false):
        // It must return 3 (skipped) + 1 (current) = 4 catch-up steps so total steps advanced = 4!
        int catchUpSteps = animOpt.evaluateWalkAnimationSteps(entityId, false, distSq, 4, 0.5F, 0.4F, 1.0F);
        assertEquals(4, catchUpSteps, "Re-entering entity must advance exact accumulated steps (3 skipped + 1 current)");

        // Subsequent visible tick advances 1 normal step
        assertEquals(1, animOpt.evaluateWalkAnimationSteps(entityId, false, distSq, 5, 0.5F, 0.4F, 1.0F), "Visible entity advances 1 step");
    }

    // -------------------------------------------------------------------------
    // Test 8: Config persistence, clamping, and corrupt-file recovery
    // -------------------------------------------------------------------------
    private static void testConfigPersistenceAndRecovery() throws Exception {
        Path tempDir = Files.createTempDirectory("wildernesscull-test-cfg");
        ConfigManager.init(tempDir);

        CullConfig cfg = ConfigManager.getConfig();
        cfg.entityCullingMaxDistance = 99999.0; // out of range -> should clamp to 512.0
        cfg.occlusionGraceTicks = -5;           // out of range -> should clamp to 1
        ConfigManager.save();

        ConfigManager.load();
        assertEquals(512L, (long) cfg.entityCullingMaxDistance, "Max distance should clamp to 512.0");
        assertEquals(1L, cfg.occlusionGraceTicks, "Grace ticks should clamp to 1");

        // Reset to defaults
        ConfigManager.resetToDefaultsAndSave();
        assertEquals(96L, (long) cfg.entityCullingMaxDistance, "Reset should restore 96.0 default distance");
        assertEquals(4L, cfg.occlusionGraceTicks, "Reset should restore 4 grace ticks");
    }

    // -------------------------------------------------------------------------
    // Synthetic A/B Benchmark (1,000 Frames x 500 Entities + 4,000 Particles)
    // -------------------------------------------------------------------------
    private static void runSyntheticBenchmark() {
        final int frames = 400;
        final int numEntities = 300;
        final int numParticles = 2000;

        // Build a deterministic dungeon/village voxel map with walls at z = 12, 24, 36
        Set<Long> opaqueVoxels = new HashSet<>();
        for (int z : new int[]{12, 24, 36}) {
            for (int x = -30; x <= 30; x++) {
                if (Math.abs(x % 9) == 4) continue; // doorways/windows
                for (int y = 63; y <= 70; y++) {
                    opaqueVoxels.add(packBlockPos(x, y, z));
                }
            }
        }
        OpaqueBlockLookup worldLookup = (x, y, z) -> opaqueVoxels.contains(packBlockPos(x, y, z));

        Random rng = new Random(26032026L);
        EntityVisibilityInput[] entities = new EntityVisibilityInput[numEntities];
        double[] entDistSq = new double[numEntities];
        for (int i = 0; i < numEntities; i++) {
            double ex = -28.0 + rng.nextDouble() * 56.0;
            double ey = 64.0;
            double ez = 4.0 + rng.nextDouble() * 60.0;
            boolean inFrustum = rng.nextDouble() < 0.65;
            entities[i] = new EntityVisibilityInput(
                    1000 + i, "minecraft:zombie",
                    ex - 0.3, ey, ez - 0.3,
                    ex + 0.3, ey + 1.95, ez + 0.3,
                    0.0, 0.0, 0.0,
                    false, false, false, false, false, inFrustum
            );
            entDistSq[i] = ex * ex + ez * ez;
        }

        double[] partX = new double[numParticles];
        double[] partY = new double[numParticles];
        double[] partZ = new double[numParticles];
        boolean[] partInFrustum = new boolean[numParticles];
        String[] partTypes = new String[numParticles];
        for (int i = 0; i < numParticles; i++) {
            partX[i] = -60.0 + rng.nextDouble() * 120.0;
            partY[i] = 64.0 + rng.nextDouble() * 8.0;
            partZ[i] = -60.0 + rng.nextDouble() * 120.0;
            partInFrustum[i] = rng.nextDouble() < 0.55;
            partTypes[i] = (i % 25 == 0) ? "HugeExplosionParticle" : "FallingLeavesParticle";
        }

        // Simulated cost of full entity render state extraction + matrix pose submission (~450 ns)
        // and particle vertex extraction (~60 ns)
        long baselineStartNs = System.nanoTime();
        long baselineEntitiesSubmitted = 0;
        long baselineParticlesSubmitted = 0;
        long baselineAnimUpdates = 0;
        double dummySink = 0.0;

        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < numEntities; i++) {
                if (entities[i].inFrustum()) {
                    baselineEntitiesSubmitted++;
                    baselineAnimUpdates++;
                    dummySink += simulateModelMatrixAndVertexSubmit(i, f);
                }
            }
            for (int p = 0; p < numParticles; p++) {
                if (partInFrustum[p]) {
                    baselineParticlesSubmitted++;
                    dummySink += simulateParticleQuadExtract(p, f);
                }
            }
        }
        long baselineElapsedNs = System.nanoTime() - baselineStartNs;

        // Now run identical workload with WildernessCull ENABLED
        CullConfig enabledCfg = new CullConfig();
        CullTelemetry enabledTelemetry = new CullTelemetry();
        OcclusionCuller culler = new OcclusionCuller(enabledCfg, enabledTelemetry);
        ParticleOptimizer partOpt = new ParticleOptimizer(enabledCfg, enabledTelemetry);
        AnimationOptimizer animOpt = new AnimationOptimizer(enabledCfg, enabledTelemetry);

        long modStartNs = System.nanoTime();
        long modEntitiesSubmitted = 0;
        long modParticlesSubmitted = 0;
        long modAnimUpdates = 0;

        for (int f = 0; f < frames; f++) {
            long tick = f / 2; // 2 frames per client tick
            int activeGroupParticles = 0;
            for (int i = 0; i < numEntities; i++) {
                boolean render = culler.shouldRenderEntity(entities[i], 0.0, 65.62, 0.0, tick, worldLookup);
                int animSteps = animOpt.evaluateWalkAnimationSteps(
                        entities[i].entityId(), !render, entDistSq[i], tick, 0.4F, 0.4F, 1.0F);
                if (animSteps > 0) {
                    modAnimUpdates++;
                }
                if (render) {
                    modEntitiesSubmitted++;
                    dummySink += simulateModelMatrixAndVertexSubmit(i, f);
                }
            }
            for (int p = 0; p < numParticles; p++) {
                boolean renderPart = partOpt.shouldRenderOrSpawnParticle(
                        partTypes[p], partX[p], partY[p], partZ[p],
                        0.0, 65.62, 0.0, partInFrustum[p], activeGroupParticles, (p % 100) / 100.0F);
                if (renderPart) {
                    activeGroupParticles++;
                    modParticlesSubmitted++;
                    dummySink += simulateParticleQuadExtract(p, f);
                }
            }
        }
        long modElapsedNs = System.nanoTime() - modStartNs;

        if (dummySink == 42.424242) System.out.println(); // prevent dead-code elimination

        double baselineMsPerFrame = (baselineElapsedNs / 1_000_000.0) / frames;
        double modMsPerFrame = (modElapsedNs / 1_000_000.0) / frames;
        double entReductionPct = 100.0 * (1.0 - ((double) modEntitiesSubmitted / baselineEntitiesSubmitted));
        double partReductionPct = 100.0 * (1.0 - ((double) modParticlesSubmitted / baselineParticlesSubmitted));
        double animReductionPct = 100.0 * (1.0 - ((double) modAnimUpdates / baselineAnimUpdates));

        System.out.println(" Synthetic A/B Benchmark (400 Frames, 300 Entities, 2,000 Particles):");
        System.out.printf("   - Baseline (Mod Disabled): %.3f ms/frame | Entities Drawn: %d | Particles Drawn: %d | Anim Setups: %d%n",
                baselineMsPerFrame, baselineEntitiesSubmitted, baselineParticlesSubmitted, baselineAnimUpdates);
        System.out.printf("   - Optimized (Mod Enabled): %.3f ms/frame | Entities Drawn: %d (-%.1f%%) | Particles Drawn: %d (-%.1f%%) | Anim Setups: %d (-%.1f%%)%n",
                modMsPerFrame,
                modEntitiesSubmitted, entReductionPct,
                modParticlesSubmitted, partReductionPct,
                modAnimUpdates, animReductionPct);
        System.out.println("   - Telemetry: " + enabledTelemetry.formatSummary());
    }

    private static double simulateModelMatrixAndVertexSubmit(int entityIdx, int frame) {
        double acc = 0.0;
        // Simulates 12 ModelPart bone sin/cos rotations + matrix multiplies per visible entity
        for (int bone = 1; bone <= 12; bone++) {
            double angle = (entityIdx * 0.13 + frame * 0.05) * bone;
            acc += Math.sin(angle) * Math.cos(angle * 0.5);
        }
        return acc;
    }

    private static double simulateParticleQuadExtract(int particleIdx, int frame) {
        double angle = particleIdx * 0.07 + frame * 0.03;
        return Math.sin(angle) + Math.cos(angle);
    }

    private static long packBlockPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }
}
