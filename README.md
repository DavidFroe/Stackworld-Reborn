# Stackworld (v1.2.0)

Multi-world **vertical stacking** for Paper 1.21+: copies the top/bottom **32-block** bands between adjacent worlds and teleports players at the **band center with hysteresis (±3)** to avoid ping-pong. Bedrock is blacklisted from copying so players can keep digging below.

## Commands
- `/stackworld reload` — reloads the config
- `/stackworld info` — prints current stack and edges
- `/stackworld where` — shows your relative band position

## Quick setup
1. Drop the JAR into `plugins/` and start the server once.
2. Edit `plugins/Stackworld/config.yml` (define the `stack.up` and `stack.down` lists).
3. Run `/stackworld reload`.

## Config highlights
- `copy.height: 32` (default)
- Teleport at band center (16) with `teleport.midSwitch.hysteresis: 6` → ±3.
- `copy.blacklist` includes `BEDROCK` by default.

---

> Migration note: if you used a previous plugin called **VerticalLink**, rename your folder to `Stackworld` and update commands from `/verticallink` → `/stackworld`. This build does not depend on the old class/package names.
