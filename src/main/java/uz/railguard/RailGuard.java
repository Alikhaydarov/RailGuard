package uz.railguard;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class RailGuard extends JavaPlugin implements Listener {

    /** Bitta relsning saqlangan holati. */
    private static final class Info {
        final int x, y, z;
        String data;     // BlockData string
        String support;  // tagidagi blok materiali
        Info(int x, int y, int z, String data, String support) {
            this.x = x; this.y = y; this.z = z; this.data = data; this.support = support;
        }
    }

    /** world -> chunkKey -> blockKey -> Info */
    private final Map<UUID, Map<Long, Map<Long, Info>>> rails = new HashMap<>();
    /** Vagon uchun oxirgi joy va qancha vaqt qimirlamagani */
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, Integer> idleSeconds = new HashMap<>();
    private boolean dirty = false;
    private File dataFile;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "rails.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        startTasks();
        getLogger().info("RailGuard yoqildi. Kuzatilayotgan relslar: " + countRails());
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void startTasks() {
        Bukkit.getScheduler().cancelTasks(this);

        int repairEvery = Math.max(1, getConfig().getInt("repair.interval-seconds", 10)) * 20;
        if (getConfig().getBoolean("repair.enabled", true)) {
            Bukkit.getScheduler().runTaskTimer(this, this::repairRails, repairEvery, repairEvery);
        }

        // vagonlar har 5 soniyada tekshiriladi
        if (getConfig().getBoolean("minecarts.cleanup-enabled", true)) {
            Bukkit.getScheduler().runTaskTimer(this, () -> cleanMinecarts(5), 100L, 100L);
        }

        if (getConfig().getBoolean("mob-protection.enabled", true)
                && getConfig().getBoolean("mob-protection.clear-existing", true)) {
            int every = Math.max(1, getConfig().getInt("mob-protection.clear-interval-seconds", 5)) * 20;
            Bukkit.getScheduler().runTaskTimer(this, this::clearMobs, every, every);
        }

        // har 5 daqiqada saqlash
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
    }

    // ------------------------------------------------------------------ tracking

    private static long chunkKey(int bx, int bz) {
        return Chunk.getChunkKey(bx >> 4, bz >> 4);
    }

    private void track(Block b) {
        Info i = new Info(b.getX(), b.getY(), b.getZ(),
                b.getBlockData().getAsString(),
                b.getRelative(BlockFace.DOWN).getType().name());
        rails.computeIfAbsent(b.getWorld().getUID(), k -> new HashMap<>())
                .computeIfAbsent(chunkKey(b.getX(), b.getZ()), k -> new HashMap<>())
                .put(Block.getBlockKey(b.getX(), b.getY(), b.getZ()), i);
        dirty = true;
    }

    private void untrack(Block b) {
        Map<Long, Map<Long, Info>> w = rails.get(b.getWorld().getUID());
        if (w == null) return;
        long ck = chunkKey(b.getX(), b.getZ());
        Map<Long, Info> c = w.get(ck);
        if (c == null) return;
        if (c.remove(Block.getBlockKey(b.getX(), b.getY(), b.getZ())) != null) dirty = true;
        if (c.isEmpty()) w.remove(ck);
    }

    private boolean isTracked(Block b) {
        Map<Long, Map<Long, Info>> w = rails.get(b.getWorld().getUID());
        if (w == null) return false;
        Map<Long, Info> c = w.get(chunkKey(b.getX(), b.getZ()));
        return c != null && c.containsKey(Block.getBlockKey(b.getX(), b.getY(), b.getZ()));
    }

    private int countRails() {
        int n = 0;
        for (Map<Long, Map<Long, Info>> w : rails.values())
            for (Map<Long, Info> c : w.values()) n += c.size();
        return n;
    }

    /** Berilgan joy yaqinida (gorizontal r, vertikal vr) kuzatilayotgan rels bormi? */
    private boolean nearRail(Location loc, int r, int vr) {
        Map<Long, Map<Long, Info>> w = rails.get(loc.getWorld().getUID());
        if (w == null) return false;
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int cx = (bx - r) >> 4; cx <= (bx + r) >> 4; cx++) {
            for (int cz = (bz - r) >> 4; cz <= (bz + r) >> 4; cz++) {
                Map<Long, Info> c = w.get(Chunk.getChunkKey(cx, cz));
                if (c == null) continue;
                for (Info i : c.values()) {
                    if (Math.abs(i.x - bx) <= r && Math.abs(i.z - bz) <= r && Math.abs(i.y - by) <= vr)
                        return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (Tag.RAILS.isTagged(b.getType())) track(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        // o'yinchi ataylab buzdi -> kuzatuvdan chiqariladi
        if (isTracked(b)) untrack(b);
        Block above = b.getRelative(BlockFace.UP);
        if (isTracked(above)) untrack(above);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (getConfig().getBoolean("repair.protect-from-explosions", true))
            e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (getConfig().getBoolean("repair.protect-from-explosions", true))
            e.blockList().removeIf(this::isProtected);
    }

    private boolean isProtected(Block b) {
        return isTracked(b) || isTracked(b.getRelative(BlockFace.UP));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!getConfig().getBoolean("mob-protection.enabled", true)) return;
        if (!getConfig().getBoolean("mob-protection.block-spawns", true)) return;
        if (!(e.getEntity() instanceof Enemy)) return;
        switch (e.getSpawnReason()) {
            case SPAWNER_EGG, CUSTOM, COMMAND, DISPENSE_EGG, BUILD_WITHER, BUILD_IRONGOLEM, BUILD_SNOWMAN -> { return; }
            default -> { }
        }
        int r = getConfig().getInt("mob-protection.radius", 8);
        int vr = getConfig().getInt("mob-protection.vertical-radius", 5);
        if (nearRail(e.getLocation(), r, vr)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ repair

    private void repairRails() {
        int max = getConfig().getInt("repair.max-per-run", 100);
        boolean fixSupport = getConfig().getBoolean("repair.restore-support-block", true);
        int fixed = 0;

        for (Map.Entry<UUID, Map<Long, Map<Long, Info>>> we : rails.entrySet()) {
            World world = Bukkit.getWorld(we.getKey());
            if (world == null) continue;
            for (Map.Entry<Long, Map<Long, Info>> ce : we.getValue().entrySet()) {
                int cx = (int) (long) ce.getKey();
                int cz = (int) (ce.getKey() >> 32);
                if (!world.isChunkLoaded(cx, cz)) continue;

                for (Info i : ce.getValue().values()) {
                    if (fixed >= max) return;
                    Block b = world.getBlockAt(i.x, i.y, i.z);
                    if (Tag.RAILS.isTagged(b.getType())) continue;           // joyida
                    if (!(b.isEmpty() || b.isLiquid() || b.isReplaceable())) continue; // boshqa narsa qurilgan

                    if (fixSupport) {
                        Block below = b.getRelative(BlockFace.DOWN);
                        if (below.isEmpty() || below.isLiquid() || below.isReplaceable()) {
                            Material m = Material.matchMaterial(i.support);
                            if (m == null || !m.isSolid() || !m.isBlock()) m = Material.COBBLESTONE;
                            below.setType(m, false);
                        }
                    }

                    BlockData want;
                    try {
                        want = Bukkit.createBlockData(i.data);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    b.setBlockData(want, true);
                    removeDroppedRail(b);
                    fixed++;
                }
            }
        }
        if (fixed > 0) getLogger().info("Tiklangan relslar: " + fixed);
    }

    /** Rels buzilganda tushgan itemni o'chiramiz (dublikat bo'lmasligi uchun). */
    private void removeDroppedRail(Block b) {
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        for (Entity en : b.getWorld().getNearbyEntities(c, 2, 2, 2)) {
            if (en instanceof Item it && Tag.RAILS.isTagged(it.getItemStack().getType())) {
                ItemStack st = it.getItemStack();
                if (st.getAmount() > 1) {
                    st.setAmount(st.getAmount() - 1);
                    it.setItemStack(st);
                } else {
                    it.remove();
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ minecarts

    private void cleanMinecarts(int stepSeconds) {
        int need = getConfig().getInt("minecarts.idle-seconds", 180);
        int playerR = getConfig().getInt("minecarts.player-radius", 16);
        boolean special = getConfig().getBoolean("minecarts.include-special", false);
        boolean drop = getConfig().getBoolean("minecarts.drop-item", false);
        Set<UUID> seen = new HashSet<>();

        for (World w : Bukkit.getWorlds()) {
            for (Minecart m : w.getEntitiesByClass(Minecart.class)) {
                UUID id = m.getUniqueId();
                seen.add(id);

                if (!(m instanceof RideableMinecart) && !special) continue;
                if (m instanceof InventoryHolder h && h.getInventory().getContents() != null) {
                    boolean hasItems = false;
                    for (ItemStack s : h.getInventory().getContents()) if (s != null && !s.getType().isAir()) hasItems = true;
                    if (hasItems) continue;
                }
                if (m.customName() != null) continue;
                if (!m.getPassengers().isEmpty()) { idleSeconds.remove(id); continue; }

                Vector pos = m.getLocation().toVector();
                Vector prev = lastPos.put(id, pos);
                boolean still = prev != null && prev.distanceSquared(pos) < 0.01
                        && m.getVelocity().lengthSquared() < 0.0004;
                if (!still) { idleSeconds.put(id, 0); continue; }

                // yaqinda o'yinchi bo'lsa tegmaymiz
                boolean playerNear = false;
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(m.getLocation()) <= (double) playerR * playerR) {
                        playerNear = true; break;
                    }
                }
                if (playerNear) { idleSeconds.put(id, 0); continue; }

                int t = idleSeconds.merge(id, stepSeconds, Integer::sum);
                if (t >= need) {
                    if (drop) w.dropItemNaturally(m.getLocation(), new ItemStack(Material.MINECART));
                    m.remove();
                    idleSeconds.remove(id);
                    lastPos.remove(id);
                }
            }
        }
        lastPos.keySet().retainAll(seen);
        idleSeconds.keySet().retainAll(seen);
    }

    // ------------------------------------------------------------------ mobs

    private void clearMobs() {
        int r = getConfig().getInt("mob-protection.radius", 8);
        int vr = getConfig().getInt("mob-protection.vertical-radius", 5);
        for (World w : Bukkit.getWorlds()) {
            Map<Long, Map<Long, Info>> wr = rails.get(w.getUID());
            if (wr == null || wr.isEmpty()) continue;
            for (Mob mob : w.getEntitiesByClass(Mob.class)) {
                if (!(mob instanceof Enemy) || mob instanceof Boss) continue;
                if (mob.customName() != null) continue;
                if (nearRail(mob.getLocation(), r, vr)) mob.remove();
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/railguard scan [radius] §7- atrofdagi relslarni ro'yxatga oladi");
            s.sendMessage("§e/railguard forget [radius] §7- atrofdagi relslarni kuzatuvdan chiqaradi");
            s.sendMessage("§e/railguard info §7| §e/railguard reload");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "info" -> s.sendMessage("§aKuzatilayotgan relslar: §f" + countRails());
            case "reload" -> {
                reloadConfig();
                startTasks();
                s.sendMessage("§aConfig qayta yuklandi.");
            }
            case "scan", "forget" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Faqat o'yinchi uchun."); return true; }
                int max = getConfig().getInt("max-scan-radius", 48);
                int r = 32;
                if (a.length > 1) {
                    try { r = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { s.sendMessage("§cRadius son bo'lishi kerak."); return true; }
                }
                r = Math.max(1, Math.min(r, max));
                boolean scan = a[0].equalsIgnoreCase("scan");
                Location l = p.getLocation();
                World w = l.getWorld();
                int n = 0;
                int minY = Math.max(w.getMinHeight(), l.getBlockY() - r);
                int maxY = Math.min(w.getMaxHeight() - 1, l.getBlockY() + r);
                for (int x = l.getBlockX() - r; x <= l.getBlockX() + r; x++) {
                    for (int z = l.getBlockZ() - r; z <= l.getBlockZ() + r; z++) {
                        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                        for (int y = minY; y <= maxY; y++) {
                            Block b = w.getBlockAt(x, y, z);
                            if (scan) {
                                if (Tag.RAILS.isTagged(b.getType())) { track(b); n++; }
                            } else if (isTracked(b)) { untrack(b); n++; }
                        }
                    }
                }
                s.sendMessage((scan ? "§aRo'yxatga olindi: §f" : "§aKuzatuvdan chiqarildi: §f") + n);
            }
            default -> s.sendMessage("§cNoma'lum buyruq. /railguard");
        }
        return true;
    }

    // ------------------------------------------------------------------ storage

    private void saveData() {
        if (!dirty) return;
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<Long, Map<Long, Info>>> we : rails.entrySet())
            for (Map<Long, Info> c : we.getValue().values())
                for (Info i : c.values())
                    out.add(we.getKey() + "|" + i.x + "|" + i.y + "|" + i.z + "|" + i.support + "|" + i.data);
        YamlConfiguration y = new YamlConfiguration();
        y.set("rails", out);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().warning("rails.yml saqlanmadi: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String line : y.getStringList("rails")) {
            String[] p = line.split("\\|", 6);
            if (p.length < 6) continue;
            try {
                UUID w = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]), yy = Integer.parseInt(p[2]), z = Integer.parseInt(p[3]);
                rails.computeIfAbsent(w, k -> new HashMap<>())
                        .computeIfAbsent(chunkKey(x, z), k -> new HashMap<>())
                        .put(Block.getBlockKey(x, yy, z), new Info(x, yy, z, p[5], p[4]));
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
