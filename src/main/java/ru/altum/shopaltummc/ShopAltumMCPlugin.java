package ru.altum.shopaltummc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShopAltumMCPlugin extends JavaPlugin implements Listener, TabExecutor {

    // PDC keys
    private NamespacedKey KEY_SHOP;
    private NamespacedKey KEY_OWNER;
    private NamespacedKey KEY_ITEM;
    private NamespacedKey KEY_AMOUNT;
    private NamespacedKey KEY_PRICE;

    private File shopsFile;
    private YamlConfiguration shops;

    private Material currencyMaterial;
    private Material signMaterial;

    private final Map<String, Object> shopLocks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        KEY_SHOP = new NamespacedKey(this, "shop");
        KEY_OWNER = new NamespacedKey(this, "owner");
        KEY_ITEM = new NamespacedKey(this, "item");
        KEY_AMOUNT = new NamespacedKey(this, "amount");
        KEY_PRICE = new NamespacedKey(this, "price");

        shopsFile = new File(getDataFolder(), "shops.yml");
        if (!shopsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                shopsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Cannot create shops.yml: " + e.getMessage());
            }
        }
        shops = YamlConfiguration.loadConfiguration(shopsFile);

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("shop"), "command /shop missing in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("shop"), "command /shop missing in plugin.yml").setTabCompleter(this);

        // Optional Paper event to prevent opening sign editor
        registerPaperSignOpenListener();

        getLogger().info("ShopAltumMC enabled.");
    }

    private void reloadLocalConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        currencyMaterial = parseMaterial(cfg.getString("currency-material"), Material.DIAMOND);
        signMaterial = parseMaterial(cfg.getString("sign-material"), Material.OAK_WALL_SIGN);
    }

    private Material parseMaterial(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    @Override
    public void onDisable() {
        saveShops();
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(msg("must-be-player")));
            return true;
        }

        if (args.length == 0) {
            for (String line : getConfig().getStringList("help-message")) {
                player.sendMessage(color(line));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!player.hasPermission("shopaltummc.admin")) {
                    player.sendMessage(color(msg("no-permission")));
                    return true;
                }
                reloadLocalConfig();
                player.sendMessage(color("&aConfig reloaded."));
                return true;
            }
            case "remove" -> {
                handleRemove(player);
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    player.sendMessage(color(msg("invalid-number")));
                    return true;
                }
                int amount;
                int price;
                try {
                    amount = Integer.parseInt(args[1]);
                    price = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage(color(msg("invalid-number")));
                    return true;
                }
                if (amount <= 0 || price < 0) {
                    player.sendMessage(color(msg("invalid-number")));
                    return true;
                }

                Block target = player.getTargetBlockExact(6);
                if (target == null || !(target.getState() instanceof Chest)) {
                    player.sendMessage(color(msg("look-at-chest")));
                    return true;
                }

                ItemStack inHand = player.getInventory().getItemInMainHand();
                if (inHand.getType() == Material.AIR) {
                    player.sendMessage(color(msg("hold-item")));
                    return true;
                }

                createShop(player, target, inHand.getType(), amount, price);
                return true;
            }
            default -> {
                for (String line : getConfig().getStringList("help-message")) {
                    player.sendMessage(color(line));
                }
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("set", "remove", "reload");
        }
        return Collections.emptyList();
    }

    private void handleRemove(Player player) {
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            player.sendMessage(color(msg("shop-not-found")));
            return;
        }

        ShopData shop = findShopByAnyBlock(target);
        if (shop == null) {
            player.sendMessage(color(msg("shop-not-found")));
            return;
        }

        if (!canManage(player, shop.owner)) {
            player.sendMessage(color(msg("not-owner")));
            return;
        }

        removeShop(shop);
        player.sendMessage(color(msg("shop-removed")));
    }

    // ---------------- Events ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        // Block opening shop chest for non-owner
        ShopData shopClicked = findShopByAnyBlock(clicked);
        if (shopClicked != null) {
            Player p = e.getPlayer();

            // If click is on a sign, treat as purchase
            if (isSign(clicked)) {
                e.setCancelled(true);
                handlePurchase(p, shopClicked);
                return;
            }

            // If click is on the chest, block opening unless owner/admin
            if (isChest(clicked)) {
                if (!canManage(p, shopClicked.owner)) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        ShopData shop = findShopByAnyBlock(block);
        if (shop == null) return;

        Player p = e.getPlayer();
        if (!canManage(p, shop.owner)) {
            e.setCancelled(true);
            p.sendMessage(color(msg("not-owner")));
            return;
        }

        // Owner/admin breaking either chest or sign removes the shop fully
        removeShop(shop);
        p.sendMessage(color(msg("shop-removed")));
    }

    // Paper-only: prevent sign editor opening on shop sign
    @SuppressWarnings("unchecked")
    private void registerPaperSignOpenListener() {
        try {
            Class<?> eventClass = Class.forName("io.papermc.paper.event.player.PlayerOpenSignEvent");
            Bukkit.getPluginManager().registerEvent(
                    (Class<? extends org.bukkit.event.Event>) eventClass,
                    this,
                    EventPriority.HIGHEST,
                    (listener, event) -> {
                        try {
                            Object block = eventClass.getMethod("getSign").invoke(event);
                            if (block instanceof Sign sign) {
                                ShopData shop = findShopByAnyBlock(sign.getBlock());
                                if (shop != null) {
                                    eventClass.getMethod("setCancelled", boolean.class).invoke(event, true);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    },
                    this,
                    true
            );
        } catch (ClassNotFoundException ignored) {
            // Not Paper or older API - fine
        }
    }

    // ---------------- Core logic ----------------

    private void createShop(Player owner, Block chestBlock, Material item, int amount, int price) {
        String id = locKey(chestBlock);

        // Already has shop?
        if (shops.contains(id)) {
            // Remove old shop safely
            ShopData old = loadShop(id);
            if (old != null) removeShop(old);
        }

        BlockFace face = guessFrontFace(owner, chestBlock);
        SignPlacement placement = placeWallSign(chestBlock, face);
        if (placement == null) {
            owner.sendMessage(color("&cНе удалось поставить табличку (нет места рядом)."));
            return;
        }

        UUID ownerId = owner.getUniqueId();
        ShopData shop = new ShopData(id, ownerId, item, amount, price, chestBlock.getWorld().getName(),
                chestBlock.getX(), chestBlock.getY(), chestBlock.getZ(),
                placement.sign.getX(), placement.sign.getY(), placement.sign.getZ());

        // Mark PDC on chest and sign
        tagChestAndSign(shop);

        // Write sign text
        updateShopSign(shop);

        // Save
        saveShop(shop);
        owner.sendMessage(color(msg("shop-created")));
    }

    private void handlePurchase(Player buyer, ShopData shop) {
        Object lock = shopLocks.computeIfAbsent(shop.id, k -> new Object());
        synchronized (lock) {
            Chest chest = shop.getChest();
            if (chest == null) {
                buyer.sendMessage(color(msg("shop-not-found")));
                return;
            }

            Inventory inv = chest.getBlockInventory();
            int available = countMaterial(inv, shop.item);
            if (available < shop.amount) {
                buyer.sendMessage(color(msg("not-enough-stock")));
                return;
            }

            int money = countMaterial(buyer.getInventory(), currencyMaterial);
            if (money < shop.price) {
                buyer.sendMessage(color(apply(msg("not-enough-money"), Map.of(
                        "%price%", String.valueOf(shop.price)
                ))));
                return;
            }

            // Remove currency
            removeMaterial(buyer.getInventory(), currencyMaterial, shop.price);

            // Remove item from chest
            removeMaterial(inv, shop.item, shop.amount);

            // Give to buyer
            ItemStack stack = new ItemStack(shop.item, shop.amount);
            HashMap<Integer, ItemStack> leftovers = buyer.getInventory().addItem(stack);
            for (ItemStack left : leftovers.values()) {
                buyer.getWorld().dropItemNaturally(buyer.getLocation(), left);
            }

            buyer.sendMessage(color(apply(msg("purchased"), Map.of(
                    "%amount%", String.valueOf(shop.amount),
                    "%item%", shop.item.name(),
                    "%price%", String.valueOf(shop.price)
            ))));
        }
    }

    private boolean canManage(Player p, UUID owner) {
        return p.hasPermission("shopaltummc.admin") || p.getUniqueId().equals(owner);
    }

    private void removeShop(ShopData shop) {
        // Remove PDC from blocks if possible
        Chest chest = shop.getChest();
        if (chest != null) {
            clearPdc(chest.getBlock());
        }

        Sign sign = shop.getSign();
        if (sign != null) {
            clearPdc(sign.getBlock());
            // Optionally remove sign block
            sign.getBlock().setType(Material.AIR);
        }

        shops.set(shop.id, null);
        saveShops();
    }

    private void saveShop(ShopData shop) {
        shops.set(shop.id + ".owner", shop.owner.toString());
        shops.set(shop.id + ".item", shop.item.name());
        shops.set(shop.id + ".amount", shop.amount);
        shops.set(shop.id + ".price", shop.price);
        shops.set(shop.id + ".world", shop.world);
        shops.set(shop.id + ".chest.x", shop.chestX);
        shops.set(shop.id + ".chest.y", shop.chestY);
        shops.set(shop.id + ".chest.z", shop.chestZ);
        shops.set(shop.id + ".sign.x", shop.signX);
        shops.set(shop.id + ".sign.y", shop.signY);
        shops.set(shop.id + ".sign.z", shop.signZ);
        saveShops();
    }

    private ShopData loadShop(String id) {
        try {
            String owner = shops.getString(id + ".owner");
            String item = shops.getString(id + ".item");
            int amount = shops.getInt(id + ".amount");
            int price = shops.getInt(id + ".price");
            String world = shops.getString(id + ".world");
            int cx = shops.getInt(id + ".chest.x");
            int cy = shops.getInt(id + ".chest.y");
            int cz = shops.getInt(id + ".chest.z");
            int sx = shops.getInt(id + ".sign.x");
            int sy = shops.getInt(id + ".sign.y");
            int sz = shops.getInt(id + ".sign.z");

            if (owner == null || item == null || world == null) return null;

            Material mat = Material.matchMaterial(item);
            if (mat == null) return null;

            return new ShopData(id, UUID.fromString(owner), mat, amount, price, world, cx, cy, cz, sx, sy, sz);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveShops() {
        try {
            shops.save(shopsFile);
        } catch (IOException e) {
            getLogger().severe("Cannot save shops.yml: " + e.getMessage());
        }
    }

    private void tagChestAndSign(ShopData shop) {
        Chest chest = shop.getChest();
        if (chest != null) {
            PersistentDataContainer pdc = chest.getPersistentDataContainer();
            pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_OWNER, PersistentDataType.STRING, shop.owner.toString());
            pdc.set(KEY_ITEM, PersistentDataType.STRING, shop.item.name());
            pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, shop.amount);
            pdc.set(KEY_PRICE, PersistentDataType.INTEGER, shop.price);
            chest.update(true, false);
        }

        Sign sign = shop.getSign();
        if (sign != null) {
            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_OWNER, PersistentDataType.STRING, shop.owner.toString());
            pdc.set(KEY_ITEM, PersistentDataType.STRING, shop.item.name());
            pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, shop.amount);
            pdc.set(KEY_PRICE, PersistentDataType.INTEGER, shop.price);
            sign.update(true, false);
        }
    }

    private void clearPdc(Block block) {
        BlockState st = block.getState();
        if (st instanceof Chest chest) {
            PersistentDataContainer pdc = chest.getPersistentDataContainer();
            pdc.remove(KEY_SHOP);
            pdc.remove(KEY_OWNER);
            pdc.remove(KEY_ITEM);
            pdc.remove(KEY_AMOUNT);
            pdc.remove(KEY_PRICE);
            chest.update(true, false);
        } else if (st instanceof Sign sign) {
            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            pdc.remove(KEY_SHOP);
            pdc.remove(KEY_OWNER);
            pdc.remove(KEY_ITEM);
            pdc.remove(KEY_AMOUNT);
            pdc.remove(KEY_PRICE);
            sign.update(true, false);
        }
    }

    private void updateShopSign(ShopData shop) {
        Sign sign = shop.getSign();
        if (sign == null) return;

        String ownerName = resolveName(shop.owner);
        String itemName = prettyMaterial(shop.item);

        String l1 = getConfig().getString("sign.line1", "&f[Магазин]");
        String l2 = getConfig().getString("sign.line2", "&f%owner%");
        String l3 = getConfig().getString("sign.line3", "&c%item%");
        String l4 = getConfig().getString("sign.line4", "&fЦена: &c%amount%&fшт. &c%price% &fалм");

        Map<String, String> vars = new HashMap<>();
        vars.put("%owner%", ownerName);
        vars.put("%item%", itemName);
        vars.put("%amount%", String.valueOf(shop.amount));
        vars.put("%price%", String.valueOf(shop.price));

        boolean bedrockFriendly = isBedrockOwner(shop.owner); // if owner is bedrock, still keep simple. We'll also sanitize for bedrock viewer at purchase time.

        sign.setLine(0, color(sanitizeForBedrock(apply(l1, vars), bedrockFriendly)));
        sign.setLine(1, color(sanitizeForBedrock(apply(l2, vars), bedrockFriendly)));
        sign.setLine(2, color(sanitizeForBedrock(apply(l3, vars), bedrockFriendly)));
        sign.setLine(3, color(sanitizeForBedrock(apply(l4, vars), bedrockFriendly)));
        sign.update(true, false);
    }

    private String sanitizeForBedrock(String s, boolean bedrock) {
        if (!bedrock) return s;
        // Strip common gradient/hex formats
        // 1) &#RRGGBB
        s = s.replaceAll("&?#([A-Fa-f0-9]{6})", "");
        // 2) <#RRGGBB>
        s = s.replaceAll("<#[A-Fa-f0-9]{6}>", "");
        // 3) §x§R§R§G§G§B§B
        s = s.replaceAll("§x(§[0-9A-Fa-f]){6}", "");
        return s;
    }

    private String resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        try {
            return Bukkit.getOfflinePlayer(uuid).getName() != null ? Bukkit.getOfflinePlayer(uuid).getName() : uuid.toString().substring(0, 8);
        } catch (Throwable t) {
            return uuid.toString().substring(0, 8);
        }
    }

    private String prettyMaterial(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        // simple capitalize
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private ShopData findShopByAnyBlock(Block block) {
        // Fast path: check PDC on this block
        ShopData byPdc = readShopFromPdc(block);
        if (byPdc != null) return byPdc;

        // Otherwise scan shops by coords (cheap for small number)
        // Build possible key if chest
        if (isChest(block)) {
            String id = locKey(block);
            if (shops.contains(id)) return loadShop(id);
        }

        // If sign, search by sign coords
        if (isSign(block)) {
            String world = block.getWorld().getName();
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            for (String key : shops.getKeys(false)) {
                if (key.contains(".")) continue;
                String w = shops.getString(key + ".world");
                if (w == null || !w.equals(world)) continue;
                if (shops.getInt(key + ".sign.x") == x && shops.getInt(key + ".sign.y") == y && shops.getInt(key + ".sign.z") == z) {
                    return loadShop(key);
                }
            }
        }

        return null;
    }

    private ShopData readShopFromPdc(Block block) {
        BlockState st = block.getState();
        PersistentDataContainer pdc;
        if (st instanceof Chest chest) {
            pdc = chest.getPersistentDataContainer();
        } else if (st instanceof Sign sign) {
            pdc = sign.getPersistentDataContainer();
        } else {
            return null;
        }

        Byte marker = pdc.get(KEY_SHOP, PersistentDataType.BYTE);
        if (marker == null || marker == 0) return null;

        String owner = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        String item = pdc.get(KEY_ITEM, PersistentDataType.STRING);
        Integer amount = pdc.get(KEY_AMOUNT, PersistentDataType.INTEGER);
        Integer price = pdc.get(KEY_PRICE, PersistentDataType.INTEGER);
        if (owner == null || item == null || amount == null || price == null) return null;

        Material mat = Material.matchMaterial(item);
        if (mat == null) return null;

        // Build from storage to include sign/chest coords
        // Prefer shops.yml if present
        if (isChest(block)) {
            String id = locKey(block);
            if (shops.contains(id)) {
                ShopData fromFile = loadShop(id);
                if (fromFile != null) return fromFile;
            }
            // fallback minimal
            return new ShopData(locKey(block), UUID.fromString(owner), mat, amount, price, block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    block.getX(), block.getY(), block.getZ());
        } else {
            // sign
            // Find matching by coords in file
            return findShopByAnyBlock(block); // will scan file
        }
    }

    private boolean isChest(Block block) {
        return block.getState() instanceof Chest;
    }

    private boolean isSign(Block block) {
        return block.getState() instanceof Sign;
    }

    private String locKey(Block chestBlock) {
        return chestBlock.getWorld().getName() + "," + chestBlock.getX() + "," + chestBlock.getY() + "," + chestBlock.getZ();
    }

    private int countMaterial(Inventory inv, Material m) {
        int count = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == m) count += it.getAmount();
        }
        return count;
    }

    private void removeMaterial(Inventory inv, Material m, int amount) {
        int remaining = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(it.getAmount(), remaining);
            it.setAmount(it.getAmount() - take);
            remaining -= take;
            if (it.getAmount() <= 0) contents[i] = null;
            if (remaining <= 0) break;
        }
        inv.setContents(contents);
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private String apply(String s, Map<String, String> vars) {
        String out = s;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // Guess front: use player facing, place sign on face player is looking at
    private BlockFace guessFrontFace(Player player, Block chestBlock) {
        BlockFace facing = yawToFace(player.getLocation().getYaw());
        // If that side is blocked, try opposite etc in placement method.
        return facing;
    }

    private BlockFace yawToFace(float yaw) {
        float rot = (yaw - 90) % 360;
        if (rot < 0) rot += 360.0F;
        if (0 <= rot && rot < 45) return BlockFace.NORTH;
        if (45 <= rot && rot < 135) return BlockFace.EAST;
        if (135 <= rot && rot < 225) return BlockFace.SOUTH;
        if (225 <= rot && rot < 315) return BlockFace.WEST;
        return BlockFace.NORTH;
    }

    private SignPlacement placeWallSign(Block chestBlock, BlockFace preferredFront) {
        List<BlockFace> order = new ArrayList<>();
        order.add(preferredFront);
        order.add(preferredFront.getOppositeFace());
        order.add(rotateRight(preferredFront));
        order.add(rotateLeft(preferredFront));
        order.add(BlockFace.UP);

        for (BlockFace face : order) {
            if (face == BlockFace.UP) {
                // As fallback, put sign on top of chest? not wall sign; skip
                continue;
            }
            Block signBlock = chestBlock.getRelative(face);
            if (!signBlock.getType().isAir()) continue;

            // Place wall sign at signBlock, attached to chest (behind)
            signBlock.setType(signMaterial);
            BlockState state = signBlock.getState();
            if (!(state instanceof Sign sign)) {
                signBlock.setType(Material.AIR);
                continue;
            }

            BlockData data = signBlock.getBlockData();
            if (data instanceof WallSign ws) {
                ws.setFacing(face);
                signBlock.setBlockData(ws);
            }

            return new SignPlacement(signBlock, face);
        }
        return null;
    }

    private BlockFace rotateRight(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
    }

    private BlockFace rotateLeft(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };
    }

    private boolean isBedrockOwner(UUID uuid) {
        // Try Floodgate API via reflection (compile-time optional)
        try {
            Class<?> apiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiCls.getMethod("getInstance").invoke(null);
            return (boolean) apiCls.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class SignPlacement {
        final Block sign;
        final BlockFace face;
        SignPlacement(Block sign, BlockFace face) {
            this.sign = sign;
            this.face = face;
        }
    }

    private static class ShopData {
        final String id;
        final UUID owner;
        final Material item;
        final int amount;
        final int price;
        final String world;
        final int chestX, chestY, chestZ;
        final int signX, signY, signZ;

        ShopData(String id, UUID owner, Material item, int amount, int price, String world,
                 int chestX, int chestY, int chestZ,
                 int signX, int signY, int signZ) {
            this.id = id;
            this.owner = owner;
            this.item = item;
            this.amount = amount;
            this.price = price;
            this.world = world;
            this.chestX = chestX;
            this.chestY = chestY;
            this.chestZ = chestZ;
            this.signX = signX;
            this.signY = signY;
            this.signZ = signZ;
        }

        Chest getChest() {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            Block b = w.getBlockAt(chestX, chestY, chestZ);
            if (!(b.getState() instanceof Chest chest)) return null;
            return chest;
        }

        Sign getSign() {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            Block b = w.getBlockAt(signX, signY, signZ);
            if (!(b.getState() instanceof Sign sign)) return null;
            return sign;
        }
    }
}
