package ru.altum.shopaltummc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public final class ShopAltumMCPlugin extends JavaPlugin implements Listener, TabExecutor {

    // --- Persistent keys (stored on the chest TileState) ---
    private NamespacedKey KEY_SHOP;
    private NamespacedKey KEY_OWNER_UUID;
    private NamespacedKey KEY_OWNER_NAME;
    private NamespacedKey KEY_PRICE;
    private NamespacedKey KEY_AMOUNT;
    private NamespacedKey KEY_ITEM;
    private NamespacedKey KEY_MSG;
    private NamespacedKey KEY_HOLOS; // comma-separated armorstand UUIDs

    private final Map<String, String> itemNames = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
                saveResource("item.yml", false);
        saveResource("hologramitem.yml", false);
        loadItemTranslations();
ensureDefaultItemsYml();

        KEY_SHOP = new NamespacedKey(this, "shop");
        KEY_OWNER_UUID = new NamespacedKey(this, "owner_uuid");
        KEY_OWNER_NAME = new NamespacedKey(this, "owner_name");
        KEY_PRICE = new NamespacedKey(this, "price");
        KEY_AMOUNT = new NamespacedKey(this, "amount");
        KEY_ITEM = new NamespacedKey(this, "item");
        KEY_MSG = new NamespacedKey(this, "msg");
        KEY_HOLOS = new NamespacedKey(this, "holos");

        loadItemsYml();

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
    }

    // -------------------- COMMANDS --------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(color(prefix() + cfg("messages.only-player")));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(p);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("shopaltum.admin")) {
                p.sendMessage(color(prefix() + cfg("messages.no-permission")));
                return true;
            }
            reloadConfig();
            loadItemsYml();
            p.sendMessage(color(prefix() + cfg("messages.reloaded")));
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            // /shop set <цена> <количество> [сообщение...]
            if (args.length < 3) {
                p.sendMessage(color(prefix() + cfg("messages.usage-set")));
                return true;
            }

            Integer price = parsePositiveInt(args[1]);
            Integer amount = parsePositiveInt(args[2]);
            if (price == null || amount == null) {
                p.sendMessage(color(prefix() + cfg("messages.invalid-number")));
                return true;
            }

            String customMsg = null;
            if (args.length >= 4) {
                customMsg = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                customMsg = color(customMsg);
            }
            if (customMsg == null || customMsg.isBlank()) {
                customMsg = color(cfg("hologram.default-message"));
            }

            Block chest = getTargetChestBlock(p, 6);
            if (chest == null) {
                p.sendMessage(color(prefix() + cfg("messages.look-at-chest")));
                return true;
            }

            Material itemMat = inferShopItemFromChest(chest);
            if (itemMat == null) {
                p.sendMessage(color(prefix() + cfg("messages.no-item-in-chest")));
                return true;
            }

            if (!markShopChest(chest, p, price, amount, itemMat, customMsg)) {
                p.sendMessage(color(prefix() + cfg("messages.cannot-mark-chest")));
                return true;
            }

            // place sign in front of chest
            boolean signOk = placeOrUpdateSign(chest, p, price, amount, itemMat);
            if (!signOk) {
                p.sendMessage(color(prefix() + cfg("messages.no-space-for-sign")));
            }

            // hologram (optional)
            updateHologram(chest, p, itemMat, customMsg);

            p.sendMessage(color(prefix() + cfg("messages.shop-created")));
            return true;
        }

        p.sendMessage(color(prefix() + cfg("messages.unknown-subcommand")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("set", "help", "reload").stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
            if (args.length == 2) return List.of("<цена>");
            if (args.length == 3) return List.of("<количество>");
            return List.of(); // message свободный
        }
        return List.of();
    }

    private void sendHelp(Player p) {
        p.sendMessage(color("&a/shop set &7<цена> <количество> [сообщение...]"));
        p.sendMessage(color("&a/shop help"));
        if (p.hasPermission("shopaltum.admin")) p.sendMessage(color("&a/shop reload"));
    }

    // -------------------- EVENTS / PROTECTION --------------------

    @EventHandler
    public void onChestPlace(BlockPlaceEvent e) {
        // nothing for now (kept for future)
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        if (isShopChest(b)) {
            if (isOwner(b, p) || p.hasPermission("shopaltum.admin")) {
                // allow break, but cleanup hologram/sign markers if desired
                cleanupHologram(b);
                // remove marker
                clearShopMarker(b);
                return;
            }
            e.setCancelled(true);
            p.sendMessage(color(prefix() + cfg("messages.cannot-break")));
            return;
        }

        if (isShopSign(b)) {
            Block chest = findChestForSign(b);
            if (chest != null && isShopChest(chest)) {
                if (isOwner(chest, p) || p.hasPermission("shopaltum.admin")) {
                    cleanupHologram(chest);
                    clearShopMarker(chest);
                    return;
                }
                e.setCancelled(true);
                p.sendMessage(color(prefix() + cfg("messages.cannot-break")));
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Block clicked = e.getClickedBlock();
        Player p = e.getPlayer();

        // prevent opening shop chest unless owner is sneaking
        if (isShopChest(clicked) && e.getAction().isRightClick()) {
            if (isOwner(clicked, p) || p.hasPermission("shopaltum.admin")) {
                if (p.isSneaking()) return; // allow owner open while sneaking
                e.setCancelled(true);
                p.sendMessage(color(prefix() + cfg("messages.owner-open-sneak")));
                return;
            }
            e.setCancelled(true);
            p.sendMessage(color(prefix() + cfg("messages.use-sign")));
            return;
        }

        // purchase by right clicking the sign
        if (isShopSign(clicked) && e.getAction().isRightClick()) {
            e.setCancelled(true);

            Block chest = findChestForSign(clicked);
            if (chest == null || !isShopChest(chest)) {
                p.sendMessage(color(prefix() + cfg("messages.shop-broken")));
                return;
            }

            UUID ownerUuid = getOwnerUuid(chest);
            String ownerName = getOwnerName(chest);

            if (ownerUuid != null && ownerUuid.equals(p.getUniqueId())) {
                p.sendMessage(color(prefix() + cfg("messages.cannot-buy-self")));
                return;
            }

            int price = getInt(chest, KEY_PRICE, 1);
            int amount = getInt(chest, KEY_AMOUNT, 1);
            Material itemMat = getItem(chest);

            if (itemMat == null) {
                p.sendMessage(color(prefix() + cfg("messages.shop-broken")));
                return;
            }

            // check stock
            if (!hasItems(chest, itemMat, amount)) {
                p.sendMessage(color(prefix() + cfg("messages.no-stock")));
                return;
            }

            // check payment (diamonds)
            Material currencyMat = Material.DIAMOND;
            if (!hasItems(p.getInventory(), currencyMat, price)) {
                p.sendMessage(color(prefix() + cfg("messages.not-enough-money")));
                return;
            }

            // transfer
            removeItems(p.getInventory(), currencyMat, price);
            if (ownerUuid != null) {
                Player ownerOnline = Bukkit.getPlayer(ownerUuid);
                if (ownerOnline != null) {
                    ownerOnline.getInventory().addItem(new ItemStack(currencyMat, price));
                }
            }

            takeFromChest(chest, itemMat, amount);
            p.getInventory().addItem(new ItemStack(itemMat, amount));

            // feedback
            String currency = cfg("messages.currency");
            String itemName = getItemName(itemMat);
            p.sendMessage(color(prefix() + cfg("messages.bought")
                    .replace("%item%", itemName)
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%price%", String.valueOf(price))
                    .replace("%currency%", currency)
                    .replace("%owner%", ownerName == null ? "?" : ownerName)
            ));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    // -------------------- SHOP MARKERS --------------------

    private boolean markShopChest(Block chestBlock, Player owner, int price, int amount, Material item, String msg) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.TileState ts)) return false;

        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_OWNER_UUID, PersistentDataType.STRING, owner.getUniqueId().toString());
        pdc.set(KEY_OWNER_NAME, PersistentDataType.STRING, owner.getName());
        pdc.set(KEY_PRICE, PersistentDataType.INTEGER, price);
        pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, amount);
        pdc.set(KEY_ITEM, PersistentDataType.STRING, item.name());
        pdc.set(KEY_MSG, PersistentDataType.STRING, ChatColor.stripColor(msg) == null ? "" : msg); // keep colors too
        ts.update(true);
        return true;
    }

    private void clearShopMarker(Block chestBlock) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.TileState ts)) return;
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        pdc.remove(KEY_SHOP);
        pdc.remove(KEY_OWNER_UUID);
        pdc.remove(KEY_OWNER_NAME);
        pdc.remove(KEY_PRICE);
        pdc.remove(KEY_AMOUNT);
        pdc.remove(KEY_ITEM);
        pdc.remove(KEY_MSG);
        pdc.remove(KEY_HOLOS);
        ts.update(true);
    }

    private boolean isShopChest(Block b) {
        if (b == null) return false;
        if (!(b.getState() instanceof org.bukkit.block.TileState ts)) return false;
        return ts.getPersistentDataContainer().has(KEY_SHOP, PersistentDataType.BYTE);
    }

    private UUID getOwnerUuid(Block chest) {
        String s = getString(chest, KEY_OWNER_UUID);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
    }

    private String getOwnerName(Block chest) {
        String name = getString(chest, KEY_OWNER_NAME);
        if (name != null && !name.isBlank()) return name;
        UUID u = getOwnerUuid(chest);
        if (u == null) return null;
        Player online = Bukkit.getPlayer(u);
        return online != null ? online.getName() : u.toString();
    }

    private boolean isOwner(Block chest, Player p) {
        UUID u = getOwnerUuid(chest);
        return u != null && u.equals(p.getUniqueId());
    }

    private int getInt(Block chest, NamespacedKey key, int def) {
        if (!(chest.getState() instanceof org.bukkit.block.TileState ts)) return def;
        Integer v = ts.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? def : v;
    }

    private String getString(Block chest, NamespacedKey key) {
        if (!(chest.getState() instanceof org.bukkit.block.TileState ts)) return null;
        return ts.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private Material getItem(Block chest) {
        String s = getString(chest, KEY_ITEM);
        if (s == null) return null;
        try { return Material.valueOf(s); } catch (Exception ignored) { return null; }
    }

    // -------------------- SIGN --------------------

    private boolean isShopSign(Block b) {
        if (b == null) return false;
        BlockData bd = b.getBlockData();
        return (bd instanceof WallSign) || (b.getState() instanceof Sign);
    }

    private Block findChestForSign(Block signBlock) {
        BlockData bd = signBlock.getBlockData();
        if (bd instanceof WallSign ws) {
            BlockFace attached = ws.getFacing().getOppositeFace();
            return signBlock.getRelative(attached);
        }
        return null;
    }

    private boolean placeOrUpdateSign(Block chestBlock, Player owner, int price, int amount, Material item) {
        BlockFace facing = getChestFacing(chestBlock);
        if (facing == null) facing = BlockFace.NORTH;

        // place in front of chest (on the latch side)
        Block signPos = chestBlock.getRelative(facing);
        if (!signPos.getType().isAir() && !isShopSign(signPos)) {
            // try other sides as fallback
            for (BlockFace bf : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block tryPos = chestBlock.getRelative(bf);
                if (tryPos.getType().isAir() || isShopSign(tryPos)) {
                    signPos = tryPos;
                    facing = bf;
                    break;
                }
            }
        }
        if (!signPos.getType().isAir() && !isShopSign(signPos)) return false;

        if (signPos.getType().isAir()) {
            signPos.setType(Material.OAK_WALL_SIGN, false);
            BlockData bd = signPos.getBlockData();
            if (bd instanceof WallSign ws) {
                ws.setFacing(facing);
                signPos.setBlockData(ws, false);
            } else if (bd instanceof Directional d) {
                d.setFacing(facing);
                signPos.setBlockData(d, false);
            }
        }

        BlockState st = signPos.getState();
        if (!(st instanceof Sign sign)) return false;

        String currency = cfg("messages.currency");
        String itemName = getItemName(item);

        List<String> lines = getConfig().getStringList("sign.lines");
        if (lines == null || lines.size() < 4) {
            lines = List.of("&a[Магазин]", "&f%owner%", "&c%item%", "&fЦена: &c%amount%&fшт. &c%price% &f%currency%");
        }

        String ownerName = owner.getName();
        for (int i = 0; i < 4; i++) {
            String line = lines.get(i);
            line = line.replace("%owner%", ownerName)
                    .replace("%item%", itemName)
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%price%", String.valueOf(price))
                    .replace("%currency%", currency);
            sign.setLine(i, color(line));
        }
        sign.update(true);
        return true;
    }

    private BlockFace getChestFacing(Block chestBlock) {
        BlockData bd = chestBlock.getBlockData();
        if (bd instanceof Chest chest) {
            return chest.getFacing();
        }
        if (bd instanceof Directional d) return d.getFacing();
        return null;
    }

    // -------------------- HOLOGRAM --------------------

    private void updateHologram(Block chestBlock, Player owner, Material item, String customMsg) {
        if (!getConfig().getBoolean("hologram.enabled", true)) return;
        cleanupHologram(chestBlock);

        String ownerName = owner.getName();
        String itemName = getItemName(item);

        List<String> lines = getConfig().getStringList("hologram.lines");
        if (lines == null || lines.isEmpty()) {
            lines = List.of(
                    "%message%",
                    "&fМагазин &e%item%&f игрока &a%owner%",
                    "&7(Чтобы купить товар нажмите на табличку)"
            );
        }

        double baseY = getConfig().getDouble("hologram.offset-y", 1.3);
        double step = getConfig().getDouble("hologram.line-step", 0.25);

        Location base = chestBlock.getLocation().add(0.5, baseY, 0.5);

        List<UUID> stands = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            raw = raw.replace("%message%", customMsg == null ? "" : customMsg)
                    .replace("%owner%", ownerName)
                    .replace("%item%", itemName);
            String name = color(raw);

            Location loc = base.clone().add(0, (lines.size() - 1 - i) * step, 0);
            ArmorStand as = (ArmorStand) chestBlock.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setCustomName(name);
            as.setCustomNameVisible(true);
            as.setSilent(true);
            as.setInvulnerable(true);
            as.setCollidable(false);
            as.setSmall(true);
            stands.add(as.getUniqueId());
        }

        // store uuids on chest
        BlockState st = chestBlock.getState();
        if (st instanceof org.bukkit.block.TileState ts) {
            String joined = stands.stream().map(UUID::toString).collect(Collectors.joining(","));
            ts.getPersistentDataContainer().set(KEY_HOLOS, PersistentDataType.STRING, joined);
            ts.update(true);
        }
    }

    private void cleanupHologram(Block chestBlock) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.TileState ts)) return;
        String joined = ts.getPersistentDataContainer().get(KEY_HOLOS, PersistentDataType.STRING);
        if (joined == null || joined.isBlank()) return;

        for (String s : joined.split(",")) {
            try {
                UUID u = UUID.fromString(s.trim());
                if (chestBlock.getWorld() == null) continue;
                chestBlock.getWorld().getEntities().stream()
                        .filter(ent -> ent.getUniqueId().equals(u))
                        .findFirst()
                        .ifPresent(ent -> ent.remove());
            } catch (Exception ignored) {}
        }
        ts.getPersistentDataContainer().remove(KEY_HOLOS);
        ts.update(true);
    }

    // -------------------- ITEMS / TRANSLATIONS --------------------

    private void ensureDefaultItemsYml() {
        try {
            File f = new File(getDataFolder(), "items.yml");
            if (f.exists()) return;
            getDataFolder().mkdirs();
            try (InputStream in = getResource("items.yml")) {
                if (in != null) Files.copy(in, f.toPath());
            }
        } catch (Exception e) {
            getLogger().warning("Failed to write default items.yml: " + e.getMessage());
        }
    }

    private void loadItemsYml() {
        itemNames.clear();
        try {
            File f = new File(getDataFolder(), "items.yml");
            if (!f.exists()) return;
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            if (yml.getConfigurationSection("items") == null) return;
            for (String key : yml.getConfigurationSection("items").getKeys(false)) {
                String v = yml.getString("items." + key);
                if (v != null && !v.isBlank()) itemNames.put(key.toUpperCase(Locale.ROOT), v);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load items.yml: " + e.getMessage());
        }
    }

    private String getItemName(Material mat) {
        if (mat == null) return "?";
        String v = itemNames.get(mat.name().toUpperCase(Locale.ROOT));
        if (v != null) return v;
        return prettyMaterial(mat.name());
    }

    private String prettyMaterial(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        // capitalize first letter
        if (s.isEmpty()) return name;
        return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // -------------------- INVENTORY HELPERS --------------------

    private boolean hasItems(org.bukkit.inventory.Inventory inv, Material mat, int amount) {
        int count = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == mat) count += it.getAmount();
            if (count >= amount) return true;
        }
        return false;
    }

    private boolean hasItems(Block chestBlock, Material mat, int amount) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return false;
        return hasItems(chest.getBlockInventory(), mat, amount);
    }

    private void removeItems(org.bukkit.inventory.Inventory inv, Material mat, int amount) {
        int left = amount;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), left);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            left -= take;
            if (left <= 0) return;
        }
    }

    private void takeFromChest(Block chestBlock, Material mat, int amount) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return;
        removeItems(chest.getBlockInventory(), mat, amount);
    }

    private Material inferShopItemFromChest(Block chestBlock) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return null;
        for (ItemStack it : chest.getBlockInventory().getContents()) {
            if (it == null) continue;
            Material m = it.getType();
            if (m == Material.AIR) continue;
            if (m == Material.DIAMOND) continue; // currency should not define item
            return m;
        }
        return null;
    }

    // -------------------- UTILS --------------------

    private Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Block getTargetChestBlock(Player p, int maxDist) {
        Block b = p.getTargetBlockExact(maxDist);
        if (b == null) return null;
        if (b.getType() != Material.CHEST) return null;
        return b;
    }

    private String prefix() {
        return cfg("messages.prefix");
    }

    private String cfg(String path) {
        FileConfiguration c = getConfig();
        String v = c.getString(path);
        return v == null ? "" : v;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private void loadItemTranslations() {
        itemNames.clear();
        hologramItemNames.clear();

        // item.yml (обычные названия)
        try {
            File f = new File(getDataFolder(), "item.yml");
            if (f.exists()) {
                var y = YamlConfiguration.loadConfiguration(f);
                var sec = y.getConfigurationSection("items");
                if (sec != null) {
                    for (String k : sec.getKeys(false)) {
                        try {
                            org.bukkit.Material m = org.bukkit.Material.valueOf(k.toUpperCase());
                            String name = sec.getString(k, null);
                            if (name != null) itemNames.put(m, name);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Не удалось загрузить item.yml: " + ex.getMessage());
        }

        // hologramitem.yml (для голограмм)
        try {
            File f = new File(getDataFolder(), "hologramitem.yml");
            if (f.exists()) {
                var y = YamlConfiguration.loadConfiguration(f);
                var sec = y.getConfigurationSection("items");
                if (sec != null) {
                    for (String k : sec.getKeys(false)) {
                        try {
                            org.bukkit.Material m = org.bukkit.Material.valueOf(k.toUpperCase());
                            String name = sec.getString(k, null);
                            if (name != null) hologramItemNames.put(m, name);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Не удалось загрузить hologramitem.yml: " + ex.getMessage());
        }
    }

    private String trItem(org.bukkit.Material mat) {
        return itemNames.getOrDefault(mat, prettifyEnum(mat));
    }

    private String trHoloItem(org.bukkit.Material mat) {
        return hologramItemNames.getOrDefault(mat, trItem(trHoloItem(mat));
    }

}