package de.ella.stackworld;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

final class WorldBootstrapper {
    static void ensureWorlds(JavaPlugin plugin, StackModel model) {
        List<String> wanted = new ArrayList<>();
        wanted.add(model.baseWorld);
        wanted.addAll(model.up);
        wanted.addAll(model.down);
        for (String name : wanted) {
            if (Bukkit.getWorld(name) == null) {
                plugin.getLogger().info("Creating world: " + name);
                WorldCreator wc = new WorldCreator(name);
                // Best-effort: mapper from config if present
                // (For brevity, we use defaults; advanced generator mapping can be added later)
                wc.environment(World.Environment.NORMAL);
                Bukkit.createWorld(wc);
            }
        }
    }
}
