package dev.wildernesscull.culling;

import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.util.CullTelemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative 3D occlusion and visibility engine for entities.
 *
 * <h3>Design Guarantees (Why it never flickers or hides visible entities):</h3>
 * <ol>
 *   <li><b>Gameplay-Critical Exemptions:</b> Bosses, glowing entities, leashed entities,
 *       entities with custom visible nametags, entities ridden by or riding the player,
 *       and whitelisted entity IDs are never culled.</li>
 *   <li><b>Dynamic Bounding-Box Expansion:</b> The entity's culling AABB is inflated by
 *       {@code hitboxExpansionMargin} (default 0.35 blocks) and stretched along its velocity
 *       vector when moving fast, preventing held items, banners, or fast-moving mobs from
 *       clipping into view a frame late.</li>
 *   <li><b>9-Point Target Sampling (Center + 8 Corners):</b> An entity is considered occluded
 *       for a tick ONLY if the 3D Amanatides-Woo DDA voxel traversal from the camera eye
 *       to the AABB center AND all 8 expanded AABB corners is blocked by a strictly opaque
 *       full-cube block. If even ONE corner has line-of-sight, the entity renders immediately.</li>
 *   <li><b>Transparent Block Awareness:</b> Glass, leaves, fences, slabs, stairs, water, portals,
 *       bamboo, cobwebs, barriers, and non-full-cube blocks NEVER count as occluders.</li>
 *   <li><b>Temporal Hysteresis (Anti-Flicker Grace Window):</b> When an entity transitions from
 *       visible to occluded, it continues rendering for {@code occlusionGraceTicks} (default 4 ticks).
 *       Transitioning from occluded to visible is instantaneous (0 ticks delay).</li>
 *   <li><b>Camera Motion & Whip-Pan Guard:</b> When the camera rotates faster than
 *       {@code fastCameraTurnThresholdDeg} per tick, cached occlusion results are invalidated
 *       and an extra velocity margin is added to the AABB.</li>
 * </ol>
 */
public final class OcclusionCuller {

    /**
     * Functional interface representing read-only query access to the client block grid.
     * Returns {@code true} ONLY if the block at (x, y, z) is a full 1x1x1 opaque cube that
     * completely blocks vision across all faces (e.g., stone, dirt, planks — NEVER glass,
     * leaves, slabs, stairs, fences, doors, chests, or fluids).
     */
    @FunctionalInterface
    public interface OpaqueBlockLookup {
        boolean isFullOpaqueCube(int blockX, int blockY, int blockZ);
    }

    /**
     * Immutable snapshot of an entity's state for visibility evaluation.
     */
    public record EntityVisibilityInput(
            int entityId,
            String entityTypeId,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double velX, double velY, double velZ,
            boolean isGlowing,
            boolean isLeashed,
            boolean hasCustomName,
            boolean isPassengerOrVehicleOfCamera,
            boolean isBossOrExempt,
            boolean inFrustum
    ) {}

    private static final class EntityCullState {
        long lastEvaluatedTick = -1;
        boolean lastRawOccluded = false;
        int consecutiveOccludedTicks = 0;
        boolean currentlyCulled = false;
        double lastCamX, lastCamY, lastCamZ;
        double lastEntX, lastEntY, lastEntZ;
    }

    private final CullConfig config;
    private final CullTelemetry telemetry;
    private final Map<Integer, EntityCullState> stateCache = new ConcurrentHashMap<>();

    private double prevCamYaw = 0.0;
    private double prevCamPitch = 0.0;
    private boolean cameraTurningFast = false;

    public OcclusionCuller(CullConfig config, CullTelemetry telemetry) {
        this.config = config;
        this.telemetry = telemetry;
    }

    /**
     * Called once per client frame/tick to track camera rotation speed.
     */
    public void updateCameraMotion(float yawDegrees, float pitchDegrees) {
        double dYaw = Math.abs(wrapDegrees(yawDegrees - prevCamYaw));
        double dPitch = Math.abs(pitchDegrees - prevCamPitch);
        double angularSpeed = Math.hypot(dYaw, dPitch);
        this.cameraTurningFast = angularSpeed >= config.fastCameraTurnThresholdDeg;
        this.prevCamYaw = yawDegrees;
        this.prevCamPitch = pitchDegrees;
    }

    public boolean isCameraTurningFast() {
        return cameraTurningFast;
    }

    public void clearCache() {
        stateCache.clear();
    }

    public void removeEntity(int entityId) {
        stateCache.remove(entityId);
    }

    /**
     * Returns {@code true} if the entity is currently marked as culled (either outside frustum
     * or confirmed occluded past the grace window). Used by AnimationOptimizer to skip
     * unnecessary bone setup.
     */
    public boolean isEntityCurrentlyCulled(int entityId) {
        EntityCullState state = stateCache.get(entityId);
        return state != null && state.currentlyCulled;
    }

    /**
     * Evaluates whether an entity should be rendered in the current frame.
     *
     * @return {@code true} if the entity MUST be rendered; {@code false} ONLY if it is confirmed
     *         invisible and safe to skip.
     */
    public boolean shouldRenderEntity(
            EntityVisibilityInput input,
            double camX, double camY, double camZ,
            long currentTick,
            OpaqueBlockLookup blockLookup
    ) {
        if (config.collectTelemetry) {
            telemetry.recordEntityEvaluated();
        }

        // 1. Master switches: if mod or entity culling is disabled, defer to vanilla frustum check
        if (!config.modEnabled || !config.entityCullingEnabled) {
            if (input.inFrustum()) {
                if (config.collectTelemetry) telemetry.recordEntityRendered();
                return true;
            } else {
                if (config.collectTelemetry) telemetry.recordEntityFrustumCulled();
                return false;
            }
        }

        // 2. Gameplay-critical exemptions
        if (config.preserveGameplayCriticalEntities) {
            if (input.isBossOrExempt()
                    || input.isGlowing()
                    || input.isLeashed()
                    || input.hasCustomName()
                    || input.isPassengerOrVehicleOfCamera()
                    || config.entityWhitelist.contains(input.entityTypeId())) {
                EntityCullState state = stateCache.computeIfAbsent(input.entityId(), k -> new EntityCullState());
                state.consecutiveOccludedTicks = 0;
                state.currentlyCulled = !input.inFrustum();
                if (input.inFrustum()) {
                    if (config.collectTelemetry) telemetry.recordEntityRendered();
                    return true;
                } else {
                    if (config.collectTelemetry) telemetry.recordEntityFrustumCulled();
                    return false;
                }
            }
        }

        // 3. Vanilla frustum check (already uses EntityRenderer.getBoundingBoxForCulling)
        if (!input.inFrustum()) {
            EntityCullState state = stateCache.computeIfAbsent(input.entityId(), k -> new EntityCullState());
            state.currentlyCulled = true;
            if (config.collectTelemetry) telemetry.recordEntityFrustumCulled();
            return false;
        }

        // 4. Compute entity center and distance to camera
        double centerX = (input.minX() + input.maxX()) * 0.5;
        double centerY = (input.minY() + input.maxY()) * 0.5;
        double centerZ = (input.minZ() + input.maxZ()) * 0.5;

        double dx = centerX - camX;
        double dy = centerY - camY;
        double dz = centerZ - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        // Entities very close to the camera (< 2.5 blocks) or beyond max raycast distance
        // are never occlusion-culled (beyond max distance, vanilla entity distance limit handles them).
        double maxDist = config.entityCullingMaxDistance;
        if (distSq < 6.25 || distSq > maxDist * maxDist) {
            EntityCullState state = stateCache.computeIfAbsent(input.entityId(), k -> new EntityCullState());
            state.consecutiveOccludedTicks = 0;
            state.currentlyCulled = false;
            if (config.collectTelemetry) telemetry.recordEntityRendered();
            return true;
        }

        EntityCullState state = stateCache.computeIfAbsent(input.entityId(), k -> new EntityCullState());

        // 5. Check if cached occlusion result from recent tick is still valid
        double camMoveSq = sq(camX - state.lastCamX) + sq(camY - state.lastCamY) + sq(camZ - state.lastCamZ);
        double entMoveSq = sq(centerX - state.lastEntX) + sq(centerY - state.lastEntY) + sq(centerZ - state.lastEntZ);
        boolean cacheValid = !cameraTurningFast
                && state.lastEvaluatedTick >= 0
                && (currentTick - state.lastEvaluatedTick) < config.occlusionCacheTtlTicks
                && camMoveSq < 0.04 // camera moved < 0.2 blocks
                && entMoveSq < 0.04; // entity moved < 0.2 blocks

        boolean rawOccluded;
        if (cacheValid) {
            rawOccluded = state.lastRawOccluded;
        } else {
            // Compute conservative expanded AABB
            double margin = config.hitboxExpansionMargin + (cameraTurningFast ? 0.25 : 0.0);
            double velSq = input.velX() * input.velX() + input.velY() * input.velY() + input.velZ() * input.velZ();
            double leadX = velSq >= config.movingEntityVelocitySqThreshold ? Math.abs(input.velX()) * 1.5 : 0.0;
            double leadY = velSq >= config.movingEntityVelocitySqThreshold ? Math.abs(input.velY()) * 1.5 : 0.0;
            double leadZ = velSq >= config.movingEntityVelocitySqThreshold ? Math.abs(input.velZ()) * 1.5 : 0.0;

            // Handle unusual or degenerate entity bounds by enforcing a minimum 0.5x0.5x0.5 test volume
            double halfW = Math.max(0.25, (input.maxX() - input.minX()) * 0.5) + margin + leadX;
            double halfH = Math.max(0.25, (input.maxY() - input.minY()) * 0.5) + margin + leadY;
            double halfD = Math.max(0.25, (input.maxZ() - input.minZ()) * 0.5) + margin + leadZ;

            double expMinX = centerX - halfW;
            double expMaxX = centerX + halfW;
            double expMinY = centerY - halfH;
            double expMaxY = centerY + halfH;
            double expMinZ = centerZ - halfD;
            double expMaxZ = centerZ + halfD;

            rawOccluded = isBoxCompletelyOccluded(
                    camX, camY, camZ,
                    centerX, centerY, centerZ,
                    expMinX, expMinY, expMinZ,
                    expMaxX, expMaxY, expMaxZ,
                    blockLookup
            );

            state.lastEvaluatedTick = currentTick;
            state.lastRawOccluded = rawOccluded;
            state.lastCamX = camX;
            state.lastCamY = camY;
            state.lastCamZ = camZ;
            state.lastEntX = centerX;
            state.lastEntY = centerY;
            state.lastEntZ = centerZ;
        }

        // 6. Temporal Hysteresis:
        //    - If visible now -> render immediately and reset consecutiveOccludedTicks to 0.
        //    - If occluded now -> increment consecutiveOccludedTicks on new ticks and only cull
        //      once consecutiveOccludedTicks >= occlusionGraceTicks.
        if (!rawOccluded) {
            state.consecutiveOccludedTicks = 0;
            state.currentlyCulled = false;
            if (config.collectTelemetry) telemetry.recordEntityRendered();
            return true;
        }

        if (!cacheValid) {
            state.consecutiveOccludedTicks++;
        }

        if (state.consecutiveOccludedTicks < config.occlusionGraceTicks) {
            state.currentlyCulled = false;
            if (config.collectTelemetry) {
                telemetry.recordEntityGraceProtected();
                telemetry.recordEntityRendered();
            }
            return true;
        }

        state.currentlyCulled = true;
        if (config.collectTelemetry) {
            telemetry.recordEntityOcclusionCulled();
        }
        return false;
    }

    /**
     * Tests 9 points on the expanded bounding box (center + 8 corners).
     * Returns {@code true} ONLY if EVERY single ray is blocked by at least one opaque full-cube block.
     */
    private static boolean isBoxCompletelyOccluded(
            double camX, double camY, double camZ,
            double centerX, double centerY, double centerZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            OpaqueBlockLookup blockLookup
    ) {
        // Test center first: if center is visible, short-circuit immediately (fast path for visible entities)
        if (!isRayOccluded(camX, camY, camZ, centerX, centerY, centerZ, blockLookup)) {
            return false;
        }
        // Test top-center and bottom-center next (common visibility points over walls/fences)
        if (!isRayOccluded(camX, camY, camZ, centerX, maxY, centerZ, blockLookup)) {
            return false;
        }
        // Test all 8 corners of the expanded AABB
        if (!isRayOccluded(camX, camY, camZ, minX, minY, minZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, maxX, minY, minZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, minX, maxY, minZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, maxX, maxY, minZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, minX, minY, maxZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, maxX, minY, maxZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, minX, maxY, maxZ, blockLookup)) return false;
        if (!isRayOccluded(camX, camY, camZ, maxX, maxY, maxZ, blockLookup)) return false;

        return true;
    }

    /**
     * 3D Amanatides-Woo Digital Differential Analyzer (DDA) voxel ray traversal.
     * Zero heap allocations per ray.
     *
     * @return {@code true} if the ray from (x0, y0, z0) to (x1, y1, z1) hits an opaque full-cube
     *         block before reaching the target voxel.
     */
    public static boolean isRayOccluded(
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            OpaqueBlockLookup blockLookup
    ) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        if (lengthSq < 1.0E-4) {
            return false;
        }

        int curX = fastFloor(x0);
        int curY = fastFloor(y0);
        int curZ = fastFloor(z0);

        int endX = fastFloor(x1);
        int endY = fastFloor(y1);
        int endZ = fastFloor(z1);

        if (curX == endX && curY == endY && curZ == endZ) {
            return false;
        }

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX = stepX > 0 ? (curX + 1.0 - x0) * tDeltaX : (stepX < 0 ? (x0 - curX) * tDeltaX : Double.MAX_VALUE);
        double tMaxY = stepY > 0 ? (curY + 1.0 - y0) * tDeltaY : (stepY < 0 ? (y0 - curY) * tDeltaY : Double.MAX_VALUE);
        double tMaxZ = stepZ > 0 ? (curZ + 1.0 - z0) * tDeltaZ : (stepZ < 0 ? (z0 - curZ) * tDeltaZ : Double.MAX_VALUE);

        int maxSteps = 256;
        for (int step = 0; step < maxSteps; step++) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX >= 1.0) break;
                    curX += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ >= 1.0) break;
                    curZ += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY >= 1.0) break;
                    curY += stepY;
                    tMaxY += tDeltaY;
                } else {
                    if (tMaxZ >= 1.0) break;
                    curZ += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }

            // Do not treat the target voxel itself as occluding the entity inside it
            if (curX == endX && curY == endY && curZ == endZ) {
                return false;
            }

            if (blockLookup.isFullOpaqueCube(curX, curY, curZ)) {
                return true;
            }
        }

        return false;
    }

    private static int fastFloor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
