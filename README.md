# WildernessCull (Minecraft 26.3, Fabric, client-only)

**Status: not a working optimizer yet.** Read this first.

- **No in-game optimization is active.** The four mixins target members that could not be confirmed
  for 26.3 (see `VERIFICATION.md`). They live in `unverified/mixin/` and are not compiled or loaded.
- **Working parts:** the config file, the keybinds, and the `/wcull` commands. The toggles change
  saved settings, but nothing changes in game until the hooks are enabled.
- **Tested in isolation:** the occlusion, particle, animation, config and telemetry logic does not
  touch Minecraft. It compiles and runs standalone. The suite passes 7 of 8 checks (see Known issues).
- **Not yet run:** the project has never been compiled against the real Minecraft 26.3 jars or started
  in a game. The first real test is `./gradlew jar` on your machine.

## Pinned toolchain

| Item | Version |
|---|---|
| Minecraft | 26.3 |
| Java | 25 |
| Gradle (wrapper) | 9.8.2 |
| Fabric Loom | 1.19.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.2+26.3 |

Each pin and its source is listed in `VERIFICATION.md`.

## Build

Requires JDK 25, with `JAVA_HOME` pointing at it. Check with `java -version`.

```
./gradlew jar        # Linux/macOS
gradlew.bat jar      # Windows
```

The mod is `build/libs/wilderness-cull-1.0.0+mc26.3.jar`. Ignore the `-sources.jar` next to it.

Use `jar`, not `build`. `build` also runs the tests, and one test currently fails (see Known issues).

Standalone check without Gradle (no Minecraft needed):

```
mkdir -p build/standalone-classes
javac -d build/standalone-classes \
  src/main/java/dev/wildernesscull/config/*.java \
  src/main/java/dev/wildernesscull/util/*.java \
  src/main/java/dev/wildernesscull/culling/*.java \
  src/main/java/dev/wildernesscull/particle/*.java \
  src/main/java/dev/wildernesscull/animation/*.java \
  src/test/java/dev/wildernesscull/WildernessCullVerificationSuite.java
java -cp build/standalone-classes dev.wildernesscull.WildernessCullVerificationSuite
```

## Install

1. Install Fabric Loader 0.20.1 or newer for Minecraft 26.3, with Java 25.
2. Put `wilderness-cull-1.0.0+mc26.3.jar` and Fabric API `0.165.2+26.3` in `.minecraft/mods/`.
3. Remove `entityculling` or `moreculling` if installed. The mod declares a conflict with `entityculling`.
4. Install on the client only. The mod is marked `"environment": "client"`.

## Configure

- File: `.minecraft/config/wildernesscull.properties` (created on first launch, with comments).
- Keybinds (Options → Controls → Key Binds → WildernessCull Performance): F9 toggles culling,
  F10 prints telemetry, and the reset key is unbound by default.
- Commands: `/wcull stats`, `/wcull resetstats`, `/wcull reset`,
  `/wcull toggle culling|particles|animations <true|false>`,
  `/wcull set graceTicks|maxEntityDistance|maxParticleDistance <value>`.

## Uninstall

Delete `wilderness-cull-1.0.0+mc26.3.jar` from `.minecraft/mods/`. Optionally delete
`.minecraft/config/wildernesscull.properties`. The mod only reads settings and does not write to saves,
worlds or servers.

## Enabling the optimizations (after verification)

Run these steps once a build exists. The merged Minecraft jar is cached by Loom under `~/.gradle/caches`.

1. Find the merged jar: `find ~/.gradle/caches -name "*.jar" | grep -i merged`.
2. Check each unverified member with `javap -p -cp <merged.jar> <class>`:
   - `net.minecraft.client.renderer.entity.EntityRenderer`: confirm `shouldRender` and its parameter list.
   - `net.minecraft.client.particle.ParticleEngine`: confirm `add(Particle)`.
   - `net.minecraft.client.particle.QuadParticleGroup`: confirm `particleType` and `particleTypeRenderState`.
   - `net.minecraft.client.renderer.entity.LivingEntityRenderer` and `net.minecraft.world.entity.WalkAnimationState`.
3. For each mixin that passes, move it back to `src/main/java/dev/wildernesscull/mixin/`, fix any
   signatures that differ, and add its class name to `"client"` in `wildernesscull.mixins.json`.
4. Rebuild with `./gradlew jar` and launch the client. Check `.minecraft/logs/latest.log` for
   `Mixin apply failed` or `InvalidInjectionException`. Any such error means that mixin's target is still wrong.

## Benchmark procedure (for after the hooks are enabled)

Compare the mod off and on under the same conditions:

1. Same world, same seed, same position, same time of day, same render distance (for example 12),
   and the same Fabric API and other mods.
2. Stand in the same spot facing a fixed direction, with a set of entities and particles nearby
   (for example a farm with 100+ mobs, or a fight with explosions).
3. Run F3 for FPS, and `/wcull resetstats` before each run. Record average FPS over 60 seconds.
4. Repeat with `/wcull toggle culling false` (and particles/animations) for the baseline.
5. Repeat each run at least three times and compare the averages. Note the entity and particle counts
   from `/wcull stats`.

The standalone suite also prints a synthetic A/B benchmark. It measures the logic only, not the game.

## Known issues

- `WildernessCullVerificationSuite`, test 4 "Camera Whip-Pan & Moving Entity Velocity Expansion",
  fails. A fast-moving entity near a wall edge can be culled too early. This is a logic bug in
  `OcclusionCuller`, and it has not been fixed. It is why `./gradlew build` fails.
- The four mixins are disabled because their targets are unverified (`VERIFICATION.md`).
- The keybind and command code has not been compiled against 26.3, so it may still need fixes.

## Known conflicts (unverified)

These are expected conflicts and have not been tested:

- **Entity culling mods** (`entityculling`, `moreculling`): both hook entity rendering. Use one at a time.
- **Shaders with entity shadows:** culled entities can still cast shadows. Turn culling off with
  `/wcull toggle culling false` if shadows disappear.
- **Sodium:** should not conflict, since the mod does not hook chunk rendering. Untested.
