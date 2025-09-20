package de.ella.stackworld;

import org.bukkit.*;
import org.bukkit.block.Block;

final class Safety {
    static Location ensureSafeLanding(Location dest, boolean enablePlatform, Material platformBlock, int size) {
        World w = dest.getWorld();
        if (w == null) return dest;

        int y = dest.getBlockY();
        // Try to nudge up until we find air with solid block below
        for (int i=0; i<6; i++) {
            if (isSafe(dest)) return dest;
            dest.add(0, 1, 0);
        }
        if (!enablePlatform) return dest;

        // build small platform
        int half = Math.max(1, size / 2);
        int yBelow = dest.getBlockY() - 1;
        for (int dx=-half; dx<=half; dx++) {
            for (int dz=-half; dz<=half; dz++) {
                Block b = w.getBlockAt(dest.getBlockX()+dx, yBelow, dest.getBlockZ()+dz);
                b.setType(platformBlock, false);
            }
        }
        return dest;
    }

    private static boolean isSafe(Location l) {
        World w = l.getWorld();
        if (w == null) return true;
        Block feet = w.getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        Block head = w.getBlockAt(l.getBlockX(), l.getBlockY()+1, l.getBlockZ());
        Block below = w.getBlockAt(l.getBlockX(), l.getBlockY()-1, l.getBlockZ());
        return feet.isEmpty() && head.isEmpty() && below.getType().isSolid();
    }
}
