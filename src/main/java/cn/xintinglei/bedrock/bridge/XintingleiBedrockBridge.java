package cn.xintinglei.bedrock.bridge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XintingleiBedrockBridge implements ModInitializer {
    public static final String MOD_ID = "xintinglei_bedrock_bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile RegistryManifestExporter.ExportResult lastExport;
    private static volatile BedrockResourcePackExporter.ExportResult lastPackExport;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(this::exportOnStartup);
        ServerTickEvents.END_SERVER_TICK.register(BedrockHudService::tick);
        LOGGER.info("Xintinglei Bedrock Bridge initialized; registry export and Bedrock HUD are enabled");
    }

    private void exportOnStartup(MinecraftServer server) {
        exportCompatibilityArtifacts();
    }

    private static void exportCompatibilityArtifacts() {
        try {
            lastExport = RegistryManifestExporter.export();
            lastPackExport = BedrockResourcePackExporter.export(lastExport.path());
            LOGGER.info("Exported Bedrock compatibility manifest to {} ({})",
                lastExport.path(), lastExport.summary());
            LOGGER.info("Exported Bedrock mod resource pack to {} ({})",
                lastPackExport.path(), lastPackExport.summary());
        } catch (Exception exception) {
            LOGGER.error("Unable to export Bedrock compatibility artifacts; compatibility must remain disabled", exception);
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("xbedrock")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.literal("export").executes(XintingleiBedrockBridge::exportCommand))
            .then(CommandManager.literal("pack").executes(XintingleiBedrockBridge::exportCommand))
            .then(CommandManager.literal("status").executes(XintingleiBedrockBridge::statusCommand)));

        dispatcher.register(CommandManager.literal("bedrockhud")
            .executes(context -> setHudEnabled(context, true))
            .then(CommandManager.literal("on").executes(context -> setHudEnabled(context, true)))
            .then(CommandManager.literal("off").executes(context -> setHudEnabled(context, false))));
    }

    private static int exportCommand(CommandContext<ServerCommandSource> context) {
        try {
            exportCompatibilityArtifacts();
            context.getSource().sendFeedback(
                () -> Text.literal("Bedrock compatibility artifacts exported: " + lastExport.summary()
                    + " | pack=" + lastPackExport.summary()), false);
            return 1;
        } catch (Exception exception) {
            LOGGER.error("Manual Bedrock compatibility manifest export failed", exception);
            context.getSource().sendError(Text.literal(
                "Bedrock compatibility export failed. Compatibility must remain disabled; see the server log."));
            return 0;
        }
    }

    private static int statusCommand(CommandContext<ServerCommandSource> context) {
        RegistryManifestExporter.ExportResult result = lastExport;
        BedrockResourcePackExporter.ExportResult pack = lastPackExport;
        if (result == null || pack == null) {
            context.getSource().sendError(Text.literal(
                "No successful manifest export is available. Run /xbedrock export and inspect the server log."));
            return 0;
        }

        context.getSource().sendFeedback(
            () -> Text.literal("Manifest: " + result.path() + " | " + result.summary()
                + " | Pack: " + pack.path() + " | " + pack.summary()), false);
        return 1;
    }

    private static int setHudEnabled(CommandContext<ServerCommandSource> context, boolean enabled) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("This command can only be used by a player."));
            return 0;
        }
        if (!GeyserProfileDetector.isGeyserProfile(player.getGameProfile())) {
            context.getSource().sendError(Text.literal("Bedrock HUD is only available through Geyser."));
            return 0;
        }

        BedrockHudService.setEnabled(player, enabled);
        player.sendMessage(Text.literal(enabled ? "§a基岩 HUD 已开启。" : "§e基岩 HUD 已关闭。"), false);
        return 1;
    }
}
