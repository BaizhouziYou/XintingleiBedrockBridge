package cn.xintinglei.bedrock.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds the Bedrock pack from the exact Fabric JARs that produced the registry manifest. */
final class BedrockResourcePackExporter {
    static final String OUTPUT_FILE = "Xintinglei-ModCompat.mcpack";

    private static final Gson GSON = new Gson();
    private static final List<String> DRINKS = List.of(
        "coffee", "energy_drink", "herbal_tea", "berry_juice", "mint_cooler", "miner_soda",
        "ocean_tonic", "blaze_brew", "monster_black", "monster_white", "monster_green", "monster_pink",
        "apple_carrot_juice", "clear_soda", "vodka", "almond_water", "bean_juice", "mega_boba_tea"
    );
    // New pack identities force Bedrock clients to fetch the custom drink textures instead of
    // reusing a stale v1 cache after xintinglei_drinks was added to the export.
    private static final UUID HEADER_UUID = UUID.nameUUIDFromBytes("xintinglei-mod-compat-header-v2".getBytes(StandardCharsets.UTF_8));
    private static final UUID MODULE_UUID = UUID.nameUUIDFromBytes("xintinglei-mod-compat-module-v2".getBytes(StandardCharsets.UTF_8));

    private BedrockResourcePackExporter() {
    }

    static ExportResult export(Path manifestPath) throws IOException {
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            manifest = GSON.fromJson(reader, JsonObject.class);
        }
        if (manifest == null || !manifest.has("items") || !manifest.has("block_states")) {
            throw new IOException("Cannot build Bedrock pack from an invalid compatibility manifest: " + manifestPath);
        }

        Path destination = manifestPath.resolveSibling(OUTPUT_FILE);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Map<String, String> itemTextures = new LinkedHashMap<>();
        Map<String, String> blockTextures = new LinkedHashMap<>();
        List<TextureSource> textureSources = collectTextures();
        Map<String, String> copiedTexturePaths = new HashMap<>();

        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
            Set<String> written = new HashSet<>();
            writeJson(output, written, "manifest.json", packManifest());
            writeBytes(output, written, "models/blocks/xintinglei_cross.geo.json", crossGeometry().getBytes(StandardCharsets.UTF_8));

            for (TextureSource source : textureSources) {
                String target = "textures/" + source.kind() + "/" + source.namespace() + "/" + source.path() + ".png";
                writeJarEntry(output, written, source.jar(), source.entryName(), target);
                copiedTexturePaths.put(source.key(), target.substring(0, target.length() - 4));
            }

            for (String drink : DRINKS) {
                String target = "textures/item/drinks/" + drink + ".png";
                writeResourceEntry(output, written,
                    "assets/xintinglei_drinks/textures/item/" + drink + ".png", target);
                copiedTexturePaths.put("drink:" + drink, target.substring(0, target.length() - 4));
            }

            String fallback = copiedTexturePaths.values().stream().findFirst()
                .orElseThrow(() -> new IOException("None of the target Fabric mods contains a PNG texture"));
            for (JsonElement element : manifest.getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                String[] split = splitIdentifier(item.get("identifier").getAsString());
                String key = RegistryManifestExporter.textureKey(split[0], split[1]);
                String identifier = split[0] + ":" + split[1];
                String path = copiedTexturePaths.getOrDefault("item:" + identifier,
                    copiedTexturePaths.getOrDefault("block:" + identifier,
                        CompatibilityTargets.VANILLA_ITEM_TEXTURES.getOrDefault(identifier, fallback)));
                itemTextures.put(key, path);
            }
            for (String drink : DRINKS) {
                itemTextures.put("xintinglei_drinks_" + drink,
                    copiedTexturePaths.get("drink:" + drink));
            }
            for (JsonElement element : manifest.getAsJsonArray("block_states")) {
                JsonObject state = element.getAsJsonObject();
                String[] split = splitIdentifier(state.get("identifier").getAsString());
                String textureKey = state.has("texture") ? state.get("texture").getAsString()
                    : RegistryManifestExporter.textureKey(split[0], split[1]);
                String texturePath = texturePathFromKey(textureKey, split[0], split[1], copiedTexturePaths, fallback);
                blockTextures.put(textureKey, texturePath);
            }

            writeJson(output, written, "textures/item_texture.json", textureAtlas("atlas.items", itemTextures));
            writeJson(output, written, "textures/terrain_texture.json", textureAtlas("atlas.terrain", blockTextures));
        }

        Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new ExportResult(destination.toAbsolutePath().normalize(), itemTextures.size(), blockTextures.size());
    }

    private static String texturePathFromKey(String key, String namespace, String blockPath,
                                             Map<String, String> textures, String fallback) {
        String normalized = key.substring(("xintinglei_" + namespace + "_").length());
        String direct = textures.get("block:" + namespace + ":" + normalized);
        if (direct != null) {
            return direct;
        }
        String exact = textures.get("block:" + namespace + ":" + blockPath);
        if (exact != null) {
            return exact;
        }
        return fallback;
    }

    private static List<TextureSource> collectTextures() throws IOException {
        List<TextureSource> result = new ArrayList<>();
        for (String modId : CompatibilityTargets.MOD_IDS) {
            ModContainer container = FabricLoader.getInstance().getModContainer(modId)
                .orElseThrow(() -> new IOException("Target mod is not loaded: " + modId));
            Path jar = container.getOrigin().getPaths().stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException("Target mod has no regular JAR origin: " + modId));
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream().filter(entry -> !entry.isDirectory()).forEach(entry -> {
                    String name = entry.getName();
                    String prefix = "assets/" + modId + "/textures/";
                    if (!name.startsWith(prefix) || !name.endsWith(".png")) {
                        return;
                    }
                    String remainder = name.substring(prefix.length(), name.length() - 4);
                    int separator = remainder.indexOf('/');
                    if (separator <= 0) {
                        return;
                    }
                    String kind = remainder.substring(0, separator);
                    if (!kind.equals("item") && !kind.equals("block")) {
                        return;
                    }
                    String path = remainder.substring(separator + 1);
                    result.add(new TextureSource(jar, name, kind, modId, path));
                });
            }
        }
        return result;
    }

    private static JsonObject packManifest() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        JsonObject header = new JsonObject();
        header.addProperty("name", "Xintinglei Fabric Mod Compatibility");
        header.addProperty("description", "Auto-generated from the active Fabric mod JARs");
        header.addProperty("uuid", HEADER_UUID.toString());
        header.add("version", version(1, 1, 0));
        header.add("min_engine_version", version(1, 21, 0));
        root.add("header", header);
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", MODULE_UUID.toString());
        module.add("version", version(1, 1, 0));
        JsonArray modules = new JsonArray();
        modules.add(module);
        root.add("modules", modules);
        return root;
    }

    private static JsonObject textureAtlas(String atlasName, Map<String, String> textures) {
        JsonObject atlas = new JsonObject();
        atlas.addProperty("resource_pack_name", "xintinglei_mod_compat");
        atlas.addProperty("texture_name", atlasName);
        JsonObject data = new JsonObject();
        textures.forEach((key, path) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", path);
            data.add(key, entry);
        });
        atlas.add("texture_data", data);
        return atlas;
    }

    /** A two-plane crop/rail geometry. It avoids assuming that a client has an undocumented vanilla cross geometry. */
    private static String crossGeometry() {
        return """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.xintinglei_cross","texture_width":16,"texture_height":16,"visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},"bones":[{"name":"cross","pivot":[0,0,0],"cubes":[{"origin":[-8,0,-0.5],"size":[16,16,1],"uv":[0,0],"uv_size":[16,16]},{"origin":[-0.5,0,-8],"size":[1,16,16],"uv":[0,0],"uv_size":[16,16]}]}]}]}
            """;
    }

    private static JsonArray version(int major, int minor, int patch) {
        JsonArray version = new JsonArray();
        version.add(major);
        version.add(minor);
        version.add(patch);
        return version;
    }

    private static void writeJson(ZipOutputStream output, Set<String> written, String name, JsonObject json) throws IOException {
        writeBytes(output, written, name, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJarEntry(ZipOutputStream output, Set<String> written, Path jar, String sourceName, String target) throws IOException {
        if (!written.add(target)) {
            return;
        }
        output.putNextEntry(new ZipEntry(target));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.getInputStream(zip.getEntry(sourceName)).transferTo(output);
        }
        output.closeEntry();
    }

    private static void writeResourceEntry(ZipOutputStream output, Set<String> written,
                                           String resourceName, String target) throws IOException {
        if (!written.add(target)) {
            return;
        }
        try (var input = BedrockResourcePackExporter.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing bundled DrinksDataPack texture resource: " + resourceName);
            }
            output.putNextEntry(new ZipEntry(target));
            input.transferTo(output);
            output.closeEntry();
        }
    }

    private static void writeBytes(ZipOutputStream output, Set<String> written, String name, byte[] bytes) throws IOException {
        if (!written.add(name)) {
            throw new IOException("Duplicate Bedrock resource-pack entry: " + name);
        }
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static String[] splitIdentifier(String identifier) throws IOException {
        int separator = identifier.indexOf(':');
        if (separator <= 0 || separator == identifier.length() - 1) {
            throw new IOException("Invalid registry identifier in manifest: " + identifier);
        }
        return new String[] { identifier.substring(0, separator), identifier.substring(separator + 1) };
    }

    private record TextureSource(Path jar, String entryName, String kind, String namespace, String path) {
        String key() {
            return kind + ":" + namespace + ":" + path;
        }
    }

    record ExportResult(Path path, int itemTextures, int blockTextures) {
        String summary() {
            return "item_textures=" + itemTextures + ", block_textures=" + blockTextures;
        }
    }
}
