package cn.xintinglei.bedrock.bridge;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Identifies the Floodgate profile that Geyser forwards to the Fabric backend. */
public final class GeyserProfileDetector {
    private static final String BEDROCK_MARKER_PROPERTY = "xintinglei:bedrock";

    private GeyserProfileDetector() {
    }

    public static boolean isGeyserProfile(GameProfile profile) {
        if (profile == null) {
            return false;
        }

        for (Property property : profile.getProperties().get(BEDROCK_MARKER_PROPERTY)) {
            if ("1".equals(property.value())) {
                return true;
            }
        }

        // Compatibility with connections made through the older Floodgate JAR.
        for (Property property : profile.getProperties().get("textures")) {
            try {
                String textureJson = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
                if (textureJson.contains("GeyserMC")) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed texture property is not a Geyser marker.
            }
        }

        return false;
    }
}
