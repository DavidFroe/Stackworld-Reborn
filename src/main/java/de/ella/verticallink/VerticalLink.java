package de.ella.verticallink;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class VerticalLink extends JavaPlugin implements Listener {

 // Fortschritt & Sicherheit
 private boolean progressActionbar;
 private int progressIntervalTicks;
// Sicherheit
  private long holdDuringCopyMs;
  private boolean safetyAnchorGlass;
  private Material safetyAnchorMaterial;
private enum LinkDir { UP, DOWN }
// TileEntities kopieren (Kisten etc.)
private boolean copyTileEntities;
// Link-Mitte & Hysterese
private int linkHysteresis; // Blöcke Abstand von der Mitte, bevor umgeschaltet wird

// Laufende Kopien tracken
private final Map<UUID, CopySession> activeCopies = new HashMap<>();


    private String fromWorldName, toWorldName;
    private int topTriggerOffset, topLandingY, topHysteresis;
    private int bottomSoftY, bottomHardY, bottomLandingYOffset;
    private int freezeTicks, cooldownMs;
    private boolean ready = false;
    private boolean platformEnabled;
    private Material platformBlock;
    private int platformSize;
	private long tpCooldownMs;
    private boolean copyEnabled;
    private int copyAreaXZ, copyHeight, copyBlocksPerTick;
    private CopyMode copyMode;


// Copy / Modes
private boolean cleanupBeforeCopy;   // Ziel-Slice vorher auf AIR setzen
private boolean teleportOnly;        // nur Spieler teleportieren, keine Kopie

// Easy mode
private boolean easyMode;
private int easyRadius;              // Suchradius für sichere Landeposition


private Set<Material> copyBlacklist = EnumSet.noneOf(Material.class);


// Neue Offsets & Optionen
private int  skyLandingOffsetFromMin;        // Overworld → Sky: Y = sky.minY + offset
private int  overworldLandingOffsetFromMax;  // Sky → Overworld: Y = overworld.maxY - offset
private int  bottomSoftOffsetFromMin;        // Soft-Trigger in Sky: sky.minY + offset
private int  bottomHardOffsetFromMin;        // Hard-Trigger in Sky: sky.minY + offset
private boolean carveAirAtLanding;           // Zielbereich (Fuß/Kopf) freischneiden

private boolean debug;

private void dbg(String msg) { if (debug) getLogger().info("[DBG] " + msg); }
private void dbg(String fmt, Object... args) { if (debug) getLogger().info("[DBG] " + String.format(Locale.ROOT, fmt, args)); }



private int skyBottomY(World sky) { return sky.getMinHeight() + skyLandingOffsetFromMin; }
private int overworldTopY(World from) { return from.getMaxHeight() - overworldLandingOffsetFromMax; }
private int skySoftY(World sky) { return sky.getMinHeight() + bottomSoftOffsetFromMin; }
private int skyHardY(World sky) { return sky.getMinHeight() + bottomHardOffsetFromMin; }



    private final Map<UUID, Long> lastCopy = new HashMap<>();
	private final Map<UUID, Long> lastTp = new HashMap<>();
    private final Map<UUID, Long> lastFreeze = new HashMap<>();
    private final Map<UUID, Domain> lastDomain = new HashMap<>(); // Hysterese oben/unten

    enum CopyMode { AIR_ONLY, OVERWRITE }
    enum Domain { FROM, TO }


 @Override public void onEnable() {
    saveDefaultConfig();
    reloadAll();

    // Lautes Banner
    getLogger().info("=== VerticalLink Boot ===");
    getLogger().info("fromWorld=" + fromWorldName + "  toWorld=" + toWorldName);

    ready = validateSetup();
    if (!ready) {
        getLogger().severe("VerticalLink DISABLED: Setup invalid (siehe Meldungen oben).");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }

    getServer().getPluginManager().registerEvents(this, this);
    dumpWorldInfo(); // einmal min/max/triggerY ins Log
    getLogger().info("VerticalLink ready.");
}

    @Override public void onDisable() {
        // nothing
    }

    private void reloadAll() {
        FileConfiguration c = getConfig();
		tpCooldownMs = getConfig().getLong("tpCooldownMs", 60000L); // 60s
        fromWorldName = c.getString("fromWorld", "world");
        toWorldName   = c.getString("toWorld", "skylands");
		skyLandingOffsetFromMin      = getConfig().getInt("skyLandingOffsetFromMin", 1);
		overworldLandingOffsetFromMax= getConfig().getInt("overworldLandingOffsetFromMax", 2);
		bottomSoftOffsetFromMin      = getConfig().getInt("bottomSoftOffsetFromMin", 4);
		bottomHardOffsetFromMin      = getConfig().getInt("bottomHardOffsetFromMin", 1);
		carveAirAtLanding            = getConfig().getBoolean("carveAirAtLanding", true);
		
		debug = getConfig().getBoolean("debug", false);

        topTriggerOffset = c.getInt("topTriggerOffset", 0);
        topLandingY      = c.getInt("topLandingY", 100);
        topHysteresis    = c.getInt("topHysteresis", 20);

        bottomSoftY          = c.getInt("bottomSoftY", -120);
        bottomHardY          = c.getInt("bottomHardY", -127);
        bottomLandingYOffset = c.getInt("bottomLandingYOffset", -113);

        freezeTicks = c.getInt("freezeTicks", 40);
        cooldownMs  = c.getInt("cooldownMs", 15000);

        platformEnabled = c.getBoolean("safePlatform.enabled", true);
        platformBlock   = Material.matchMaterial(c.getString("safePlatform.block", "GLASS"));
        platformSize    = Math.max(1, c.getInt("safePlatform.size", 3));
        if (platformBlock == null) platformBlock = Material.GLASS;

        copyEnabled       = c.getBoolean("copy.enabled", true);
        copyAreaXZ        = Math.max(1, c.getInt("copy.areaXZ", 201));
        copyHeight        = Math.max(1, c.getInt("copy.height", 20));
        copyBlocksPerTick = Math.max(1, c.getInt("copy.blocksPerTick", 4000));
        copyMode          = CopyMode.valueOf(c.getString("copy.mode", "AIR_ONLY").toUpperCase(Locale.ROOT));
		copyTileEntities = c.getBoolean("copy.tileEntities", true);
		
		//progressActionbar    = c.getBoolean("progress.actionbar", true);
		//progressIntervalTicks= Math.max(1, c.getInt("progress.intervalTicks", 10));
		
		holdDuringCopyMs     = c.getLong("safety.holdDuringCopyMs", 10000L);
		safetyAnchorGlass    = c.getBoolean("safety.anchorGlass", true);
		String sMat          = c.getString("safety.anchorMaterial", "GLASS");
		safetyAnchorMaterial = Optional.ofNullable(Material.matchMaterial(sMat)).orElse(Material.GLASS);

  
  
  
  
copyBlacklist.clear();
List<String> bl = c.getStringList("copy.blacklist");
for (String name : bl) {
    try {
        Material m = Material.valueOf(name.toUpperCase(Locale.ROOT));
        copyBlacklist.add(m);
    } catch (IllegalArgumentException ex) {
        getLogger().warning("Unknown material in copy.blacklist: " + name);
    }
}
dbg("Blacklist size=%d %s", copyBlacklist.size(), copyBlacklist.toString());

// copy.mode: Standard lieber OVERWRITE
String modeStr = c.getString("copy.mode", null);
if (modeStr != null) {
    copyMode = CopyMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
} else {
    // Backcompat: "copy.override" oder "override" akzeptieren
    boolean legacyOverride = c.getBoolean("copy.override", c.getBoolean("override", false));
    copyMode = legacyOverride ? CopyMode.OVERWRITE : CopyMode.AIR_ONLY;
}

// Harte Vorreinigung
cleanupBeforeCopy = c.getBoolean("copy.cleanup", true);

// Nur Teleport (keine Kopie)
teleportOnly = c.getBoolean("teleport.onlyPlayer", false);

// Easy Mode (sichere Landeposition suchen)
easyMode   = c.getBoolean("easymode.enabled", false);
easyRadius = Math.max(1, c.getInt("easymode.radius", 12));

linkHysteresis = Math.max(0, getConfig().getInt("link.hysteresis", 3));


  }

@EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
public void onMove(PlayerMoveEvent e) {
    if (!ready) return;
    if (e.getTo() == null) return;

    final Player p = e.getPlayer();
    final Location to = e.getTo();
    final World w = to.getWorld();
    if (w == null) return;

    dbg("move: %s in %s toY=%.2f", p.getName(), w.getName(), to.getY());

    final World from = Bukkit.getWorld(fromWorldName);
    final World sky  = Bukkit.getWorld(toWorldName);
    if (from == null || sky == null) return;


        // --- TOP: Overworld -> Sky ---
if (w.getName().equalsIgnoreCase(fromWorldName)) {
    double mid = owMidY(from);
    if (to.getY() >= mid + linkHysteresis) {
        if (tpOnCooldown(p)) return;
        freeze(p);

        double delta = to.getY() - mid;                // relativer Versatz zur Mitte
        double yDest = skyMidY(sky) + delta;           // gleiche Position relativ zur Mitte oben
        Location dest = clampToWorldBounds(sky, to.getX(), yDest, to.getZ());
        carveLandingSpace(dest);
        tp(p, dest);
        touchTp(p);

        if (!teleportOnly && copyEnabled && readyForCopy(p)) {
            scheduleCopy(LinkDir.UP, p, from, sky, to, dest);
            touchCopy(p);
        }
        lastDomain.put(p.getUniqueId(), Domain.TO);
        return;
    }
}


// --- Mitte-Regel in Sky: bei y <= Mitte - Hysterese -> zurück in Overworld ---
if (w.getName().equalsIgnoreCase(toWorldName)) {
    double mid = skyMidY(sky);
    if (to.getY() <= mid - linkHysteresis) {
        if (tpOnCooldown(p)) return;
        freeze(p);

        double delta = to.getY() - mid;
        double yDest = owMidY(from) + delta;
        Location dest = clampToWorldBounds(from, to.getX(), yDest, to.getZ());
        carveLandingSpace(dest);
        tp(p, dest);
        touchTp(p);

        if (!teleportOnly && copyEnabled && readyForCopy(p)) {
            scheduleCopy(LinkDir.DOWN, p, sky, from, to, dest);
            touchCopy(p);
        }
        lastDomain.put(p.getUniqueId(), Domain.FROM);
        return;
    }
}
	
    }



private double owMidY(World from) { return from.getMaxHeight() - (copyHeight / 2.0); }
private double skyMidY(World sky)  { return sky.getMinHeight() + (copyHeight / 2.0); }


private int findTopmostSolidY(World w, int x, int z, int y1, int y2) {
    w.getChunkAt(x >> 4, z >> 4).load();
    for (int y = y2; y >= y1; y--) {
        Material m = w.getBlockAt(x, y, z).getType();
        if (!m.isAir() && !copyBlacklist.contains(m)) return y;
    }
    return -1;
}

private int findTopmostSolidInRadius(World w, int cx, int cz, int minY, int maxY, int radius) {
    int bestY = -1;
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
            int x = cx + dx, z = cz + dz;
            w.getChunkAt(x >> 4, z >> 4).load();
            for (int y = maxY; y >= minY; y--) {
                Material m = w.getBlockAt(x, y, z).getType();
                if (!m.isAir() && !copyBlacklist.contains(m)) {
                    if (y > bestY) bestY = y;
                    break;
                }
            }
        }
    }
    return bestY;
}



private void scheduleCopy(LinkDir dir, Player who, World src, World dst,
                          Location playerAt, Location playerDest) {
    final int srcY0 = (dir == LinkDir.UP)   ? (src.getMaxHeight() - copyHeight) : src.getMinHeight();
    final int dstY0 = (dir == LinkDir.UP)   ?  dst.getMinHeight()               : (dst.getMaxHeight() - copyHeight);
    startCopyWithSafety(dir.name(), who, src, dst, playerAt, playerDest, srcY0, dstY0);
}

private int findBottommostSolidY(World w, int x, int z, int y1, int y2) {
    w.getChunkAt(x >> 4, z >> 4).load();
    for (int y = y1; y <= y2; y++) {
        Material m = w.getBlockAt(x, y, z).getType();
        if (!m.isAir() && !copyBlacklist.contains(m)) return y;
    }
    return -1;
}


private boolean tpOnCooldown(Player p) {
    Long last = lastTp.get(p.getUniqueId());
    return last != null && System.currentTimeMillis() - last < tpCooldownMs;
}
private void touchTp(Player p) {
    lastTp.put(p.getUniqueId(), System.currentTimeMillis());
}

private static final class CopySession {
    final UUID playerId;
    final World src, dst;
    final int srcY0, dstY0, areaXZ, height, totalBlocks;
    final long startedAt = System.currentTimeMillis();
  //  int processedCleanup = 0;
  //  int processedCopy    = 0;
   boolean cleaning = false;

    // Safety-Anker (Block unter Füßen)
    final int ax, ay, az;
    final World aw;

    // Tasks
    //int progressTaskId = -1;
    int holdTaskId     = -1;

    CopySession(UUID pid, World s, World d, int sY0, int dY0, int area, int h, World aw, int ax, int ay, int az) {
        this.playerId = pid; this.src = s; this.dst = d;
        this.srcY0 = sY0; this.dstY0 = dY0; this.areaXZ = area; this.height = h;
        this.totalBlocks = area * area * h;
        this.aw = aw; this.ax = ax; this.ay = ay; this.az = az;
    }
}


private boolean validateSetup() {
    World from = Bukkit.getWorld(fromWorldName);
    World to   = Bukkit.getWorld(toWorldName);

    if (from == null) {
        getLogger().severe("fromWorld '" + fromWorldName + "' ist NICHT geladen!");
        getLogger().severe("→ Existiert die Welt? level-name in server.properties prüfen oder /mv list.");
        return false;
    }
    if (to == null) {
        getLogger().severe("toWorld '" + toWorldName + "' ist NICHT geladen!");
        getLogger().severe("→ Lege sie an: /mv create " + toWorldName + " normal -g OldGenerator:sb173");
        return false;
    }

    // Sanity: topLandingY innerhalb Zielwelt klemmen
    int tMin = to.getMinHeight(), tMax = to.getMaxHeight();
    if (topLandingY < tMin + 2 || topLandingY > tMax - 2) {
        getLogger().warning("topLandingY (" + topLandingY + ") liegt außerhalb [" + (tMin+2) + ".." + (tMax-2) + "], klemme automatisch.");
        topLandingY = Math.max(tMin + 2, Math.min(tMax - 2, topLandingY));
    }

    return true;
}

private void dumpWorldInfo() {
    World from = Bukkit.getWorld(fromWorldName);
    World to   = Bukkit.getWorld(toWorldName);
    if (from == null || to == null) return;

    int fMin = from.getMinHeight(), fMax = from.getMaxHeight();
    int tMin = to.getMinHeight(),   tMax = to.getMaxHeight();
    int topTriggerY = fMax - topTriggerOffset;

    getLogger().info(String.format(
        Locale.ROOT,
        "fromWorld[%s]: min=%d max=%d  |  toWorld[%s]: min=%d max=%d",
        fromWorldName, fMin, fMax, toWorldName, tMin, tMax
    ));
    getLogger().info(String.format(
        Locale.ROOT,
        "Trigger oben (Overworld→Sky): Y=%d  | Hysterese oben=%d  | Soft/Hard unten (Sky→Overworld): %d/%d",
        topTriggerY, topHysteresis, bottomSoftY, bottomHardY
    ));
}






    private void freeze(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastFreeze.get(p.getUniqueId());
        if (last != null && now - last < 150) return; // entprellen
        lastFreeze.put(p.getUniqueId(), now);

        // Physik stoppen
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);
        // Spielerseitig „einfrieren“
        p.setFreezeTicks(Math.max(p.getFreezeTicks(), freezeTicks));

        // Sicherheit: kurz später wieder freigeben
        new BukkitRunnable() { @Override public void run() {
            p.setFreezeTicks(0);
        }}.runTaskLater(this, freezeTicks + 5L);
    }

    private void tp(Player p, Location dest) {
        if (dest.getWorld() == null) return;
        // Clamp auf Bounds
        dest = clampToWorldBounds(dest.getWorld(), dest.getX(), dest.getY(), dest.getZ());
        p.setFallDistance(0f);
        p.teleport(dest);
    }

private void carveLandingSpace(Location loc) {
    if (!carveAirAtLanding || loc.getWorld() == null) return;
    World w = loc.getWorld();
    int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
    // Füße + Kopf frei
    w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
    w.getBlockAt(bx, by + 1, bz).setType(Material.AIR, false);
}

private Location clampToWorldBounds(World w, double x, double y, double z) {
    int min = w.getMinHeight();
    int max = w.getMaxHeight();
    double cy = Math.max(min + 1, Math.min(max - 1, y));
    return new Location(w, x, cy, z);
}


    private void safePlatformIfNeeded(Location loc) {
        if (!platformEnabled || loc.getWorld() == null) return;
        Location below = loc.clone().add(0, -1, 0);
        World w = loc.getWorld();
        int bx = below.getBlockX(), by = below.getBlockY(), bz = below.getBlockZ();
        // baue nur, wenn darunter keine solide Fläche ist
        if (!w.getBlockAt(bx, by, bz).getType().isSolid()) {
            int r = platformSize / 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = w.getBlockAt(bx + dx, by, bz + dz);
                    if (b.getType() == Material.AIR) {
                        b.setType(platformBlock, false); // kein Physik-Spam
                    }
                }
            }
        }
    }

    /* -------------------- Copy-Jobs (gedrosselt) -------------------- */

    private boolean readyForCopy(Player p) {
        Long last = lastCopy.get(p.getUniqueId());
        return last == null || System.currentTimeMillis() - last >= cooldownMs;
    }

    private void touchCopy(Player p) {
        lastCopy.put(p.getUniqueId(), System.currentTimeMillis());
    }
private void scheduleCopyUp(Player who, World src, World dst, Location playerAt, Location playerDest) {
     scheduleCopy(LinkDir.UP, who, src, dst, playerAt, playerDest);
 }
 private void scheduleCopyDown(Player who, World src, World dst, Location playerAt, Location playerDest) {
     scheduleCopy(LinkDir.DOWN, who, src, dst, playerAt, playerDest);
}

private void onCopyFinished(CopySession cs, String tag) {
    try {
Block anchorDst = cs.dst.getBlockAt(cs.ax, cs.ay, cs.az);
int offsetY = cs.ay - cs.dstY0;
int sy = cs.srcY0 + offsetY;

Block srcAtAnchor = cs.src.getBlockAt(cs.ax, sy, cs.az);
if (!copyBlacklist.contains(srcAtAnchor.getType())) {
    // Exakt den Quellblock setzen (Typ & Data)
    if (!anchorDst.getBlockData().matches(srcAtAnchor.getBlockData())) {
        anchorDst.setBlockData(srcAtAnchor.getBlockData(), false);
    }
    // Container-Inventar zum Schluss kopieren
    if (copyTileEntities) {
        org.bukkit.block.BlockState s = srcAtAnchor.getState();
        org.bukkit.block.BlockState d = anchorDst.getState();
        if (s instanceof org.bukkit.block.Container srcCon && d instanceof org.bukkit.block.Container dstCon) {
            dstCon.getInventory().setContents(srcCon.getInventory().getContents());
            dstCon.update(true, false);
        }
    }
} else {
    anchorDst.setType(Material.AIR, false);
}
    } catch (Throwable t) {
        getLogger().warning("onCopyFinished: " + t.getMessage());
    } finally {
        // Progress/Hold-Tasks beenden
    //    if (cs.progressTaskId != -1) getServer().getScheduler().cancelTask(cs.progressTaskId);
        if (cs.holdTaskId != -1) getServer().getScheduler().cancelTask(cs.holdTaskId);
        activeCopies.remove(cs.playerId);
    }

dbg("COPY finished %s in %d ms", tag, (System.currentTimeMillis() - cs.startedAt));
}



private void startCopyWithSafety(String tag, Player who, World src, World dst,
                                 Location playerAt, Location playerDest,
                                 int srcY0, int dstY0) {

    // Safety-Anker-Block unter Füßen im Ziel
    Block under = dst.getBlockAt(playerDest.getBlockX(), playerDest.getBlockY() - 1, playerDest.getBlockZ());
    if (safetyAnchorGlass) {
        if (under.getType() == Material.AIR || under.getType().isOccluding() == false) {
            under.setType(safetyAnchorMaterial, false);
        }
    }

    CopySession cs = new CopySession(
            who.getUniqueId(), src, dst, srcY0, dstY0, copyAreaXZ, copyHeight,
            dst, under.getX(), under.getY(), under.getZ()
    );
    cs.cleaning = cleanupBeforeCopy;
    activeCopies.put(who.getUniqueId(), cs);

 //   // Fortschritt Actionbar
 //   if (progressActionbar) {
 //       cs.progressTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
 //           Player p = Bukkit.getPlayer(cs.playerId);
 //           if (p == null) return;
 //           int done = cs.processedCleanup + cs.processedCopy;
 //           int pct  = (int)Math.round((done * 100.0) / Math.max(1, cs.totalBlocks));
 //           long eta = (System.currentTimeMillis() - cs.startedAt);
 //           p.sendActionBar(Component.text("§7Link " + tag + " §f" + pct + "%  §8(" + done + "/" + cs.totalBlocks + ", " + (eta/1000) + "s)"));
 //       }, 1L, Math.max(1, progressIntervalTicks));
 //   }

    // Halten: nicht runterfallen lassen
    if (holdDuringCopyMs > 0) {
        final long until = System.currentTimeMillis() + holdDuringCopyMs;
        final Location anchor = playerDest.clone();
        cs.holdTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            Player p = Bukkit.getPlayer(cs.playerId);
            if (p == null) return;
            if (System.currentTimeMillis() > until || !activeCopies.containsKey(cs.playerId)) {
                // Ende
                getServer().getScheduler().cancelTask(cs.holdTaskId);
                return;
            }
            if (p.getWorld() == anchor.getWorld() && p.getLocation().getY() < anchor.getY() - 0.4) {
                p.setVelocity(new Vector(0,0,0));
                p.teleport(anchor);
            }
        }, 1L, 5L);
    }

    // eigentliche Kopie starten
    scheduleCopySlice(cs, playerAt, tag);
}
private void scheduleCopySlice(CopySession cs, Location center, String tag) {
    final int cx = center.getBlockX();
    final int cz = center.getBlockZ();

    getLogger().info(String.format(
            java.util.Locale.ROOT,
            "[VerticalLink] Link %s @ (%d,%d) slice %dx%dx%d blocks",
            tag, cx, cz, cs.areaXZ, cs.height, cs.areaXZ));

  new BukkitRunnable() {
        final int half = cs.areaXZ / 2;
        final int fCx = cx;
        final int fCz = cz;

        int x = -half, y = 0, z = -half;
     int prevScx = Integer.MIN_VALUE, prevScz = Integer.MIN_VALUE;
       int prevDcx = Integer.MIN_VALUE, prevDcz = Integer.MIN_VALUE;

        @Override public void run() {
            int processed = 0;

            while (processed < copyBlocksPerTick) {
                if (y >= cs.height) {
                    if (cs.cleaning) {
                        cs.cleaning = false;
                        x = -half; y = 0; z = -half;
                        continue;
                    } else {
                        onCopyFinished(cs, tag);
                        cancel();
                        return;
                    }
                }

                int sx = fCx + x;
                int sy = cs.srcY0 + y;
                int sz = fCz + z;

                int dx = fCx + x;
                int dy = cs.dstY0 + y;
                int dz = fCz + z;


              int scx = sx >> 4, scz = sz >> 4;
              if (scx != prevScx || scz != prevScz) {
                  cs.src.getChunkAt(scx, scz).load();
                  prevScx = scx; prevScz = scz;
              }
              int dcx = dx >> 4, dcz = dz >> 4;
              if (dcx != prevDcx || dcz != prevDcz) {
                  cs.dst.getChunkAt(dcx, dcz).load();
                  prevDcx = dcx; prevDcz = dcz;
              }

                boolean isAnchor = (dx == cs.ax && dy == cs.ay && dz == cs.az);

                if (cs.cleaning) {
                    if (!isAnchor) {
                        Block dstBlock = cs.dst.getBlockAt(dx, dy, dz);
                        if (dstBlock.getType() != Material.AIR) dstBlock.setType(Material.AIR, false);
                    }

                } else {
                    Block srcBlock = cs.src.getBlockAt(sx, sy, sz);
                    Block dstBlock = cs.dst.getBlockAt(dx, dy, dz);
                    Material srcMat = srcBlock.getType();
if (copyTileEntities) {
    org.bukkit.block.BlockState srcState = srcBlock.getState();

    if (srcState instanceof org.bukkit.block.Container srcCon) {
        // sicherstellen, dass Ziel wirklich derselbe Containertyp ist
        if (!dstBlock.getBlockData().matches(srcBlock.getBlockData())) {
            dstBlock.setBlockData(srcBlock.getBlockData(), false);
        }

        org.bukkit.block.BlockState dstState = dstBlock.getState();
        if (dstState instanceof org.bukkit.block.Container dstCon) {
            dstCon.getInventory().setContents(srcCon.getInventory().getContents());
            dstCon.update(true, false);
        }
    }
}
                    if (!isAnchor) {
                        if (copyBlacklist.contains(srcMat)) {
                            if (copyMode == CopyMode.OVERWRITE && dstBlock.getType() != Material.AIR) {
                                dstBlock.setType(Material.AIR, false);
                            }
                        } else {
                            if (copyMode == CopyMode.OVERWRITE) {
                                if (!dstBlock.getBlockData().matches(srcBlock.getBlockData())) {
                                    dstBlock.setBlockData(srcBlock.getBlockData(), false);
                                }
                            } else { // AIR_ONLY
                                if (dstBlock.getType() == Material.AIR && srcMat != Material.AIR) {
                                    dstBlock.setBlockData(srcBlock.getBlockData(), false);
                                }
                            }

                            if (copyTileEntities
                                    && srcBlock.getState() instanceof org.bukkit.block.Container srcCon
                                    && dstBlock.getState() instanceof org.bukkit.block.Container dstCon) {
                                dstCon.getInventory().setContents(srcCon.getInventory().getContents());
                                dstCon.update(true, false);
                            }
                        }
                    }

                }

                processed++;
                z++;
                if (z > half) { z = -half; x++; }
                if (x > half) { x = -half; y++; }
            }
        }
    }.runTaskTimer(this, 1L, 1L);
} // <-- Ende scheduleCopySlice



    /* -------------------- (Optional) Command: /verticallink reload -------------------- */

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender,
                             org.bukkit.command.Command cmd,
                             String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("verticallink")) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("verticallink.reload")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            reloadConfig(); reloadAll();
            sender.sendMessage("§aVerticalLink reloaded.");
            return true;
        }
		if (args.length > 0 && args[0].equalsIgnoreCase("info")) {
			World from = Bukkit.getWorld(fromWorldName);
			World to   = Bukkit.getWorld(toWorldName);
			sender.sendMessage("§7VerticalLink: " + (ready ? "§aREADY" : "§cNOT READY"));
			sender.sendMessage("§7fromWorld: §f" + fromWorldName + " §7loaded=" + (from != null));
			sender.sendMessage("§7toWorld:   §f" + toWorldName   + " §7loaded=" + (to   != null));
			if (from != null) sender.sendMessage("§7fromWorld Y: §f" + from.getMinHeight() + " .. " + from.getMaxHeight());
			if (to   != null) sender.sendMessage("§7toWorld   Y: §f" + to.getMinHeight()   + " .. " + to.getMaxHeight());
			return true;
		}

		
		
		
        sender.sendMessage("§7Usage: /verticallink reload");
        return true;
    }
}
