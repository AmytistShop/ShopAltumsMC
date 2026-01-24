package ru.altum.shopaltummc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class ShopAltumMCPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey KEY_SHOP;
    private NamespacedKey KEY_OWNER;
    private NamespacedKey KEY_AMOUNT;
    private NamespacedKey KEY_PRICE;
    private NamespacedKey KEY_SIGN_LOC;  // "world;x;y;z"

    private File shopsFile;
    private YamlConfiguration shopsCfg;

    private Material currencyMat;
    private FileConfiguration cfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();

        KEY_SHOP = new NamespacedKey(this, "shop");
        KEY_OWNER = new NamespacedKey(this, "owner");
        KEY_AMOUNT = new NamespacedKey(this, "amount");
        KEY_PRICE = new NamespacedKey(this, "price");
        KEY_SIGN_LOC = new NamespacedKey(this, "sign_loc");

        currencyMat = Material.matchMaterial(cfg.getString("currency.material", "DIAMOND"));
        if (currencyMat == null) currencyMat = Material.DIAMOND;

        shopsFile = new File(getDataFolder(), "shops.yml");
        shopsCfg = new YamlConfiguration();
        loadShopsFile();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        saveShopsFile();
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (args.length == 0) {
            p.sendMessage(msg("messages.usage-set"));
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (args.length != 3) {
                p.sendMessage(msg("messages.usage-set"));
                return true;
            }

            Integer amount = parsePositiveInt(args[1]);
            Integer price = parsePositiveInt(args[2]);
            if (amount == null || price == null) {
                p.sendMessage(msg("messages.usage-set"));
                return true;
            }

            Block chestBlock = getTargetChestBlock(p, 6);
            if (chestBlock == null) {
                p.sendMessage(msg("messages.look-at-chest"));
                return true;
            }

            Chest chest = (Chest) chestBlock.getState();

            // only single chest
            if (isDoubleChest(chest)) {
                p.sendMessage(msg("messages.only-single-chest"));
                return true;
            }

            if (isShopChest(chest)) {
                p.sendMessage(msg("messages.already-shop"));
                return true;
            }

            ItemStack inHand = p.getInventory().getItemInMainHand();
            if (inHand == null || inHand.getType() == Material.AIR) {
                p.sendMessage(msg("messages.hold-item"));
                return true;
            }

            // Determine "front" block
            Block signBlock = getFrontBlock(chestBlock);
            if (signBlock == null || !signBlock.getType().isAir()) {
                p.sendMessage(msg("messages.no-space-front"));
                return true;
            }

            // Place sign programmatically
            placeShopSign(chestBlock, signBlock);

            // Save shop data to chest PDC and to shops.yml
            ShopData data = new ShopData(
                    p.getUniqueId(),
                    amount,
                    price,
                    normalizeTemplate(inHand, amount),
                    signBlock.getLocation()
            );

            markChestAsShop(chest, data);
            markSignAsShop((Sign) signBlock.getState(), data, chestBlock.getLocation());

            saveShopToFile(chestBlock.getLocation(), data);

            p.sendMessage(msg("messages.created"));
            return true;
        }

        if (args[0].equalsIgnoreCase("remove")) {
            Block chestBlock = getTargetChestBlock(p, 6);
            if (chestBlock == null) {
                // maybe looking at sign
                Block maybe = p.getTargetBlockExact(6);
                if (maybe != null && (maybe.getState() instanceof Sign sign)) {
                    Location chestLoc = getChestLocFromSign(sign);
                    if (chestLoc != null) {
                        chestBlock = chestLoc.getBlock();
                    }
                }
            }
            if (chestBlock == null || !(chestBlock.getState() instanceof Chest chest)) {
                p.sendMessage(msg("messages.look-at-chest"));
                return true;
            }

            if (!isShopChest(chest)) {
                p.sendMessage(msg("messages.look-at-chest"));
                return true;
            }

            UUID owner = getOwner(chest);
            if (!isOwnerOrAdmin(p, owner)) {
                p.sendMessage(msg("messages.no-permission-break"));
                return true;
            }

            removeShop(chestBlock.getLocation(), true);
            p.sendMessage(msg("messages.removed"));
            return true;
        }

        p.sendMessage(msg("messages.usage-set"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("set", "remove").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ---------------- Events ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignClick(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        BlockState st = b.getState();
        if (!(st instanceof Sign sign)) return;

        if (!isShopSign(sign)) return;

        // Always cancel to prevent sign editor GUI (even for OP/owner)
        e.setCancelled(true);

        Location chestLoc = getChestLocFromSign(sign);
        if (chestLoc == null) return;

        Block chestBlock = chestLoc.getBlock();
        if (!(chestBlock.getState() instanceof Chest chest)) {
            // chest missing -> cleanup sign and file
            cleanupDanglingSign(b.getLocation());
            b.setType(Material.AIR);
            return;
        }

        // load data (prefer chest PDC)
        ShopData data = getShopDataFromChest(chest);
        if (data == null) {
            // fallback from file
            data = loadShopFromFile(chestLoc);
            if (data == null) return;
            markChestAsShop(chest, data);
        }

        Player buyer = e.getPlayer();
        // prevent owner "editing" by accident - still allow buying
        tryBuy(buyer, chest, data, sign);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Chest chest)) return;

        if (!isShopChest(chest)) return;

        UUID owner = getOwner(chest);
        if (isOwnerOrAdmin(p, owner)) return; // owner/admin can open
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        // protect shop chest
        if (b.getState() instanceof Chest chest && isShopChest(chest)) {
            UUID owner = getOwner(chest);
            if (!isOwnerOrAdmin(p, owner)) {
                e.setCancelled(true);
                p.sendMessage(msg("messages.no-permission-break"));
                return;
            }
            // owner breaks chest => remove shop and allow break
            removeShop(b.getLocation(), false);
            return;
        }

        // protect shop sign
        if (b.getState() instanceof Sign sign && isShopSign(sign)) {
            Location chestLoc = getChestLocFromSign(sign);
            UUID owner = (chestLoc != null) ? getOwnerFromFile(chestLoc) : null;

            if (chestLoc != null && chestLoc.getBlock().getState() instanceof Chest chest && isShopChest(chest)) {
                UUID chestOwner = getOwner(chest);
                if (chestOwner != null) owner = chestOwner;
            }

            if (!isOwnerOrAdmin(p, owner)) {
                e.setCancelled(true);
                p.sendMessage(msg("messages.no-permission-break"));
                return;
            }

            // owner/admin breaks sign => remove shop (including chest tag) but allow sign break
            if (chestLoc != null) {
                removeShop(chestLoc, true);
            } else {
                cleanupDanglingSign(b.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtectedShopBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtectedShopBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        // If piston tries to move a shop chest/sign or pushes into it - cancel
        for (Block b : e.getBlocks()) {
            if (isProtectedShopBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
        Block movedInto = e.getBlock().getRelative(e.getDirection(), e.getLength() + 1);
        if (isProtectedShopBlock(movedInto)) {
            e.setCancelled(true);
        }
    }

    // ---------------- Core logic ----------------

    private void tryBuy(Player buyer, Chest chest, ShopData data, Sign sign) {
        Inventory inv = chest.getBlockInventory();

        if (!hasEnoughSimilar(inv, data.itemTemplate, data.amount)) {
            buyer.sendMessage(msg("messages.not-enough-items"));
            return;
        }

        if (!hasEnoughCurrency(buyer, data.price)) {
            buyer.sendMessage(msg("messages.not-enough-money"));
            return;
        }

        // 1) take money
        removeCurrency(buyer, data.price);

        // 2) remove items from chest
        removeSimilar(inv, data.itemTemplate, data.amount);

        // 3) give items to buyer
        ItemStack give = data.itemTemplate.clone();
        give.setAmount(data.amount);
        Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(give);
        if (!leftovers.isEmpty()) {
            // drop leftovers at buyer feet
            leftovers.values().forEach(it -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), it));
        }

        // 4) put money into chest
        ItemStack money = new ItemStack(currencyMat, data.price);
        Map<Integer, ItemStack> moneyLeft = inv.addItem(money);
        if (!moneyLeft.isEmpty()) {
            moneyLeft.values().forEach(it -> chest.getWorld().dropItemNaturally(chest.getLocation().add(0.5, 1.0, 0.5), it));
            buyer.sendMessage(msg("messages.chest-full-money-drop"));
        }

        buyer.sendMessage(msg("messages.bought"));

        // refresh sign text (ensures Bedrock doesn't corrupt last line)
        renderSign(sign, data);
        sign.update(true, false);
    }

    private Block getTargetChestBlock(Player p, int maxDistance) {
        Block target = p.getTargetBlockExact(maxDistance);
        if (target == null) return null;
        if (!(target.getState() instanceof Chest)) return null;
        return target;
    }

    private boolean isDoubleChest(Chest chest) {
        // On Paper, double chest inventory holder is DoubleChest; easiest check via inventory size
        return chest.getInventory().getSize() > 27;
    }

    private Block getFrontBlock(Block chestBlock) {
        if (!(chestBlock.getBlockData() instanceof Directional dir)) return null;
        return chestBlock.getRelative(dir.getFacing());
    }

    private void placeShopSign(Block chestBlock, Block signBlock) {
        signBlock.setType(Material.OAK_WALL_SIGN, false);
        BlockState st = signBlock.getState();
        if (st instanceof Sign sign) {
            WallSign ws = (WallSign) signBlock.getBlockData();
            if (chestBlock.getBlockData() instanceof Directional dir) {
                ws.setFacing(dir.getFacing());
            }
            signBlock.setBlockData(ws, false);
            sign.update(true, false);
        }
    }

    private ItemStack normalizeTemplate(ItemStack inHand, int amount) {
        ItemStack t = inHand.clone();
        t.setAmount(1); // template amount always 1
        return t;
    }

    private void markChestAsShop(Chest chest, ShopData data) {
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_OWNER, PersistentDataType.STRING, data.owner.toString());
        pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, data.amount);
        pdc.set(KEY_PRICE, PersistentDataType.INTEGER, data.price);
        pdc.set(KEY_SIGN_LOC, PersistentDataType.STRING, locToString(data.signLoc));
        chest.update(true, false);
    }

    private void markSignAsShop(Sign sign, ShopData data, Location chestLoc) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_SIGN_LOC, PersistentDataType.STRING, locToString(chestLoc)); // store chest loc here
        renderSign(sign, data);
        sign.update(true, false);
    }

    private void renderSign(Sign sign, ShopData data) {
        String itemName = humanItemName(data.itemTemplate);
        String l1 = cfg.getString("sign.line1", "&f[Магазин]");
        String l2 = cfg.getString("sign.line2", "&7%owner%");
        String l3 = cfg.getString("sign.line3", "&e%item%");
        String l4 = cfg.getString("sign.line4", "&fЦена: &c%amount% &fшт. &c%price% &fалм");

        l2 = l2.replace("%owner%", safeName(data.owner));
        l3 = l3.replace("%item%", itemName);
        l4 = l4.replace("%amount%", String.valueOf(data.amount))
               .replace("%price%", String.valueOf(data.price));

        sign.setLine(0, color(l1));
        sign.setLine(1, color(l2));
        sign.setLine(2, color(l3));
        sign.setLine(3, color(l4));
    }

    private String safeName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        // try cache from file
        return shopsCfg.getString("names." + uuid, uuid.toString().substring(0, 8));
    }

    private String humanItemName(ItemStack stack) {
        // Prefer display name if exists
        if (stack.hasItemMeta() && stack.getItemMeta() != null) {
            String dn = stack.getItemMeta().getDisplayName();
            if (dn != null && !dn.isBlank()) return ChatColor.stripColor(dn);
        }
        // Fallback: MATERIAL_NAME
        return stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean isProtectedShopBlock(Block b) {
        if (b.getState() instanceof Chest chest) return isShopChest(chest);
        if (b.getState() instanceof Sign sign) return isShopSign(sign);
        return false;
    }

    private boolean isShopChest(Chest chest) {
        return chest.getPersistentDataContainer().has(KEY_SHOP, PersistentDataType.BYTE);
    }

    private boolean isShopSign(Sign sign) {
        return sign.getPersistentDataContainer().has(KEY_SHOP, PersistentDataType.BYTE);
    }

    private UUID getOwner(Chest chest) {
        String s = chest.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean isOwnerOrAdmin(Player p, UUID owner) {
        if (p.hasPermission("shop.admin")) return true;
        if (owner == null) return false;
        return owner.equals(p.getUniqueId());
    }

    private Location getChestLocFromSign(Sign sign) {
        String s = sign.getPersistentDataContainer().get(KEY_SIGN_LOC, PersistentDataType.STRING);
        if (s == null) return null;
        return stringToLoc(s);
    }

    private void removeShop(Location chestLoc, boolean removeSign) {
        // remove tags from chest if exists
        Block b = chestLoc.getBlock();
        if (b.getState() instanceof Chest chest) {
            PersistentDataContainer pdc = chest.getPersistentDataContainer();
            String signLocStr = pdc.get(KEY_SIGN_LOC, PersistentDataType.STRING);
            pdc.remove(KEY_SHOP);
            pdc.remove(KEY_OWNER);
            pdc.remove(KEY_AMOUNT);
            pdc.remove(KEY_PRICE);
            pdc.remove(KEY_SIGN_LOC);
            chest.update(true, false);

            if (removeSign && signLocStr != null) {
                Location sl = stringToLoc(signLocStr);
                if (sl != null && sl.getBlock().getState() instanceof Sign sign) {
                    cleanupSignPdc(sign);
                    sl.getBlock().setType(Material.AIR, false);
                }
            }
        }

        // remove from file
        shopsCfg.set("shops." + locKey(chestLoc), null);
        saveShopsFile();
    }

    private void cleanupDanglingSign(Location signLoc) {
        // Remove any file entry referencing this sign
        // Best effort: no heavy scan, just clear nothing.
        // Also clear sign pdc if block still sign
        Block b = signLoc.getBlock();
        if (b.getState() instanceof Sign sign) {
            cleanupSignPdc(sign);
            sign.update(true, false);
        }
    }

    private void cleanupSignPdc(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.remove(KEY_SHOP);
        pdc.remove(KEY_SIGN_LOC);
    }

    // ---------------- Currency / items helpers ----------------

    private boolean hasEnoughCurrency(Player p, int price) {
        return countMaterial(p.getInventory(), currencyMat) >= price;
    }

    private void removeCurrency(Player p, int price) {
        removeMaterial(p.getInventory(), currencyMat, price);
    }

    private int countMaterial(Inventory inv, Material mat) {
        int c = 0;
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType() != mat) continue;
            c += it.getAmount();
        }
        return c;
    }

    private void removeMaterial(Inventory inv, Material mat, int amount) {
        int left = amount;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            left -= take;
            if (left <= 0) break;
        }
    }

    private boolean hasEnoughSimilar(Inventory inv, ItemStack template, int amount) {
        int c = 0;
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            if (it.isSimilar(template)) c += it.getAmount();
        }
        return c >= amount;
    }

    private void removeSimilar(Inventory inv, ItemStack template, int amount) {
        int left = amount;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (!it.isSimilar(template)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            left -= take;
            if (left <= 0) break;
        }
    }

    // ---------------- Shops file persistence ----------------

    private void loadShopsFile() {
        if (!shopsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                shopsFile.createNewFile();
            } catch (IOException ignored) {}
        }
        try {
            shopsCfg.load(shopsFile);
        } catch (IOException | InvalidConfigurationException ignored) {}

        // Cache names section
        if (!shopsCfg.isConfigurationSection("names")) {
            shopsCfg.createSection("names");
        }
    }

    private void saveShopsFile() {
        try {
            shopsCfg.save(shopsFile);
        } catch (IOException ignored) {}
    }

    private void saveShopToFile(Location chestLoc, ShopData data) {
        String key = "shops." + locKey(chestLoc);
        shopsCfg.set(key + ".owner", data.owner.toString());
        shopsCfg.set("names." + data.owner, safeName(data.owner));
        shopsCfg.set(key + ".amount", data.amount);
        shopsCfg.set(key + ".price", data.price);
        shopsCfg.set(key + ".item", data.itemTemplate.serialize());
        shopsCfg.set(key + ".sign", locToString(data.signLoc));
        saveShopsFile();
    }

    private ShopData loadShopFromFile(Location chestLoc) {
        String key = "shops." + locKey(chestLoc);
        if (!shopsCfg.contains(key)) return null;
        try {
            UUID owner = UUID.fromString(shopsCfg.getString(key + ".owner"));
            int amount = shopsCfg.getInt(key + ".amount");
            int price = shopsCfg.getInt(key + ".price");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) shopsCfg.get(key + ".item");
            ItemStack item = ItemStack.deserialize(map);
            Location signLoc = stringToLoc(shopsCfg.getString(key + ".sign"));
            return new ShopData(owner, amount, price, item, signLoc);
        } catch (Exception ex) {
            return null;
        }
    }

    private UUID getOwnerFromFile(Location chestLoc) {
        String key = "shops." + locKey(chestLoc) + ".owner";
        String s = shopsCfg.getString(key);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) { return null; }
    }

    // ---------------- Serialization helpers ----------------


    private ShopData getShopDataFromChest(Chest chest) {
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        if (!pdc.has(KEY_SHOP, PersistentDataType.BYTE)) return null;

        try {
            UUID owner = UUID.fromString(pdc.get(KEY_OWNER, PersistentDataType.STRING));
            int amount = pdc.getOrDefault(KEY_AMOUNT, PersistentDataType.INTEGER, 1);
            int price = pdc.getOrDefault(KEY_PRICE, PersistentDataType.INTEGER, 1);

            // item stored in file, not in PDC to keep simple/reliable
            Location chestLoc = chest.getLocation();
            ShopData fileData = loadShopFromFile(chestLoc);
            if (fileData == null) return new ShopData(owner, amount, price, new ItemStack(Material.STONE), chestLoc);

            return new ShopData(owner, amount, price, fileData.itemTemplate, fileData.signLoc);
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------------- Misc helpers ----------------

    private String msg(String path) {
        String prefix = cfg.getString("messages.prefix", "&a[Магазин]&r ");
        String body = cfg.getString(path, "");
        return color(prefix + body);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s);
            if (v <= 0) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String locKey(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    private String locToString(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private Location stringToLoc(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            int z = Integer.parseInt(p[3]);
            return new Location(w, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class ShopData {
        final UUID owner;
        final int amount;
        final int price;
        final ItemStack itemTemplate;
        final Location signLoc;

        ShopData(UUID owner, int amount, int price, ItemStack itemTemplate, Location signLoc) {
            this.owner = owner;
            this.amount = amount;
            this.price = price;
            this.itemTemplate = itemTemplate;
            this.signLoc = signLoc;
        }
    }
}
