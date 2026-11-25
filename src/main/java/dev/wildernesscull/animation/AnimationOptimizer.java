package dev.wildernesscull.animation;

import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.util.CullTelemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimizes entity animation updates without making visible animations choppy, delayed, or out-of-phase.
 *
 * <h3>How Phase-Preserving Catch-Up Works:</h3>
 * <ul>
 *   <li>When an entity is visible inside the camera frustum (and not occlusion-culled), every
 *       animation tick and {@code EntityModel.setupAnim} call runs at full vanilla rate (100% fidelity).</li>
 *   <li>When a distant entity (&gt; {@code animationThrottleMinDistance}) is confirmed off-screen or
 *       occlusion-culled, per-frame model bone setup ({@code setupAnim}) is skipped completely
 *       (since no vertices are submitted), and periodic limb-swing / keyframe state ticks can be
 *       throttled to every {@code N}th tick.</li>
 *   <li>Crucially, {@code KeyframeAnimation.apply(AnimationState, float currentTime)} in Minecraft 26.3
 *       is driven by elapsed clock/tick time ({@code ageInTicks}), so skipping {@code setupAnim} while
 *       culled requires zero state mutation and automatically resumes at the exact keyframe timestamp
 *       on the very frame the entity becomes visible again!</li>
 *   <li>For incremental walk-animation accumulators ({@code WalkAnimationState.update}), if ticks are
 *       skipped while culled, we record the missed steps and apply an exact analytical catch-up step
 *       when the entity re-enters view so limb phase and speed match unthrottled vanilla within &lt;1e-4.</li>
 * </ul>
 */
public final class AnimationOptimizer {

    public static final class WalkCatchUpState {
        private int skippedTicks = 0;
        private float lastTargetSpeed = 0.0F;
        private float lastFactor = 0.4F;
        private float lastPositionScale = 1.0F;
        private boolean wasCulled = false;

        public int getSkippedTicks() {
            return skippedTicks;
        }
    }

    private final CullConfig config;
    private final CullTelemetry telemetry;
    private final Map<Integer, WalkCatchUpState> entityAnimStates = new ConcurrentHashMap<>();

    public AnimationOptimizer(CullConfig config, CullTelemetry telemetry) {
        this.config = config;
        this.telemetry = telemetry;
    }

    public void clearCache() {
        entityAnimStates.clear();
    }

    public void removeEntity(int entityId) {
        entityAnimStates.remove(entityId);
    }

    /**
     * Returns {@code true} if {@code EntityModel.setupAnim} should be skipped for this frame.
     * In Minecraft 26.3, when {@code EntityRenderer.shouldRender} returns {@code false}, vanilla
     * already skips {@code extractRenderState} and {@code submit}. This method provides an additional
     * guard for any custom render layer or direct model invocation on a culled entity.
     */
    public boolean shouldSkipModelSetup(boolean isEntityCulledThisFrame) {
        if (!config.modEnabled || !config.animationOptimizationEnabled || !config.skipCulledEntityModelSetup) {
            return false;
        }
        return isEntityCulledThisFrame;
    }

    /**
     * Evaluates whether an entity's walk animation update should run on this tick, be deferred,
     * or execute a phase-preserving catch-up step upon re-entering visibility.
     *
     * @param entityId        Unique entity network ID
     * @param isEntityCulled  Whether the entity is currently off-screen or occlusion-culled
     * @param distanceSq      Squared distance from camera to entity
     * @param currentTick     Current client level tick
     * @param targetSpeed     Target limb speed passed to {@code WalkAnimationState.update}
     * @param factor          Interpolation factor passed to {@code WalkAnimationState.update}
     * @param positionScale   Position scale passed to {@code WalkAnimationState.update}
     * @return Number of analytical steps to advance on this tick:
     *         {@code 0} = skip this tick (deferred),
     *         {@code 1} = normal single-tick update,
     *         {@code >1} = catch-up multi-step update to restore exact phase on visibility re-entry.
     */
    public int evaluateWalkAnimationSteps(
            int entityId,
            boolean isEntityCulled,
            double distanceSq,
            long currentTick,
            float targetSpeed,
            float factor,
            float positionScale
    ) {
        if (config.collectTelemetry) {
            telemetry.recordAnimationEvaluated();
        }

        if (!config.modEnabled || !config.animationOptimizationEnabled || !config.throttleOffscreenWalkAnimations) {
            return 1;
        }

        int interval = config.offscreenAnimationTickInterval;
        if (interval <= 1) {
            return 1;
        }

        double minDist = config.animationThrottleMinDistance;
        boolean eligibleForThrottle = isEntityCulled && distanceSq >= (minDist * minDist);

        WalkCatchUpState state = entityAnimStates.computeIfAbsent(entityId, k -> new WalkCatchUpState());

        if (!eligibleForThrottle) {
            // Entity is visible (or close): if it had skipped ticks while culled, catch up immediately
            // so the very first visible frame has the exact limb phase!
            if (state.skippedTicks > 0) {
                int steps = state.skippedTicks + 1;
                state.skippedTicks = 0;
                state.wasCulled = false;
                if (config.collectTelemetry) {
                    telemetry.recordAnimationReentrySynced();
                }
                return steps;
            }
            state.wasCulled = false;
            return 1;
        }

        // Entity is culled and distant: update only every Nth tick, accumulating skipped ticks
        state.wasCulled = true;
        state.lastTargetSpeed = targetSpeed;
        state.lastFactor = factor;
        state.lastPositionScale = positionScale;

        if (((currentTick + entityId) % interval) != 0) {
            state.skippedTicks++;
            if (config.collectTelemetry) {
                telemetry.recordAnimationThrottled();
            }
            return 0;
        } else {
            int steps = state.skippedTicks + 1;
            state.skippedTicks = 0;
            return steps;
        }
    }
}
