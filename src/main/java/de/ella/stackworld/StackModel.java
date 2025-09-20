package de.ella.stackworld;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

enum Direction { UP, DOWN; }

record BandInfo(String world, String info) {
    public String toString() { return "[" + world + "] " + info; }
}

record BandEvent(Edge edge, Direction direction) {}

final class StackModel {
    final String baseWorld;
    final List<String> up;    // ordered top
    final List<String> down;  // ordered bottom
    final Map<String, Integer> bottomBoundaryOverride;

    StackModel(String baseWorld, List<String> up, List<String> down, Map<String,Integer> bottomOverrides) {
        this.baseWorld = baseWorld;
        this.up = up;
        this.down = down;
        this.bottomBoundaryOverride = bottomOverrides;
    }

    static StackModel fromConfig(FileConfiguration c) {
        String base = c.getString("baseWorld", "world");

        List<String> up = new ArrayList<>();
        List<String> down = new ArrayList<>();
        Map<String,Integer> overrides = new HashMap<>();

        ConfigurationSection upSec = c.getConfigurationSection("stack.up");
        if (upSec != null) {
            for (Object o : upSec.getValues(false).values()) {
                if (o instanceof ConfigurationSection s) {
                    String name = s.getString("name", null);
                    if (name != null) up.add(name);
                    if (s.contains("bottomBoundaryY")) overrides.put(name, s.getInt("bottomBoundaryY"));
                }
            }
        }
        ConfigurationSection dnSec = c.getConfigurationSection("stack.down");
        if (dnSec != null) {
            for (Object o : dnSec.getValues(false).values()) {
                if (o instanceof ConfigurationSection s) {
                    String name = s.getString("name", null);
                    if (name != null) down.add(name);
                    if (s.contains("bottomBoundaryY")) overrides.put(name, s.getInt("bottomBoundaryY"));
                }
            }
        }
        return new StackModel(base, up, down, overrides);
    }

    List<Edge> edges() {
        List<Edge> list = new ArrayList<>();
        // upwards
        String prev = baseWorld;
        for (String w : up) {
            list.add(new Edge(prev, w, this));
            prev = w;
        }
        // downwards
        prev = baseWorld;
        for (String w : down) {
            list.add(new Edge(w, prev, this)); // note: edge is lower -> higher for bottom side
            prev = w;
        }
        return list;
    }

    int bottomBoundaryY(World w) {
        Integer custom = bottomBoundaryOverride.get(w.getName());
        if (custom != null) return custom;
        return w.getMinHeight();
    }

    BandInfo locate(org.bukkit.Location loc) {
        World w = loc.getWorld();
        if (w == null) return new BandInfo("?", "no world");
        int min = w.getMinHeight();
        int max = w.getMaxHeight();
        return new BandInfo(w.getName(), "y=" + loc.getBlockY() + " (min=" + min + ", max=" + max + ")");
    }

    String debugInfo() {
        return "base=" + baseWorld
                + " | up=" + up
                + " | down=" + down
                + " | edges=" + edges().stream().map(Edge::toString).collect(Collectors.joining(", "));
    }

    BandEvent detectEvent(org.bukkit.Location to, int hysteresis) {
        World w = to.getWorld();
        if (w == null) return null;
        double y = to.getY();

        // check edges touching this world
        for (Edge e : edges()) {
            if (e.fromWorld().equals(w.getName())) {
                // top band of 'from' world -> up
                int bandBottom = w.getMaxHeight() - e.copyHeight();
                double mid = bandBottom + e.copyHeight() / 2.0;
                if (Math.abs(y - mid) <= hysteresis / 2.0) {
                    return new BandEvent(e, Direction.UP);
                }
            }
            if (e.toWorld().equals(w.getName())) {
                // bottom band of 'to' world -> down detection uses that world's bottomBoundary
                int bandBottom = e.bottomBoundaryY(w);
                double mid = bandBottom + e.copyHeight() / 2.0;
                if (Math.abs(y - mid) <= hysteresis / 2.0) {
                    return new BandEvent(e, Direction.DOWN);
                }
            }
        }
        return null;
    }
}

final class Edge {
    private final String fromWorld; // lower
    private final String toWorld;   // upper
    private final StackModel model;
    private final int copyHeightDefault = 32;

    Edge(String lower, String upper, StackModel model) {
        this.fromWorld = lower;
        this.toWorld = upper;
        this.model = model;
    }

    String fromWorld() { return fromWorld; }
    String toWorld() { return toWorld; }
    StackModel model() { return model; }

    int copyHeight() { return copyHeightDefault; }

    int bottomBoundaryY(World w) {
        return model.bottomBoundaryY(w);
    }

    CopySpec effectiveCopySpec(org.bukkit.configuration.file.FileConfiguration c) {
        // per-world override possible
        int h = c.getInt("stack.copy.height", 32);
        int area = c.getInt("stack.copy.areaXZ", 201);
        int bpt = c.getInt("stack.copy.blocksPerTick", 4000);
        String mode = c.getString("stack.copy.mode", "OVERWRITE");
        boolean cleanup = c.getBoolean("stack.copy.cleanup", true);
        return new CopySpec(h, area, bpt, mode.equalsIgnoreCase("AIR_ONLY") ? CopyMode.AIR_ONLY : CopyMode.OVERWRITE, cleanup);
    }

    @Override public String toString() { return fromWorld + "↕" + toWorld; }
}

enum CopyMode { OVERWRITE, AIR_ONLY; }

record CopySpec(int height, int areaXZ, int blocksPerTick, CopyMode mode, boolean cleanup) {
    Set<Material> blacklist(org.bukkit.configuration.file.FileConfiguration cfg) {
        List<String> list = cfg.getStringList("stack.copy.blacklist");
        Set<Material> m = EnumSet.noneOf(Material.class);
        m.add(Material.BEDROCK); // always blacklist bedrock
        for (String s : list) {
            Material mat = Material.matchMaterial(s);
            if (mat != null) m.add(mat);
        }
        return m;
    }
}

