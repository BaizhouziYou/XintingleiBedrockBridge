package cn.xintinglei.bedrock.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class RegistryManifestExporter {
    static final int SCHEMA_VERSION = 2;
    static final String OUTPUT_DIRECTORY = "xintinglei-bedrock-bridge";
    static final String OUTPUT_FILE = "compat-manifest.json";

    private static final Set<String> TARGET_NAMESPACES = Set.of(
        "better_mcdonalds_mod",
        "happy_ghast_legacy",
        "thecopperrail"
    );
    private static final List<String> TARGET_MODS = List.of(
        "better_mcdonalds_mod",
        "happy_ghast_legacy",
        "thecopperrail"
    );
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();

    private RegistryManifestExporter() {
    }

    static ExportResult export() throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", SCHEMA_VERSION);
        manifest.addProperty("generated_at", Instant.now().toString());
        manifest.addProperty("minecraft", SharedConstants.getGameVersion().getName());
        manifest.addProperty("fabric_loader", modVersion("fabricloader"));

        JsonObject mods = exportMods();
        JsonArray items = exportItems();
        JsonArray blocks = exportBlocks();
        JsonArray blockStates = exportBlockStates();
        JsonArray entityTypes = exportEntityTypes();
        JsonArray sounds = exportSounds();

        manifest.add("mods", mods);
        manifest.add("items", items);
        manifest.add("blocks", blocks);
        manifest.add("block_states", blockStates);
        manifest.add("entity_types", entityTypes);
        manifest.add("sounds", sounds);

        Path directory = FabricLoader.getInstance().getConfigDir().resolve(OUTPUT_DIRECTORY);
        Files.createDirectories(directory);
        Path destination = directory.resolve(OUTPUT_FILE);
        Path temporary = directory.resolve(OUTPUT_FILE + ".tmp");

        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
        }

        replaceAtomically(temporary, destination);
        validateWrittenManifest(destination);

        return new ExportResult(destination.toAbsolutePath().normalize(), items.size(), blocks.size(),
            blockStates.size(), entityTypes.size(), sounds.size());
    }

    private static JsonObject exportMods() throws IOException {
        JsonObject result = new JsonObject();
        for (String modId : TARGET_MODS) {
            ModContainer container = FabricLoader.getInstance().getModContainer(modId)
                .orElseThrow(() -> new IOException("Required target mod is not loaded: " + modId));

            JsonObject mod = new JsonObject();
            mod.addProperty("version", container.getMetadata().getVersion().getFriendlyString());
            Path origin = singleRegularOrigin(container);
            if (origin == null) {
                throw new IOException("Required target mod does not have one regular JAR origin: " + modId);
            }
            mod.addProperty("file", origin.getFileName().toString());
            mod.addProperty("sha256", sha256(origin));
            result.add(modId, mod);
        }
        return result;
    }

    private static JsonArray exportItems() {
        JsonArray result = new JsonArray();
        for (Identifier id : sortedTargetIds(Registries.ITEM.getIds())) {
            Item item = Registries.ITEM.get(id);
            JsonObject entry = baseRegistryEntry(id, Registries.ITEM.getRawId(item));
            entry.addProperty("display_name", item.getName().getString());
            entry.addProperty("max_count", item.getMaxCount());
            // A generic Fabric Item does not expose a stable max-damage accessor in the
            // 1.21.4 mappings. Non-zero values are added by dedicated item adapters later;
            // ordinary food, seeds and block items remain stackable here.
            entry.addProperty("max_damage", 0);
            entry.addProperty("block_item", item instanceof BlockItem);
            result.add(entry);
        }
        return result;
    }

    private static JsonArray exportBlocks() {
        JsonArray result = new JsonArray();
        for (Identifier id : sortedTargetIds(Registries.BLOCK.getIds())) {
            Block block = Registries.BLOCK.get(id);
            JsonObject entry = baseRegistryEntry(id, Registries.BLOCK.getRawId(block));
            entry.addProperty("default_state_raw_id", Block.STATE_IDS.getRawId(block.getDefaultState()));
            entry.addProperty("display_name", block.getName().getString());
            entry.addProperty("geometry", geometryFor(id, block.getDefaultState()));
            entry.addProperty("block_item", hasBlockItem(block));
            result.add(entry);
        }
        return result;
    }

    private static JsonArray exportBlockStates() {
        JsonArray result = new JsonArray();
        for (Identifier id : sortedTargetIds(Registries.BLOCK.getIds())) {
            Block block = Registries.BLOCK.get(id);
            List<BlockState> states = new ArrayList<>(block.getStateManager().getStates());
            states.sort(Comparator.comparingInt(Block.STATE_IDS::getRawId));
            for (BlockState state : states) {
                JsonObject entry = baseRegistryEntry(id, Block.STATE_IDS.getRawId(state));
                entry.addProperty("block_raw_id", Registries.BLOCK.getRawId(block));
                entry.addProperty("state_group_id", Registries.BLOCK.getRawId(block));
                entry.addProperty("block_hardness", isNonSolidVisual(id, state) ? 0.0f : 1.0f);
                entry.addProperty("can_break_with_hand", true);
                entry.addProperty("waterlogged", isWaterlogged(state));
                entry.addProperty("geometry", geometryFor(id, state));
                entry.addProperty("texture", textureFor(id, state));
                entry.add("collision", collisionFor(id, state));
                JsonObject properties = new JsonObject();
                Map<String, String> sortedProperties = new TreeMap<>();
                state.getEntries().forEach((property, value) ->
                    sortedProperties.put(property.getName(), propertyValueName(property, value)));
                sortedProperties.forEach(properties::addProperty);
                entry.add("properties", properties);
                result.add(entry);
            }
        }
        return result;
    }

    private static JsonArray exportEntityTypes() {
        JsonArray result = new JsonArray();
        for (Identifier id : sortedTargetIds(Registries.ENTITY_TYPE.getIds())) {
            EntityType<?> entityType = Registries.ENTITY_TYPE.get(id);
            result.add(baseRegistryEntry(id, Registries.ENTITY_TYPE.getRawId(entityType)));
        }
        return result;
    }

    private static JsonArray exportSounds() {
        JsonArray result = new JsonArray();
        for (Identifier id : sortedTargetIds(Registries.SOUND_EVENT.getIds())) {
            SoundEvent sound = Registries.SOUND_EVENT.get(id);
            result.add(baseRegistryEntry(id, Registries.SOUND_EVENT.getRawId(sound)));
        }
        return result;
    }

    private static List<Identifier> sortedTargetIds(Set<Identifier> ids) {
        return ids.stream()
            .filter(id -> TARGET_NAMESPACES.contains(id.getNamespace()))
            .sorted(Comparator.comparing(Identifier::toString))
            .toList();
    }

    private static JsonObject baseRegistryEntry(Identifier id, int rawId) {
        JsonObject entry = new JsonObject();
        entry.addProperty("identifier", id.toString());
        entry.addProperty("raw_id", rawId);
        return entry;
    }

    /**
     * Bedrock only needs a conservative client-side collision approximation. Crops and rails are
     * deliberately non-solid: reporting them as full cubes is what traps Bedrock players while
     * walking through a Java Fabric crop/rail block.
     */
    private static JsonArray collisionFor(Identifier id, BlockState state) {
        JsonArray boxes = new JsonArray();
        if (!isNonSolidVisual(id, state)) {
            JsonObject box = new JsonObject();
            box.addProperty("middle_x", 0.5d);
            box.addProperty("middle_y", 0.5d);
            box.addProperty("middle_z", 0.5d);
            box.addProperty("size_x", 1.0d);
            box.addProperty("size_y", 1.0d);
            box.addProperty("size_z", 1.0d);
            boxes.add(box);
        }
        return boxes;
    }

    private static boolean isNonSolidVisual(Identifier id, BlockState state) {
        String path = id.getPath();
        return path.contains("crop") || path.contains("rail") || state.getProperties().stream()
            .anyMatch(property -> property.getName().equals("age"));
    }

    private static boolean isWaterlogged(BlockState state) {
        return state.getProperties().stream().anyMatch(property -> property.getName().equals("waterlogged")
            && "true".equals(propertyValueName(property, state.get(property))));
    }

    private static String geometryFor(Identifier id, BlockState state) {
        return isNonSolidVisual(id, state) ? "cross" : "full_block";
    }

    private static String textureFor(Identifier id, BlockState state) {
        String path = id.getPath();
        String age = state.getProperties().stream()
            .filter(property -> property.getName().equals("age"))
            .findFirst()
            .map(property -> propertyValueName(property, state.get(property)))
            .orElse(null);
        String hydration = state.getProperties().stream()
            .filter(property -> property.getName().equals("hydration"))
            .findFirst()
            .map(property -> propertyValueName(property, state.get(property)))
            .orElse(null);
        if (age != null) {
            path += "_stage" + age;
        } else if (hydration != null) {
            path += "_hydration_" + hydration + "_north";
        }
        return textureKey(id.getNamespace(), path);
    }

    static String textureKey(String namespace, String path) {
        return "xintinglei_" + namespace + "_" + path.replace('/', '_').replace('-', '_');
    }

    private static boolean hasBlockItem(Block block) {
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.name(value);
    }

    private static String modVersion(String modId) throws IOException {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElseThrow(() -> new IOException("Required mod is not loaded: " + modId));
    }

    private static Path singleRegularOrigin(ModContainer container) {
        List<Path> regularOrigins = container.getOrigin().getPaths().stream()
            .filter(Files::isRegularFile)
            .toList();
        return regularOrigins.size() == 1 ? regularOrigins.get(0) : null;
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JVM does not provide SHA-256", exception);
        }

        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void replaceAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateWrittenManifest(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject parsed = GSON.fromJson(reader, JsonObject.class);
            if (parsed == null || parsed.get("schema").getAsInt() != SCHEMA_VERSION) {
                throw new IOException("Written manifest failed schema validation: " + manifest);
            }
        }
    }

    record ExportResult(Path path, int items, int blocks, int blockStates, int entityTypes, int sounds) {
        String summary() {
            return "items=" + items + ", blocks=" + blocks + ", block_states=" + blockStates
                + ", entity_types=" + entityTypes + ", sounds=" + sounds;
        }
    }
}
