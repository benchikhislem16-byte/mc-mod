# Verification status (Minecraft 26.3, Fabric)

Read this before trusting any claim in the README. Each row says where the fact came from.
"Not found" means no source reachable from the build environment contains it. It does not
mean the member is absent in 26.3.

## Toolchain pins

| Item | Value used | Source |
|---|---|---|
| Minecraft | 26.3 (unobfuscated, Mojang names, no remap) | `FabricMC/fabric-example-mod`, branch `26.3`, commit 91f19010 (`gradle.properties`) |
| Java | 25 (`options.release = 25`) | same template (`build.gradle`) |
| Gradle wrapper | 9.8.2 | same template (`gradle-wrapper.properties`) |
| Fabric Loader | 0.20.1 | `FabricMC/fabric-loader` tag `0.20.1`; same template |
| Fabric API | 0.165.2+26.3 | `FabricMC/fabric` tag `0.165.2+26.3` (newer than the template's 0.165.0+26.3) |
| Fabric Loom | 1.19.3 (stable) | `FabricMC/fabric-loom` tag `v1.19.3`; its source has 26.3-specific handling ("Required since 26.3-snapshot-10"). The template uses `1.19-SNAPSHOT`, which is not used here. |

Not checked here: that the artifacts resolve from Maven. The sandbox could not reach
`maven.fabricmc.net`, so the first `./gradlew jar` on a real machine is the actual test.

## Minecraft and Fabric API members

| Member | Status | Evidence |
|---|---|---|
| `LivingEntityRenderer#extractRenderState(LivingEntity, LivingEntityRenderState, float)V` | Confirmed | Fabric 0.165.2+26.3 `LivingEntityRendererMixin` targets it |
| `ParticleEngine` static `RENDER_ORDER` | Confirmed | Fabric 0.165.2+26.3 `ParticleEngineAccessor` |
| `ParticleEngine#createParticleGroup(ParticleRenderType)` | Confirmed | Fabric 0.165.2+26.3 `ParticleEngineMixin` |
| `ParticleEngine` field `particles` (map of `ParticleRenderType` to `ParticleGroup`) | Confirmed | NeoForge `26.3.x` patch `ParticleEngine.java.patch` (context lines) |
| `ParticleEngine#extract(ParticlesRenderState, Frustum, Camera, float)V` | Confirmed | NeoForge `26.3.x` patch (context lines) |
| `ParticleGroup#extractRenderState(Frustum, Camera, float)` returns `ParticleGroupRenderState` | Confirmed | Fabric 0.165.2+26.3 `ParticleGroupRegistryTests` (overrides it) |
| `QuadParticleGroup(ParticleEngine, ParticleRenderType)` constructor | Confirmed | Fabric 0.165.2+26.3 `ParticleEngineMixin` (`NEW` target) |
| `Frustum#isVisible(AABB)Z` | Confirmed | NeoForge `26.3.x` patch `Frustum.java.patch` |
| `EntityRenderer#extractRenderState(Entity, EntityRenderState, float)` | Confirmed | NeoForge `26.3.x` patch `EntityRenderer.java.patch` |
| `EntityRenderer#shouldRender(Entity, Frustum, double, double, double, float)Z` | **Not found** | No caller or override found in Fabric or NeoForge sources |
| `ParticleEngine#add(Particle)V` | **Not found** | Not in any reachable source |
| `QuadParticleGroup` fields `particleType`, `particleTypeRenderState` | **Not found** | Not in any reachable source |
| `EntityRenderer#getBoundingBoxForCulling`, `#affectedByCulling` | **Not found** | Not in any reachable source |
| `WalkAnimationState` (`net.minecraft.world.entity`) | **Not found** | Not in any reachable source |

## Client API (keybinds and commands)

| API | Status | Evidence |
|---|---|---|
| `KeyMapping(String, InputConstants.Type.KEYBOARD, int scancode, KeyMapping.Category)` | Confirmed (replaces the old `Type.KEYSYM` + `InputConstants.KEY_*` form) | Fabric 0.165.2+26.3 networking testmod |
| `org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_F9` etc. | Confirmed as the pattern used by 26.3 test code | Same testmod (`SDL_SCANCODE_RIGHTBRACKET`, `SDL_SCANCODE_MENU`) |
| `KeyMapping.Category.register(Identifier)` | Confirmed | Fabric key-mapping testmod |
| `KeyMappingHelper.registerKeyMapping` | Confirmed | Fabric key-mapping testmod |
| `Identifier.fromNamespaceAndPath` | Confirmed | Fabric key-mapping testmod |
| `ClientCommandRegistrationCallback.EVENT` with `(dispatcher, registryAccess)` and `ClientCommands.literal` | Confirmed | Fabric 0.165.2+26.3 command tests |
| `ClientTickEvents.END_CLIENT_TICK` | Not separately checked | Standard Fabric API; not in the sources read here |

## What this means

- Four mixins were written against targets that are not confirmed. They are kept in
  `unverified/mixin/` and are not compiled. They are not in `wildernesscull.mixins.json`.
  That means no optimization runs in game yet.
- The earlier README said these APIs were verified. That was wrong, and the earlier zip also
  had a compile error in `ConfigManager.java`, which is fixed.
- The occlusion, particle, animation, config and telemetry logic does not touch Minecraft.
  It compiles standalone and is exercised by `WildernessCullVerificationSuite`. The suite
  currently passes 7 of 8 checks (see README, Known issues).
