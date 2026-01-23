package ru.altum.shopaltummc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class ShopAltumMCPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey KEY_OWNER;
    private NamespacedKey KEY_PRICE;
    private NamespacedKey KEY_AMOUNT;
    private NamespacedKey KEY_ITEM;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        KEY_OWNER = new NamespacedKey(this, "owner");
        KEY_PRICE = new NamespacedKey(this, "price");
        KEY_AMOUNT = new NamespacedKey(this, "amount");
        KEY_ITEM = new NamespacedKey(this, "item");

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shop")).setTabCompleter(this);
    }

    // ---------------- Commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }

        if (args.length == 0) {
            msg(p, String.join("\n", cfg().getStringList("messages.help")));
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            if (!p.hasPermission("shopaltummc.admin")) {
                msg(p, cfg().getString("messages.no-permission"));
                return true;
            }
            reloadConfig();
            msg(p, cfg().getString("messages.reloaded"));
            return true;
        }

        if ("set".equalsIgnoreCase(args[0])) {
            if (args.length < 3) {
                msg(p, "&cИспользуй: /shop set <цена> <кол-во>");
                return true;
            }

            int price;
            int amount;
            try {
                price = Integer.parseInt(args[1]);
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                msg(p, "&cЦена и кол-во должны быть числами.");
                return true;
            }
            if (price <= 0 || amount <= 0) {
                msg(p, "&cЦена и кол-во должны быть больше 0.");
                return true;
            }

            Block target = p.getTargetBlockExact(5);
            if (target == null) {
                msg(p, cfg().getString("messages.look-at-chest"));
                return true;
            }
            BlockState state = target.getState();
            if (!(state instanceof Chest chest)) {
                msg(p, cfg().getString("messages.not-a-chest"));
                return true;
            }

            // item for sale = first non-air stack in chest
            ItemStack item = firstNonAir(chest.getBlockInventory());
            if (item == null) {
                msg(p, "&cПоложи в сундук предмет для продажи (хотя бы 1).");
                return true;
            }

            // Find sign on the front face is complex; simplest: create sign on chest block itself if clicked? 
            // We'll use: player must click a sign attached to chest to finalize.
            msg(p, "&aТеперь поставь табличку на сундук и напиши на 1 строке [Магазин], затем кликни по ней.");
            // store pending in memory
            PendingStore.set(p.getUniqueId(), new Pending(p.getUniqueId(), getLocationKey(chest.getLocation()), price, amount));
            return true;
        }

        msg(p, String.join("\n", cfg().getStringList("messages.help")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("set", "reload");
        return Collections.emptyList();
    }

    // ---------------- Events ----------------

    @EventHandler
    public void onSignClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        BlockState st = e.getClickedBlock().getState();
        if (!(st instanceof Sign sign)) return;

        Player p = e.getPlayer();

        // If this is a shop sign -> buy
        if (isShopSign(sign)) {
            e.setCancelled(true);

            ShopData data = readShop(sign);
            if (data == null) {
                msg(p, cfg().getString("messages.shop-not-found"));
                return;
            }

            Chest chest = data.findChest();
            if (chest == null) {
                msg(p, cfg().getString("messages.shop-not-found"));
                return;
            }

            ItemStack prototype = firstNonAir(chest.getBlockInventory());
            if (prototype == null) {
                msg(p, cfg().getString("messages.not-enough-items"));
                return;
            }

            int available = countSimilar(chest.getBlockInventory(), prototype);
            if (available < data.amount) {
                msg(p, cfg().getString("messages.not-enough-items"));
                return;
            }

            Material currencyMat = getCurrencyMaterial();
            int money = countMaterial(p.getInventory(), currencyMat);
            if (money < data.price) {
                msg(p, cfg().getString("messages.not-enough-money"));
                // optional: close inventory (not needed since click sign)
                return;
            }

            // take money from player, put into chest
            removeMaterial(p.getInventory(), currencyMat, data.price);
            chest.getBlockInventory().addItem(new ItemStack(currencyMat, data.price));

            // give items to player, remove from chest
            ItemStack give = prototype.clone();
            give.setAmount(data.amount);
            if (!canFit(p.getInventory(), give)) {
                // refund if cannot fit
                chest.getBlockInventory().removeItem(new ItemStack(currencyMat, data.price));
                p.getInventory().addItem(new ItemStack(currencyMat, data.price));
                msg(p, "&cОсвободи место в инвентаре.");
                return;
            }

            removeSimilar(chest.getBlockInventory(), prototype, data.amount);
            p.getInventory().addItem(give);

            String itemName = prettyItemName(give.getType());
            msg(p, cfg().getString("messages.bought")
                    .replace("%item%", itemName)
                    .replace("%amount%", String.valueOf(data.amount))
                    .replace("%price%", String.valueOf(data.price))
                    .replace("%currency%", currencyDisplay()));
            return;
        }

        // otherwise, try finalize creation if pending and sign first line is [Магазин]
        Pending pending = PendingStore.get(p.getUniqueId());
        if (pending == null) return;

        String line1 = strip(sign.getLine(0));
        if (!line1.equalsIgnoreCase("[Магазин]")) return;

        Chest chest = pending.findChest();
        if (chest == null) {
            msg(p, cfg().getString("messages.shop-not-found"));
            PendingStore.clear(p.getUniqueId());
            return;
        }

        ItemStack item = firstNonAir(chest.getBlockInventory());
        if (item == null) {
            msg(p, "&cПоложи предмет в сундук.");
            return;
        }

        // write shop data into sign PDC
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(KEY_OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
        pdc.set(KEY_PRICE, PersistentDataType.INTEGER, pending.price);
        pdc.set(KEY_AMOUNT, PersistentDataType.INTEGER, pending.amount);
        pdc.set(KEY_ITEM, PersistentDataType.STRING, item.getType().getKey().toString());

        // update sign text
        applySignText(sign, p.getName(), item.getType(), pending.amount, pending.price);
        sign.update(true, false);

        msg(p, cfg().getString("messages.sign-created")
                .replace("%price%", String.valueOf(pending.price))
                .replace("%amount%", String.valueOf(pending.amount))
                .replace("%currency%", currencyDisplay()));

        PendingStore.clear(p.getUniqueId());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!cfg().getBoolean("protection.prevent-break", true)) return;

        BlockState st = e.getBlock().getState();
        Player p = e.getPlayer();

        // protect sign blocks that are shop signs
        if (st instanceof Sign sign && isShopSign(sign)) {
            ShopData data = readShop(sign);
            if (data == null) return;

            if (!isOwnerOrAdmin(p, data.owner)) {
                e.setCancelled(true);
                msg(p, cfg().getString("messages.owner-only-break"));
            }
            return;
        }

        // protect chest blocks that are linked to any shop sign nearby
        if (st instanceof Chest chest) {
            UUID owner = findOwnerForChest(chest);
            if (owner == null) return;

            if (!isOwnerOrAdmin(p, owner)) {
                e.setCancelled(true);
                msg(p, cfg().getString("messages.owner-only-break"));
            }
        }
    }

    @EventHandler
    public void onChestOpen(PlayerInteractEvent e) {
        if (!cfg().getBoolean("protection.prevent-open", true)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        BlockState st = e.getClickedBlock().getState();
        if (!(st instanceof Chest chest)) return;

        UUID owner = findOwnerForChest(chest);
        if (owner == null) return;

        Player p = e.getPlayer();
        if (!isOwnerOrAdmin(p, owner)) {
            e.setCancelled(true);
            msg(p, cfg().getString("messages.cannot-open"));
        }
    }

    // ---------------- Helpers ----------------

    private FileConfiguration cfg() { return getConfig(); }

    private void msg(Player p, String text) {
        if (text == null) return;
        String prefix = cfg().getString("messages.prefix", "");
        String colored = color(prefix + text);
        for (String line : colored.split("\n")) p.sendMessage(line);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String strip(String s) {
        return ChatColor.stripColor(s == null ? "" : s).trim();
    }

    private Material getCurrencyMaterial() {
        String name = cfg().getString("currency.material", "DIAMOND");
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Material.DIAMOND; }
    }

    private String currencyDisplay() {
        return cfg().getString("currency.display", "алмазов");
    }

    private ItemStack firstNonAir(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() != Material.AIR && it.getAmount() > 0) return it;
        }
        return null;
    }

    private int countSimilar(Inventory inv, ItemStack proto) {
        int total = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.isSimilar(proto)) total += it.getAmount();
        }
        return total;
    }

    private void removeSimilar(Inventory inv, ItemStack proto, int amount) {
        int left = amount;
        ItemStack[] items = inv.getContents();
        for (int i = 0; i < items.length && left > 0; i++) {
            ItemStack it = items[i];
            if (it == null || !it.isSimilar(proto)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            if (it.getAmount() <= 0) items[i] = null;
        }
        inv.setContents(items);
    }

    private int countMaterial(Inventory inv, Material mat) {
        int total = 0;
        for (ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == mat) total += it.getAmount();
        }
        return total;
    }

    private void removeMaterial(Inventory inv, Material mat, int amount) {
        int left = amount;
        ItemStack[] items = inv.getContents();
        for (int i = 0; i < items.length && left > 0; i++) {
            ItemStack it = items[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            if (it.getAmount() <= 0) items[i] = null;
        }
        inv.setContents(items);
    }

    private boolean canFit(Inventory inv, ItemStack stack) {
        HashMap<Integer, ItemStack> leftovers = inv.addItem(stack.clone());
        if (leftovers.isEmpty()) return true;
        // rollback: remove what we added
        inv.removeItem(stack);
        return false;
    }

    private boolean isOwnerOrAdmin(Player p, UUID owner) {
        return p.getUniqueId().equals(owner) || p.hasPermission("shopaltummc.admin") || p.isOp();
    }

    private boolean isShopSign(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        return pdc.has(KEY_OWNER, PersistentDataType.STRING) && pdc.has(KEY_PRICE, PersistentDataType.INTEGER);
    }

    private ShopData readShop(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String ownerStr = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        Integer price = pdc.get(KEY_PRICE, PersistentDataType.INTEGER);
        Integer amount = pdc.get(KEY_AMOUNT, PersistentDataType.INTEGER);
        String itemKey = pdc.get(KEY_ITEM, PersistentDataType.STRING);
        if (ownerStr == null || price == null || amount == null) return null;

        UUID owner;
        try { owner = UUID.fromString(ownerStr); } catch (Exception e) { return null; }

        Material mat = Material.AIR;
        if (itemKey != null) {
            try {
                mat = Material.matchMaterial(itemKey);
            } catch (Exception ignored) {}
        }
        if (mat == null) mat = Material.AIR;

        return new ShopData(owner, price, amount, sign.getLocation(), mat);
    }

    private UUID findOwnerForChest(Chest chest) {
        // look for shop signs within 1 block around the chest (walls)
        for (Block b : nearbyBlocks(chest.getBlock(), 2)) {
            BlockState st = b.getState();
            if (!(st instanceof Sign s)) continue;
            if (!isShopSign(s)) continue;

            ShopData data = readShop(s);
            if (data == null) continue;

            // If sign is attached near this chest, accept it
            if (data.isNearChest(chest.getBlock())) return data.owner;
        }
        return null;
    }

    private List<Block> nearbyBlocks(Block center, int radius) {
        List<Block> out = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    out.add(center.getWorld().getBlockAt(center.getX()+x, center.getY()+y, center.getZ()+z));
                }
            }
        }
        return out;
    }

    private void applySignText(Sign sign, String ownerName, Material mat, int amount, int price) {
        String l1 = cfg().getString("sign.line1", "&a[Магазин]");
        String l2 = cfg().getString("sign.line2", "&f%owner%").replace("%owner%", ownerName);
        String l3 = cfg().getString("sign.line3", "&e%item% x%amount%")
                .replace("%item%", prettyItemName(mat))
                .replace("%amount%", String.valueOf(amount));
        String l4 = cfg().getString("sign.line4", "&6Цена: %price% %currency%")
                .replace("%price%", String.valueOf(price))
                .replace("%currency%", currencyDisplay());

        sign.setLine(0, color(l1));
        sign.setLine(1, color(l2));
        sign.setLine(2, color(l3));
        sign.setLine(3, color(l4));
    }

    private String prettyItemName(Material mat) {
        if (mat == null) return "Предмет";
        // simple russian-ish formatting: replace underscores and lowercase
        String base = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        // title case
        return Arrays.stream(base.split(" "))
                .filter(s -> !s.isBlank())
                .map(s -> s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1))
                .collect(Collectors.joining(" "));
    }

    // ---------------- Data ----------------

    private static final class Pending {
        final UUID owner;
        final String chestKey;
        final int price;
        final int amount;

        Pending(UUID owner, String chestKey, int price, int amount) {
            this.owner = owner;
            this.chestKey = chestKey;
            this.price = price;
            this.amount = amount;
        }

        Chest findChest() {
            return ChestLocator.find(chestKey);
        }
    }

    private static final class PendingStore {
        private static final Map<UUID, Pending> map = new HashMap<>();
        static void set(UUID id, Pending p) { map.put(id, p); }
        static Pending get(UUID id) { return map.get(id); }
        static void clear(UUID id) { map.remove(id); }
    }

    private static final class ShopData {
        final UUID owner;
        final int price;
        final int amount;
        final org.bukkit.Location signLoc;
        final Material itemMat;

        ShopData(UUID owner, int price, int amount, org.bukkit.Location signLoc, Material itemMat) {
            this.owner = owner;
            this.price = price;
            this.amount = amount;
            this.signLoc = signLoc;
            this.itemMat = itemMat;
        }

        Chest findChest() {
            // search for chest near sign
            Block signBlock = signLoc.getBlock();
            for (Block b : NearbyBlocksCompat.getNearbyBlocks(signBlock,2,2,2)) {
                if (b.getState() instanceof Chest chest) return chest;
            }
            return null;
        }

        boolean isNearChest(Block chestBlock) {
            return chestBlock.getWorld().equals(signLoc.getWorld())
                    && chestBlock.getLocation().distanceSquared(signLoc) <= 9; // within 3 blocks
        }
    }

    // Paper adds Block#getNearbyBlocks in newer versions; provide fallback by extension
    private static final class ChestLocator {
        static final Map<String, org.bukkit.Location> locs = new HashMap<>();
        static Chest find(String key) {
            org.bukkit.Location loc = locs.get(key);
            if (loc == null) {
                // cannot resolve without stored location; key format: world;x;y;z
                String[] p = key.split(";");
                if (p.length != 4) return null;
                var world = Bukkit.getWorld(p[0]);
                if (world == null) return null;
                loc = new org.bukkit.Location(world, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                locs.put(key, loc);
            }
            BlockState st = loc.getBlock().getState();
            return (st instanceof Chest c) ? c : null;
        }
    }

    // helper to store chest location key
    private String getLocationKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    // add getNearbyBlocks compatibility
    private static final class NearbyBlocksCompat {
        static Iterable<Block> getNearbyBlocks(Block b, int x, int y, int z) {
            List<Block> out = new ArrayList<>();
            for (int dx=-x; dx<=x; dx++)
                for (int dy=-y; dy<=y; dy++)
                    for (int dz=-z; dz<=z; dz++)
                        out.add(b.getWorld().getBlockAt(b.getX()+dx,b.getY()+dy,b.getZ()+dz));
            return out;
        }
    }

    // Extension for older API: use our compat method
    private static class BlockExt {
        static Iterable<Block> nearby(Block b, int x, int y, int z) { return NearbyBlocksCompat.getNearbyBlocks(b,x,y,z); }
    }
}
