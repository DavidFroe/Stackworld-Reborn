package de.ella.stackworld;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

final class BandCopier {
    private final JavaPlugin plugin;
    private final World src, dst;
    private final int srcY0, dstY0;
    private final int x0, z0, w, d, h;
    private final CopyMode mode;
    private final boolean cleanup;
    private final Set<Material> blacklist;

    private int xi=0, zi=0, yi=0;
    private boolean cleaningPhase=false;

    BandCopier(JavaPlugin plugin, World src, World dst, int srcY0, int dstY0,
               int x0, int z0, int w, int d, int h,
               CopyMode mode, boolean cleanup, Set<Material> blacklist) {
        this.plugin = plugin;
        this.src = src;
        this.dst = dst;
        this.srcY0 = srcY0;
        this.dstY0 = dstY0;
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.d = d;
        this.h = h;
        this.mode = mode;
        this.cleanup = cleanup;
        this.blacklist = blacklist;
    }

    void start(int blocksPerTick) {
        new BukkitRunnable() {
            @Override public void run() {
                int budget = blocksPerTick;
                while (budget-- > 0) {
                    if (advance()) return; // finished
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean advance() {
        if (cleanup && !cleaningPhase) {
            // set dest slice to air first
            Block b = dst.getBlockAt(x0 + xi, dstY0 + yi, z0 + zi);
            b.setBlockData(Bukkit.createBlockData(Material.AIR), false);
            if (!inc()) return true;
            if (xi==0 && zi==0 && yi==0) cleaningPhase = true; // finished cleaning
            return false;
        }

        // copy
        Block srcB = src.getBlockAt(x0 + xi, srcY0 + yi, z0 + zi);
        Block dstB = dst.getBlockAt(x0 + xi, dstY0 + yi, z0 + zi);

        Material m = srcB.getType();
        if (!blacklist.contains(m)) {
            if (mode == CopyMode.OVERWRITE || dstB.getType() == Material.AIR) {
                dstB.setBlockData(srcB.getBlockData(), false);
            }
        }

        return !inc();
    }

    private boolean inc() {
        xi++;
        if (xi >= w) { xi = 0; zi++; }
        if (zi >= d) { zi = 0; yi++; }
        return yi < h;
    }
}
