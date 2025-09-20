package de.ella.stackworld;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Stackworld extends JavaPlugin implements Listener {

    private StackModel model;
    private Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    // global copy/teleport defaults (can be overridden per world in config)
    private int defaultCopyHeight = 32;
    private int defaultAreaXZ = 201;
    private int defaultBlocksPerTick = 4000;
    private int hysteresis = 6; // ±3 around band center by default
    private long tpCooldownMs = 60000L;

    private boolean safePlatformEnabled = true;
    private Material safePlatformBlock = Material.GLASS;
    private int safePlatformSize = 3;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("stackworld")).setExecutor(this);
        getLogger().info("Stackworld enabled. Bands=32, mid-switch with hysteresis ±3.");
    }

    @Override
    public void onDisable() {
        // nothing special
    }

    private void reloadAll() {
        reloadConfig();
        FileConfiguration c = getConfig();

        tpCooldownMs = c.getLong("teleport.cooldownMs", 60000L);
        hysteresis = Math.max(0, c.getInt("teleport.midSwitch.hysteresis", 6));

        ConfigurationSection safety = c.getConfigurationSection("safety.safePlatform");
        if (safety != null) {
            safePlatformEnabled = safety.getBoolean("enabled", true);
            safePlatformBlock = Material.matchMaterial(safety.getString("block", "GLASS"));
            if (safePlatformBlock == null) safePlatformBlock = Material.GLASS;
            safePlatformSize = Math.max(1, safety.getInt("size", 3));
        }

        model = StackModel.fromConfig(c);
        // ensure worlds exist (createIfMissing)
        WorldBootstrapper.ensureWorlds(this, model);
    }

    // ===== Commands =====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Stackworld v" + getDescription().getVersion() + " - /" + label + " reload|info|where").color(NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("stackworld.reload")) {
                    sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
                    return true;
                }
                reloadAll();
                sender.sendMessage(Component.text("Stackworld reloaded.").color(NamedTextColor.GREEN));
            }
            case "info" -> {
                if (!sender.hasPermission("stackworld.info")) {
                    sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text(model.debugInfo()).color(NamedTextColor.AQUA));
            }
            case "where" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                BandInfo bi = model.locate(p.getLocation());
                sender.sendMessage(Component.text(bi.toString()).color(NamedTextColor.AQUA));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand. Try reload|info|where").color(NamedTextColor.RED));
        }
        return true;
    }

    // ===== Movement handling =====

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        PlayerState st = states.computeIfAbsent(p.getUniqueId(), id -> new PlayerState());
        Location to = e.getTo();

        BandEvent evt = model.detectEvent(to, hysteresis);
        if (evt == null) return;

        long now = System.currentTimeMillis();
        if (now - st.lastTeleport < tpCooldownMs) return;

        // schedule copy + teleport
        performEdgeTransfer(p, evt);
        st.lastTeleport = now;
    }

    private void performEdgeTransfer(Player p, BandEvent evt) {
        Edge edge = evt.edge();
        boolean upward = evt.direction() == Direction.UP;

        // 1) schedule band copy around player's XZ
        CopySpec spec = edge.effectiveCopySpec(getConfig());
        int area = spec.areaXZ();
        int half = area / 2;
        int copyHeight = spec.height();
        int blocksPerTick = spec.blocksPerTick();

        final World src = Bukkit.getWorld(upward ? edge.fromWorld() : edge.toWorld());
        final World dst = Bukkit.getWorld(upward ? edge.toWorld() : edge.fromWorld());
        if (src == null || dst == null) {
            getLogger().warning("Missing world(s) for edge " + edge);
            return;
        }

        int srcBandBottomY = upward ? src.getMaxHeight() - copyHeight : edge.bottomBoundaryY(src);
        int dstBandBottomY = upward ? edge.bottomBoundaryY(dst) : dst.getMaxHeight() - copyHeight;

        int cx = p.getLocation().getBlockX();
        int cz = p.getLocation().getBlockZ();

        Set<Material> blacklist = spec.blacklist(this.getConfig());

        new BandCopier(this, src, dst, srcBandBottomY, dstBandBottomY, cx - half, cz - half, area, area, copyHeight,
                spec.mode(), spec.cleanup(), blacklist).start(blocksPerTick);

        // 2) teleport near band center (preserve relative Y inside band)
        double relY = p.getLocation().getY() - (srcBandBottomY + copyHeight / 2.0);
        double targetY = dstBandBottomY + (copyHeight / 2.0) + relY;
        Location dest = new Location(dst, p.getLocation().getX(), targetY, p.getLocation().getZ(), p.getLocation().getYaw(), p.getLocation().getPitch());

        dest = Safety.ensureSafeLanding(dest, safePlatformEnabled, safePlatformBlock, safePlatformSize);
        p.teleport(dest);
    }

    // ====== Support classes ======

    static final class PlayerState {
        long lastTeleport = 0;
    }
}
