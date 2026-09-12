package cn.xintinglei.bedrock.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Presents the HUD subset of Servux data using an action bar, which Geyser maps to the native
 * Bedrock HUD. Servux's Java-only payload channels remain available to Java clients unchanged.
 */
public final class BedrockHudService {
    private static final int UPDATE_INTERVAL_TICKS = 40;
    private static final Set<UUID> DISABLED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static int ticks;

    private BedrockHudService() {
    }

    public static void tick(MinecraftServer server) {
        if (++ticks < UPDATE_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;

        float mspt = server.getAverageTickTime();
        double tps = Math.min(20.0D, 1000.0D / Math.max(1.0F, mspt));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!GeyserProfileDetector.isGeyserProfile(player.getGameProfile())
                || DISABLED_PLAYERS.contains(player.getUuid())) {
                continue;
            }
            player.sendMessage(Text.literal(format(player, tps, mspt)), true);
        }
    }

    public static void setEnabled(ServerPlayerEntity player, boolean enabled) {
        if (enabled) {
            DISABLED_PLAYERS.remove(player.getUuid());
        } else {
            DISABLED_PLAYERS.add(player.getUuid());
        }
    }

    private static String format(ServerPlayerEntity player, double tps, float mspt) {
        ServerWorld world = player.getServerWorld();
        long worldTime = world.getTimeOfDay();
        long day = worldTime / 24000L + 1L;
        long time = worldTime % 24000L;
        String weather = world.isThundering() ? "雷暴" : world.isRaining() ? "下雨" : "晴朗";
        String dimension = world.getRegistryKey().getValue().getPath();

        return String.format(java.util.Locale.ROOT,
            "§bTPS §f%.1f §7| §bMSPT §f%.1f §7| §e%s §f%d %d %d §7| §a%s §7| §d第%d天 %s",
            tps, mspt, dimension, player.getBlockX(), player.getBlockY(), player.getBlockZ(), weather, day,
            formatTime(time));
    }

    private static String formatTime(long time) {
        long hours = (time / 1000L + 6L) % 24L;
        long minutes = (time % 1000L) * 60L / 1000L;

        return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
