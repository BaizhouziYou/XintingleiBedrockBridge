# Xintinglei Bedrock Bridge

Fabric 1.21.4 server-side diagnostics for the Xintinglei Bedrock compatibility project.

The bridge exports the actual runtime registry identifiers and raw IDs for the two fixed target mods:

- `better_mcdonalds_mod` 4.4.1+1.21.4
- `happy_ghast_legacy` 1.8.1

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
