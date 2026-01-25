
package ru.altum.shopaltummc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockIterator;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public final class ShopAltumMCPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey KEY_SHOP;
    private NamespacedKey KEY_OWNER;
    private NamespacedKey KEY_ITEM;
    private NamespacedKey KEY_AMOUNT;
    private NamespacedKey KEY_PRICE;

    // Material name -> display
    private Map<String, String> itemNames = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfNotExists("items.yml");

        KEY_SHOP = new NamespacedKey(this, "shop");
        KEY_OWNER = new NamespacedKey(this, "owner");
        KEY_ITEM = new NamespacedKey(this, "item");
        KEY_AMOUNT = new NamespacedKey(this, "amount");
        KEY_PRICE = new NamespacedKey(this, "price");

        loadItems();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
    }

    private void saveResourceIfNotExists(String name) {
        File f = new File(getDataFolder(), name);
        if (!f.exists()) {
            saveResource(name, false);
        }
    }

    private void loadItems() {
        try {
            File f = new File(getDataFolder(), "items.yml");
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            if (yml.isConfigurationSection("items")) {
                for (String k : Objects.requireNonNull(yml.getConfigurationSection("items")).getKeys(false)) {
                    String v = yml.getString("items." + k);
                    if (v != null) itemNames.put(k.toUpperCase(Locale.ROOT), v);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load items.yml: " + e.getMessage());
        }
    }

    private String itemDisplay(Material mat) {
        String s = itemNames.get(mat.name());
        return (s != null && !s.isBlank()) ? s : beautify(mat.name());
    }

    private static String beautify(String name) {
        String[] parts = name.toLowerCase(Locale.ROOT).split("_");
        return Arrays.stream(parts).map(p -> p.isEmpty() ? p : (p.substring(0,1).toUpperCase(Locale.ROOT)+p.substring(1)))
                .collect(Collectors.joining(" "));
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }
        if (args.length != 3 || !"set".equalsIgnoreCase(args[0])) {
            msg(p, cfg("messages.usage"));
            return true;
        }

        int amount;
        int price;
        try {
            amount = Integer.parseInt(args[1]);
            price = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            msg(p, cfg("messages.bad_number"));
            return true;
        }
        if (amount <= 0 || price <= 0) {
            msg(p, cfg("messages.bad_number"));
            return true;
        }

        Block chestBlock = getTargetBlock(p, 6);
        if (chestBlock == null || chestBlock.getType() != Material.CHEST) {
            msg(p, cfg("messages.look_at_chest"));
            return true;
        }

        // Create / overwrite shop
        Material sellMat = firstNonAirItem(chestBlock);
        if (sellMat == null) {
            msg(p, ChatColor.translateAlternateColorCodes('&', "&cВ сундуке нет предметов для продажи."));
            return true;
        }

        // Mark chest
        if (!markChestShop(chestBlock, p.getUniqueId(), sellMat, amount, price)) {
            msg(p, ChatColor.translateAlternateColorCodes('&', "&cНе удалось сохранить данные магазина."));
            return true;
        }

        // Place sign (in front of chest). If blocked -> try left/right/back.
        Sign sign = placeShopSign(chestBlock, p, sellMat, amount, price);
        if (sign == null) {
            msg(p, ChatColor.translateAlternateColorCodes('&', "&cНет места для таблички (спереди/сбоку занято)."));
            return true;
        }

        msg(p, cfg("messages.created"));
        return true;
    }

    private Block getTargetBlock(Player p, int maxDistance) {
        BlockIterator it = new BlockIterator(p, maxDistance);
        while (it.hasNext()) {
            Block b = it.next();
            if (!b.getType().isAir()) {
                return b;
            }
        }
        return null;
    }

    private Material firstNonAirItem(Block chestBlock) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return null;
        Inventory inv = chest.getBlockInventory();
        for (ItemStack is : inv.getContents()) {
            if (is != null && is.getType() != Material.AIR && is.getAmount() > 0) return is.getType();
        }
        return null;
    }

    private boolean markChestShop(Block chestBlock, UUID owner, Material item, int amount, int price) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof TileState ts)) return false;
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_OWNER, PersistentDataType.STRING, owner.toString());
        pdc.set(KEY_ITEM, PersistentDataType.STRING, item.name());
        pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, amount);
        pdc.set(KEY_PRICE, PersistentDataType.INTEGER, price);
        return ts.update(true, false);
    }

    private Sign placeShopSign(Block chestBlock, Player creator, Material item, int amount, int price) {
        List<Block> spots = signSpots(chestBlock, creator);
        for (Block spot : spots) {
            if (!spot.getType().isAir()) continue;
            BlockData bd = spot.getBlockData();
            spot.setType(Material.OAK_WALL_SIGN, false);
            BlockData newBd = spot.getBlockData();
            if (newBd instanceof WallSign ws) {
                ws.setFacing(facingForSpot(chestBlock, spot));
                spot.setBlockData(ws, false);
            }

            BlockState st = spot.getState();
            if (!(st instanceof Sign sign)) continue;

            // Mark sign as shop sign
            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            pdc.set(KEY_SHOP, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_OWNER, PersistentDataType.STRING, creator.getUniqueId().toString());

            // Text
            String l1 = color("&f[Магазин]");
            String l2 = color("&f" + creator.getName());
            String l3 = color("&c" + itemDisplay(item));
            String l4 = color("&fЦена: &c" + amount + "&fшт. &c" + price + "% &fалм");
            sign.setLine(0, l1);
            sign.setLine(1, l2);
            sign.setLine(2, l3);
            sign.setLine(3, l4);

            sign.update(true, false);
            return sign;
        }
        return null;
    }

    private List<Block> signSpots(Block chestBlock, Player creator) {
        BlockFace front = chestFront(chestBlock, creator);
        BlockFace left = rotateLeft(front);
        BlockFace right = rotateRight(front);
        BlockFace back = front.getOppositeFace();

        return List.of(
                chestBlock.getRelative(front),
                chestBlock.getRelative(left),
                chestBlock.getRelative(right),
                chestBlock.getRelative(back)
        );
    }

    private BlockFace chestFront(Block chestBlock, Player creator) {
        BlockData bd = chestBlock.getBlockData();
        if (bd instanceof Directional d) return d.getFacing();
        return creator.getFacing();
    }

    private BlockFace facingForSpot(Block chestBlock, Block spot) {
        // spot is adjacent to chest. We want sign to face outwards away from chest,
        // which means facing = direction from chest -> spot.
        int dx = spot.getX() - chestBlock.getX();
        int dz = spot.getZ() - chestBlock.getZ();
        if (dx == 1) return BlockFace.EAST;
        if (dx == -1) return BlockFace.WEST;
        if (dz == 1) return BlockFace.SOUTH;
        if (dz == -1) return BlockFace.NORTH;
        // fallback
        return chestFront(chestBlock, Bukkit.getPlayer(UUID.fromString(getChestOwner(chestBlock))).orElse(null));
    }

    private static BlockFace rotateLeft(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> f;
        };
    }
    private static BlockFace rotateRight(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> f;
        };
    }

    // ---------------- Protection & Buying ----------------

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        // Protect chest
        if (isShopChest(b)) {
            String owner = getChestOwner(b);
            if (owner != null && !owner.equalsIgnoreCase(p.getUniqueId().toString()) && !p.hasPermission("shop.admin")) {
                msg(p, cfg("messages.protected").replace("%owner%", ownerName(owner)));
                e.setCancelled(true);
            }
            return;
        }

        // Protect sign
        BlockState st = b.getState();
        if (st instanceof Sign sign && isShopSign(sign)) {
            String owner = sign.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
            if (owner != null && !owner.equalsIgnoreCase(p.getUniqueId().toString()) && !p.hasPermission("shop.admin")) {
                msg(p, cfg("messages.protected").replace("%owner%", ownerName(owner)));
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Block b = e.getClickedBlock();
        Player p = e.getPlayer();

        BlockState st = b.getState();
        if (!(st instanceof Sign sign)) return;
        if (!isShopSign(sign)) return;

        // Find attached chest: behind the sign (opposite of facing)
        BlockFace face = BlockFace.NORTH;
        BlockData bd = b.getBlockData();
        if (bd instanceof WallSign ws) face = ws.getFacing();
        Block chestBlock = b.getRelative(face.getOppositeFace());
        if (!isShopChest(chestBlock)) return;

        String owner = getChestOwner(chestBlock);
        if (owner != null && owner.equalsIgnoreCase(p.getUniqueId().toString())) {
            msg(p, cfg("messages.self_buy"));
            e.setCancelled(true);
            return;
        }

        ShopData data = readShopData(chestBlock);
        if (data == null) return;

        if (!takeItems(chestBlock, data.item, data.amount)) {
            msg(p, cfg("messages.not_enough"));
            e.setCancelled(true);
            return;
        }

        // Here you can integrate economy. For now: just give items for "price" diamonds from player.
        if (!takeDiamonds(p, data.price)) {
            // rollback items back into chest
            giveItemsToChest(chestBlock, data.item, data.amount);
            msg(p, cfg("messages.no_money"));
            e.setCancelled(true);
            return;
        }

        p.getInventory().addItem(new ItemStack(data.item, data.amount));
        msg(p, cfg("messages.bought")
                .replace("%amount%", String.valueOf(data.amount))
                .replace("%item%", itemDisplay(data.item))
                .replace("%price%", String.valueOf(data.price)));
        e.setCancelled(true);
    }

    private boolean takeDiamonds(Player p, int price) {
        int need = price;
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack is = inv[i];
            if (is == null || is.getType() != Material.DIAMOND) continue;
            int take = Math.min(is.getAmount(), need);
            is.setAmount(is.getAmount() - take);
            need -= take;
            if (is.getAmount() <= 0) inv[i] = null;
            if (need <= 0) break;
        }
        p.getInventory().setContents(inv);
        return need <= 0;
    }

    private boolean takeItems(Block chestBlock, Material mat, int amount) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return false;
        Inventory inv = chest.getBlockInventory();
        int have = 0;
        for (ItemStack is : inv.getContents()) {
            if (is != null && is.getType() == mat) have += is.getAmount();
        }
        if (have < amount) return false;

        int need = amount;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack is = inv.getItem(i);
            if (is == null || is.getType() != mat) continue;
            int take = Math.min(is.getAmount(), need);
            is.setAmount(is.getAmount() - take);
            need -= take;
            if (is.getAmount() <= 0) inv.setItem(i, null);
            if (need <= 0) break;
        }
        return true;
    }

    private void giveItemsToChest(Block chestBlock, Material mat, int amount) {
        BlockState st = chestBlock.getState();
        if (!(st instanceof org.bukkit.block.Chest chest)) return;
        chest.getBlockInventory().addItem(new ItemStack(mat, amount));
    }

    private boolean isShopChest(Block b) {
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return false;
        Byte flag = ts.getPersistentDataContainer().get(KEY_SHOP, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private String getChestOwner(Block b) {
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return null;
        return ts.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
    }

    private ShopData readShopData(Block b) {
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return null;
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        String itemName = pdc.get(KEY_ITEM, PersistentDataType.STRING);
        Integer amount = pdc.get(KEY_AMOUNT, PersistentDataType.INTEGER);
        Integer price = pdc.get(KEY_PRICE, PersistentDataType.INTEGER);
        if (itemName == null || amount == null || price == null) return null;
        Material mat = Material.matchMaterial(itemName);
        if (mat == null) return null;
        return new ShopData(mat, amount, price);
    }

    private boolean isShopSign(Sign sign) {
        Byte flag = sign.getPersistentDataContainer().get(KEY_SHOP, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private String ownerName(String uuidStr) {
        try {
            UUID u = UUID.fromString(uuidStr);
            Player online = Bukkit.getPlayer(u);
            if (online != null) return online.getName();
        } catch (Exception ignored) {}
        return uuidStr;
    }

    // ---------------- Messages & Tab ----------------

    private String cfg(String path) {
        return Objects.requireNonNullElse(getConfig().getString(path), "");
    }
    private void msg(Player p, String s) {
        String pref = cfg("messages.prefix");
        p.sendMessage(color(pref + s));
    }
    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("set", "reload");
        return Collections.emptyList();
    }

    private record ShopData(Material item, int amount, int price) {}
}