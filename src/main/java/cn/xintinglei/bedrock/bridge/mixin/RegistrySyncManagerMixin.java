package cn.xintinglei.bedrock.bridge.mixin;

import cn.xintinglei.bedrock.bridge.GeyserProfileDetector;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Fabric registry synchronization intact for normal Java clients while allowing the Geyser
 * downstream connection to proceed without Fabric's custom registry payload support.
 */
@Mixin(value = RegistrySyncManager.class, remap = false)
abstract class RegistrySyncManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("XintingleiBedrockBridge");
    private static final String BYPASS_PROPERTY = "Xintinglei.AllowGeyserWithoutFabricRegistrySync";

    @Inject(method = "configureClient", at = @At("HEAD"), cancellable = true, remap = false)
    private static void xintinglei$allowGeyserClient(
        ServerConfigurationNetworkHandler handler,
        MinecraftServer server,
        CallbackInfo callback
    ) {
        if (!Boolean.getBoolean(BYPASS_PROPERTY)) {
            return;
        }

        String clientBrand = ServerNetworkingImpl.getAddon(handler).getClientBrand();
        GameProfile profile = ((ServerConfigurationNetworkHandlerAccessor) (Object) handler)
            .xintinglei$getProfile();
        if (!"Geyser".equals(clientBrand) && !GeyserProfileDetector.isGeyserProfile(profile)) {
            return;
        }

        LOGGER.info("Allowing Geyser connection without Fabric static registry synchronization (brand={}, profile={})",
            clientBrand, profile.getName());
        callback.cancel();
    }

}
