# Stackworld-Reborn

A Paper/Spigot plugin that “stacks” two worlds vertically. When players reach a configurable band (e.g., 20 blocks tall), they are teleported between worlds and that band is copied 1:1 — including block states (orientation), optionally tile entities (chests, barrels, shulkers), blacklist, cleanup, and a safety anchor that prevents falling during copy. An “only player teleport” mode and an easy mode for safe landing are included.

## Features
- 🚀 **Vertical link** between two worlds (e.g., Overworld ↔ Skylands)
- 📦 **Copy band** (area/height configurable), **OVERWRITE** or **AIR_ONLY**
- 🧭 **Block states preserved** (stairs, ladders, etc.)
- 📚 **Optional tile entity copy** (chests, barrels, shulkers)
- 🧼 **Cleanup phase**: clear destination slice before copying
- 🛑 **Copy blacklist** (skip water/lava/glass, etc.)
- 🧲 **Safety anchor** (temporary block under player), optional short hold
- 🧘 **Teleport-only mode** (no copying), **Easy mode** (safe landing search)
- 🪜 **Mid-band teleport** (optional): teleport around the band center with hysteresis to avoid ping-pong

## Requirements
- Paper/Spigot **1.21+**
- Java **21**
- Maven to build

## Build & Install
1. `mvn -DskipTests package`
2. Copy the built JAR from `target/` to your server’s `plugins/` folder
3. Start the server once to generate `plugins/VerticalLink/config.yml`
4. Adjust `config.yml`, then `/verticallink reload`

## Commands & Permissions
- `/verticallink reload` — `verticallink.reload`
- `/verticallink info` — open to all

## Configuration (excerpt)
```yml
# Worlds
fromWorld: "world"
toWorld:   "skylands"

# Copy band
copy:
  enabled: true
  areaXZ: 201           # square width/depth
  height: 20            # band height
  blocksPerTick: 4000   # throttle
  mode: OVERWRITE       # OVERWRITE | AIR_ONLY
  cleanup: true         # clear destination band first
  blacklist:
    - WATER
    - LAVA
    - GLASS
  tileEntities: true    # copy inventories for chests/barrels/shulkers

# Teleport
tpCooldownMs: 60000
safePlatform:
  enabled: true
  block: GLASS
  size: 3

# Safety during copy
safety:
  holdDuringCopyMs: 10000
  anchorGlass: true
  anchorMaterial: GLASS

# Easy mode (find a safe landing nearby)
easymode:
  enabled: false
  radius: 12

# Mid-band teleport (instead of top/bottom of the band)
midTeleport:
  enabled: true
  hysteresis: 6   # ±3 around the band center
