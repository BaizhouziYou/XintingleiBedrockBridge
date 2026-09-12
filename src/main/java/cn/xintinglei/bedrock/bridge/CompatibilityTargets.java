package cn.xintinglei.bedrock.bridge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Fabric namespaces whose runtime registry entries are intentionally exposed to Bedrock.
 *
 * <p>Keep this list deliberately small. Exporting every installed server-side mod would make
 * Geyser advertise items that have neither a Bedrock representation nor a tested interaction
 * path.</p>
 */
final class CompatibilityTargets {
    static final List<String> MOD_IDS = List.of(
        "better_mcdonalds_mod",
        "happy_ghast_legacy",
        "thecopperrail",
        "centifolia"
    );

    static final Set<String> NAMESPACES = Set.copyOf(MOD_IDS);

    /**
     * Centifolia deliberately inherits vanilla Java item models and ships no PNG textures of its
     * own. These vanilla Bedrock atlas paths are therefore explicit mappings, rather than the
     * unrelated first texture from another Fabric mod.
     */
    static final Map<String, String> VANILLA_ITEM_TEXTURES = Map.of(
        "centifolia:west_wind", "textures/items/glass_pane_top_gray",
        "centifolia:wild_dog_milk", "textures/items/potion_bottle_drinkable"
    );

    private CompatibilityTargets() {
    }
}
