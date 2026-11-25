package dev.wildernesscull.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight telemetry counters used for in-game diagnostics ({@code /wcull stats})
 * and reproducible A/B benchmarking.
 */
public final class CullTelemetry {

    private final AtomicLong entitiesEvaluated = new AtomicLong();
    private final AtomicLong entitiesRendered = new AtomicLong();
    private final AtomicLong entitiesFrustumCulled = new AtomicLong();
    private final AtomicLong entitiesOcclusionCulled = new AtomicLong();
    private final AtomicLong entitiesGraceProtected = new AtomicLong();

    private final AtomicLong particlesEvaluated = new AtomicLong();
    private final AtomicLong particlesRendered = new AtomicLong();
    private final AtomicLong particlesCulledDistance = new AtomicLong();
    private final AtomicLong particlesCulledFrustum = new AtomicLong();
    private final AtomicLong particlesThinnedDensity = new AtomicLong();

    private final AtomicLong animationsEvaluated = new AtomicLong();
    private final AtomicLong animationsThrottled = new AtomicLong();
    private final AtomicLong animationsReentrySynced = new AtomicLong();

    public void recordEntityEvaluated() { entitiesEvaluated.incrementAndGet(); }
    public void recordEntityRendered() { entitiesRendered.incrementAndGet(); }
    public void recordEntityFrustumCulled() { entitiesFrustumCulled.incrementAndGet(); }
    public void recordEntityOcclusionCulled() { entitiesOcclusionCulled.incrementAndGet(); }
    public void recordEntityGraceProtected() { entitiesGraceProtected.incrementAndGet(); }

    public void recordParticleEvaluated() { particlesEvaluated.incrementAndGet(); }
    public void recordParticleRendered() { particlesRendered.incrementAndGet(); }
    public void recordParticleCulledDistance() { particlesCulledDistance.incrementAndGet(); }
    public void recordParticleCulledFrustum() { particlesCulledFrustum.incrementAndGet(); }
    public void recordParticleThinnedDensity() { particlesThinnedDensity.incrementAndGet(); }

    public void recordAnimationEvaluated() { animationsEvaluated.incrementAndGet(); }
    public void recordAnimationThrottled() { animationsThrottled.incrementAndGet(); }
    public void recordAnimationReentrySynced() { animationsReentrySynced.incrementAndGet(); }

    public long getEntitiesEvaluated() { return entitiesEvaluated.get(); }
    public long getEntitiesRendered() { return entitiesRendered.get(); }
    public long getEntitiesFrustumCulled() { return entitiesFrustumCulled.get(); }
    public long getEntitiesOcclusionCulled() { return entitiesOcclusionCulled.get(); }
    public long getEntitiesGraceProtected() { return entitiesGraceProtected.get(); }

    public long getParticlesEvaluated() { return particlesEvaluated.get(); }
    public long getParticlesRendered() { return particlesRendered.get(); }
    public long getParticlesCulledDistance() { return particlesCulledDistance.get(); }
    public long getParticlesCulledFrustum() { return particlesCulledFrustum.get(); }
    public long getParticlesThinnedDensity() { return particlesThinnedDensity.get(); }

    public long getAnimationsEvaluated() { return animationsEvaluated.get(); }
    public long getAnimationsThrottled() { return animationsThrottled.get(); }
    public long getAnimationsReentrySynced() { return animationsReentrySynced.get(); }

    public void reset() {
        entitiesEvaluated.set(0);
        entitiesRendered.set(0);
        entitiesFrustumCulled.set(0);
        entitiesOcclusionCulled.set(0);
        entitiesGraceProtected.set(0);

        particlesEvaluated.set(0);
        particlesRendered.set(0);
        particlesCulledDistance.set(0);
        particlesCulledFrustum.set(0);
        particlesThinnedDensity.set(0);

        animationsEvaluated.set(0);
        animationsThrottled.set(0);
        animationsReentrySynced.set(0);
    }

    public String formatSummary() {
        long entTotal = Math.max(1, entitiesEvaluated.get());
        long partTotal = Math.max(1, particlesEvaluated.get());
        long animTotal = Math.max(1, animationsEvaluated.get());

        double entCullPct = (entitiesOcclusionCulled.get() * 100.0) / entTotal;
        double partCullPct = ((particlesCulledDistance.get() + particlesCulledFrustum.get() + particlesThinnedDensity.get()) * 100.0) / partTotal;
        double animThrottlePct = (animationsThrottled.get() * 100.0) / animTotal;

        return String.format(
                "Entities[eval=%d, rendered=%d, frustumCulled=%d, occlusionCulled=%d (%.1f%%), hysteresisSaved=%d] | " +
                "Particles[eval=%d, rendered=%d, distCulled=%d, frustumCulled=%d, densityThinned=%d (%.1f%%)] | " +
                "Animations[eval=%d, throttled=%d (%.1f%%), reentrySynced=%d]",
                entitiesEvaluated.get(), entitiesRendered.get(), entitiesFrustumCulled.get(),
                entitiesOcclusionCulled.get(), entCullPct, entitiesGraceProtected.get(),
                particlesEvaluated.get(), particlesRendered.get(), particlesCulledDistance.get(),
                particlesCulledFrustum.get(), particlesThinnedDensity.get(), partCullPct,
                animationsEvaluated.get(), animationsThrottled.get(), animThrottlePct, animationsReentrySynced.get()
        );
    }
}
