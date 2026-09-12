# Xintinglei Bedrock Bridge

Fabric 1.21.4 server-side diagnostics for the Xintinglei Bedrock compatibility project.

The bridge exports the actual runtime registry identifiers and raw IDs for the fixed Bedrock-compatible target mods:

- `better_mcdonalds_mod` 4.4.1+1.21.4
- `happy_ghast_legacy` 1.8.1
- `thecopperrail` 0.9.7-a
- `centifolia` 1.0.0

Centifolia exports its two registered items (`centifolia:west_wind` and
`centifolia:wild_dog_milk`). Its custom enchantments and gameplay mechanics remain authoritative
on the Fabric server; Bedrock clients can hold and consume the two items but do not receive the
optional Fabric client HUD or custom keybind payloads.

It also records each target JAR's SHA-256. A missing target Mod, ambiguous JAR origin, or failed export is a hard failure: the Geyser compatibility layer must remain disabled rather than guess mappings.

## Commands

- `/xbedrock export` regenerates the manifest.
- `/xbedrock status` reports the last successful export.

The server also exports automatically after startup to:

```text
config/xintinglei-bedrock-bridge/compat-manifest.json
```

## Geyser registry-sync gate

Fabric Registry Sync normally disconnects clients that cannot receive its custom registry payload.
For the fixed Geyser path, the bridge can bypass only that configuration task when the downstream
client brand is exactly `Geyser`. Normal Java/Fabric clients keep the original synchronization.

The bypass is disabled unless the Fabric server is started with:

```text
-DXintinglei.AllowGeyserWithoutFabricRegistrySync=true
```

## Build

```powershell
.\gradlew.bat clean build
```

The deployable JAR is written under `build/libs/` without the `-sources` suffix.
