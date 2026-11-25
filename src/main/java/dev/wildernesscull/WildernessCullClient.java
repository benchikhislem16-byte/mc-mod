package dev.wildernesscull;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.wildernesscull.animation.AnimationOptimizer;
import dev.wildernesscull.config.ConfigManager;
import dev.wildernesscull.config.CullConfig;
import dev.wildernesscull.culling.OcclusionCuller;
import dev.wildernesscull.particle.ParticleOptimizer;
import dev.wildernesscull.util.CullTelemetry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lwjgl.sdl.SDLScancode;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for WildernessCull (Minecraft Java Edition 26.3 Fabric).
 *
 * <p>Uses verified Minecraft 26.3 unobfuscated Mojang mappings and Fabric API:
 * <ul>
 *   <li>{@link Identifier#fromNamespaceAndPath(String, String)}</li>
 *   <li>{@link KeyMapping.Category#register(Identifier)} and {@link KeyMappingHelper#registerKeyMapping(KeyMapping)}</li>
 *   <li>{@link ClientTickEvents#END_CLIENT_TICK}</li>
 *   <li>{@link ClientCommandRegistrationCallback} with {@link ClientCommands}</li>
 * </ul>
 */
public final class WildernessCullClient implements ClientModInitializer {

    public static final String MOD_ID = "wildernesscull";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final CullTelemetry TELEMETRY = new CullTelemetry();
    private static OcclusionCuller occlusionCuller;
    private static ParticleOptimizer particleOptimizer;
    private static AnimationOptimizer animationOptimizer;

    private KeyMapping toggleCullingKey;
    private KeyMapping printStatsKey;
    private KeyMapping resetConfigKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static CullTelemetry getTelemetry() {
        return TELEMETRY;
    }

    public static OcclusionCuller getOcclusionCuller() {
        return occlusionCuller;
    }

    public static ParticleOptimizer getParticleOptimizer() {
        return particleOptimizer;
    }

    public static AnimationOptimizer getAnimationOptimizer() {
        return animationOptimizer;
    }

    @Override
    public void onInitializeClient() {
        ConfigManager.init(FabricLoader.getInstance().getConfigDir());
        CullConfig config = ConfigManager.getConfig();

        occlusionCuller = new OcclusionCuller(config, TELEMETRY);
        particleOptimizer = new ParticleOptimizer(config, TELEMETRY);
        animationOptimizer = new AnimationOptimizer(config, TELEMETRY);

        registerKeyMappings();
        registerTickHandlers();
        registerClientCommands();

        LOGGER.info("[WildernessCull] Initialized for Minecraft 26.3 (EntityCulling={}, Particles={}, Animations={})",
                config.entityCullingEnabled, config.particleOptimizationEnabled, config.animationOptimizationEnabled);
    }

    private void registerKeyMappings() {
        KeyMapping.Category category = KeyMapping.Category.register(id("general"));

        // Minecraft 26.3 uses SDL scancodes (org.lwjgl.sdl.SDLScancode) with Type.KEYBOARD,
        // matching the official Fabric 26.3 test mods. Unbound = SDL_SCANCODE_UNKNOWN.
        this.toggleCullingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.wildernesscull.toggle_culling",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_F9,
                category
        ));

        this.printStatsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.wildernesscull.print_stats",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_F10,
                category
        ));

        this.resetConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.wildernesscull.reset_config",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_UNKNOWN,
                category
        ));
    }

    private void registerTickHandlers() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null) {
                occlusionCuller.clearCache();
                animationOptimizer.clearCache();
                return;
            }

            Camera camera = client.gameRenderer.getMainCamera();
            if (camera != null) {
                occlusionCuller.updateCameraMotion(camera.getYRot(), camera.getXRot());
            }

            while (this.toggleCullingKey.consumeClick()) {
                CullConfig cfg = ConfigManager.getConfig();
                cfg.entityCullingEnabled = !cfg.entityCullingEnabled;
                ConfigManager.save();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable(
                            cfg.entityCullingEnabled ? "wildernesscull.msg.culling_on" : "wildernesscull.msg.culling_off"
                    ));
                }
            }

            while (this.printStatsKey.consumeClick()) {
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("[WildernessCull] " + TELEMETRY.formatSummary()));
                }
            }

            while (this.resetConfigKey.consumeClick()) {
                ConfigManager.resetToDefaultsAndSave();
                occlusionCuller.clearCache();
                animationOptimizer.clearCache();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable("wildernesscull.msg.config_reset"));
                }
            }
        });
    }

    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("wcull")
                    .then(ClientCommands.literal("stats")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] " + TELEMETRY.formatSummary()));
                                return 1;
                            }))
                    .then(ClientCommands.literal("resetstats")
                            .executes(ctx -> {
                                TELEMETRY.reset();
                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] Telemetry counters reset."));
                                return 1;
                            }))
                    .then(ClientCommands.literal("reset")
                            .executes(ctx -> {
                                ConfigManager.resetToDefaultsAndSave();
                                occlusionCuller.clearCache();
                                animationOptimizer.clearCache();
                                ctx.getSource().sendFeedback(Component.translatable("wildernesscull.msg.config_reset"));
                                return 1;
                            }))
                    .then(ClientCommands.literal("toggle")
                            .then(ClientCommands.literal("culling")
                                    .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> {
                                                boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                                ConfigManager.getConfig().entityCullingEnabled = enabled;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] entityCullingEnabled = " + enabled));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("particles")
                                    .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> {
                                                boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                                ConfigManager.getConfig().particleOptimizationEnabled = enabled;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] particleOptimizationEnabled = " + enabled));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("animations")
                                    .then(ClientCommands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> {
                                                boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                                ConfigManager.getConfig().animationOptimizationEnabled = enabled;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] animationOptimizationEnabled = " + enabled));
                                                return 1;
                                            }))))
                    .then(ClientCommands.literal("set")
                            .then(ClientCommands.literal("graceTicks")
                                    .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(1, 40))
                                            .executes(ctx -> {
                                                int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                                ConfigManager.getConfig().occlusionGraceTicks = ticks;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] occlusionGraceTicks = " + ticks));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("maxEntityDistance")
                                    .then(ClientCommands.argument("distance", DoubleArgumentType.doubleArg(8.0, 512.0))
                                            .executes(ctx -> {
                                                double dist = DoubleArgumentType.getDouble(ctx, "distance");
                                                ConfigManager.getConfig().entityCullingMaxDistance = dist;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] entityCullingMaxDistance = " + dist));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("maxParticleDistance")
                                    .then(ClientCommands.argument("distance", DoubleArgumentType.doubleArg(8.0, 256.0))
                                            .executes(ctx -> {
                                                double dist = DoubleArgumentType.getDouble(ctx, "distance");
                                                ConfigManager.getConfig().maxParticleDistance = dist;
                                                ConfigManager.save();
                                                ctx.getSource().sendFeedback(Component.literal("[WildernessCull] maxParticleDistance = " + dist));
                                                return 1;
                                            }))))
            );
        });
    }

    /**
     * Bridges a Minecraft 26.3 {@link Entity} and {@link ClientLevel} into the pure-Java
     * {@link OcclusionCuller}.
     */
    public static boolean evaluateEntityVisibility(
            Entity entity,
            AABB cullingBox,
            boolean vanillaShouldRender,
            double camX, double camY, double camZ
    ) {
        if (occlusionCuller == null) {
            return vanillaShouldRender;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || entity == null) {
            return vanillaShouldRender;
        }

        Entity cameraEntity = mc.getCameraEntity();
        if (entity == cameraEntity) {
            return vanillaShouldRender;
        }

        boolean isPassengerOrVehicle = cameraEntity != null
                && (entity.hasPassenger(cameraEntity) || cameraEntity.hasPassenger(entity));
        boolean isLeashed = (entity instanceof Leashable leashable) && leashable.isLeashed();
        boolean isBossOrExempt = !entity.canChangeDimensions(level, level) || entity.isCurrentlyGlowing();

        Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String typeId = typeKey != null ? typeKey.toString() : "minecraft:unknown";

        Vec3 delta = entity.getDeltaMovement();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        OcclusionCuller.EntityVisibilityInput input = new OcclusionCuller.EntityVisibilityInput(
                entity.getId(),
                typeId,
                cullingBox.minX, cullingBox.minY, cullingBox.minZ,
                cullingBox.maxX, cullingBox.maxY, cullingBox.maxZ,
                delta.x, delta.y, delta.z,
                entity.isCurrentlyGlowing(),
                isLeashed,
                entity.hasCustomName(),
                isPassengerOrVehicle,
                isBossOrExempt,
                vanillaShouldRender
        );

        return occlusionCuller.shouldRenderEntity(
                input,
                camX, camY, camZ,
                level.getGameTime(),
                (bx, by, bz) -> {
                    mutablePos.set(bx, by, bz);
                    if (!level.hasChunkAt(mutablePos)) {
                        return false;
                    }
                    BlockState state = level.getBlockState(mutablePos);
                    // Strictly require a solid render opaque full 1x1x1 cube so glass, leaves,
                    // slabs, stairs, fences, doors, portals, and translucent blocks never occlude!
                    return state.isSolidRender() && state.isCollisionShapeFullBlock(level, mutablePos);
                }
        );
    }
}
