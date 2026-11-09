package com.enderdevelopment.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin implements Listener, CommandExecutor {
    private static final String LIST_TITLE = "Lojas";
    private final Map<String, Shop> shops = new HashMap<>();
    private final Map<UUID, PendingPrice> pendingPrices = new HashMap<>();
    private NamespacedKey shopKey;
    private NamespacedKey listingKey;
    private ClaimAccess claimAccess;
    private EconomyAccess economyAccess;

    @Override
    public void onEnable() {
        shopKey = new NamespacedKey(this, "shop");
        listingKey = new NamespacedKey(this, "listing");
        claimAccess = new ClaimAccess();
        economyAccess = new EconomyAccess();
        loadShops();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("loja") != null) getCommand("loja").setExecutor(this);
    }

    @Override
    public void onDisable() {
        saveShops();
        pendingPrices.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("criar")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Uso: /loja criar <nome da loja>");
                return true;
            }
            Block target = player.getTargetBlockExact(5);
            if (target == null || target.getType() != Material.CHEST) {
                player.sendMessage(ChatColor.RED + "Olhe para um baú a até 5 blocos para criar a loja.");
                return true;
            }
            if (!claimAccess.isClaimed(target.getLocation()) || !claimAccess.canBuild(player, target.getLocation(), target.getType())) {
                player.sendMessage(ChatColor.RED + "[Exstart] você não pode fazer sua loja fora da sua claim");
                return true;
            }
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            String key = name.toLowerCase(Locale.ROOT);
            if (shops.containsKey(key)) {
                player.sendMessage(ChatColor.RED + "Já existe uma loja com esse nome.");
                return true;
            }
            if (shopAt(target.getLocation()) != null) {
                player.sendMessage(ChatColor.RED + "Este baú já pertence a uma loja.");
                return true;
            }
            shops.put(key, new Shop(name, player.getUniqueId(), target.getLocation()));
            saveShops();
            player.sendMessage(ChatColor.GREEN + "Loja criada. Segure o item e clique com o botão esquerdo neste baú para cadastrar o preço.");
            return true;
        }
        openShopList(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHEST) return;
        Shop shop = shopAt(block.getLocation());
        if (shop == null) return;
        Player player = event.getPlayer();
        if (!claimAccess.canBuild(player, block.getLocation(), block.getType())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "você não pode abusar, destruir ou matar entidades, você tem permissão?");
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            event.setCancelled(true);
            ItemStack held = player.getInventory().getItemInMainHand().clone();
            pendingPrices.put(player.getUniqueId(), new PendingPrice(shop, held));
            player.sendMessage(ChatColor.GREEN + "quanto você quer vender o " + held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            openShop(player, shop);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        Shop shop = shopAt(location);
        if (shop != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "você não pode destruir, quebrar e abusar da construção do jogador que criou a loja dentro da claim");
            return;
        }
        if (claimAccess.isClaimed(location) && !claimAccess.canBuild(event.getPlayer(), location, event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "você não pode abusar, destruir ou matar entidades, você tem permissão?");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Location location = event.getBlock().getLocation();
        if (claimAccess.isClaimed(location) && !claimAccess.canBuild(event.getPlayer(), location, event.getBlock().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "você não pode abusar, destruir ou matar entidades, você tem permissão?");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker == null) return;
        Location location = event.getEntity().getLocation();
        if (claimAccess.isClaimed(location) && !claimAccess.canBuild(attacker, location, Material.AIR)) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "você não pode abusar, destruir ou matar entidades, você tem permissão?");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        PendingPrice pending = pendingPrices.remove(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        double price;
        try {
            price = Double.parseDouble(event.getMessage().trim().replace(',', '.'));
            if (!Double.isFinite(price) || price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            Bukkit.getScheduler().runTask(this, () -> {
                event.getPlayer().sendMessage(ChatColor.RED + "Digite um preço válido maior que zero; clique novamente no baú para tentar outra vez.");
            });
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (!claimAccess.canBuild(event.getPlayer(), pending.shop.chest, Material.CHEST)
                    || shopAt(pending.shop.chest) != pending.shop) {
                event.getPlayer().sendMessage(ChatColor.RED + "você não pode abusar, destruir ou matar entidades, você tem permissão?");
                return;
            }
            pending.shop.item = pending.item;
            pending.shop.price = price;
            saveShops();
            event.getPlayer().sendMessage(ChatColor.GREEN + "Item cadastrado por " + price + ".");
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title.equals(LIST_TITLE)) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String key = clicked.getItemMeta().getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
            Shop shop = key == null ? null : shops.get(key);
            if (shop != null) openShop(player, shop);
            return;
        }
        if (!title.startsWith("Loja: ")) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String key = clicked.getItemMeta().getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
        Shop shop = key == null ? null : shops.get(key);
        if (shop == null || shop.item == null || shop.price <= 0) return;
        if (!economyAccess.withdraw(player, shop.price)) return;
        if (!economyAccess.deposit(Bukkit.getOfflinePlayer(shop.owner), shop.price)) {
            economyAccess.deposit(player, shop.price);
            player.sendMessage(ChatColor.RED + "A economia não conseguiu concluir o pagamento.");
            return;
        }
        ItemStack purchased = shop.item.clone();
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(purchased);
        if (!leftovers.isEmpty()) {
            economyAccess.withdraw(Bukkit.getOfflinePlayer(shop.owner), shop.price);
            economyAccess.deposit(player, shop.price);
            player.sendMessage(ChatColor.RED + "Seu inventário está cheio.");
            return;
        }
        player.sendMessage(ChatColor.GREEN + "Compra realizada por " + shop.price + ".");
        player.closeInventory();
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void openShopList(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, LIST_TITLE);
        for (Shop shop : shops.values()) {
            ItemStack icon = new ItemStack(shop.item == null ? Material.CHEST : shop.item.getType());
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + shop.name);
            meta.setLore(shop.item == null ? List.of(ChatColor.GRAY + "Nenhum item cadastrado") : List.of(ChatColor.GREEN + "Preço: " + shop.price));
            meta.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, shop.name.toLowerCase(Locale.ROOT));
            icon.setItemMeta(meta);
            inventory.addItem(icon);
        }
        player.openInventory(inventory);
    }

    private void openShop(Player player, Shop shop) {
        Inventory inventory = Bukkit.createInventory(null, 27, "Loja: " + shop.name);
        if (shop.item != null) {
            ItemStack display = shop.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setLore(List.of(ChatColor.GREEN + "Preço: " + shop.price, ChatColor.GRAY + "Para cadastrar novamente, segure o item e clique esquerdo no baú."));
                meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, shop.name.toLowerCase(Locale.ROOT));
                display.setItemMeta(meta);
            }
            inventory.setItem(13, display);
        }
        player.openInventory(inventory);
    }

    private Shop shopAt(Location location) {
        for (Shop shop : shops.values()) if (sameBlock(shop.chest, location)) return shop;
        return null;
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void loadShops() {
        for (String key : getConfig().getConfigurationSection("shops") == null
                ? List.<String>of() : getConfig().getConfigurationSection("shops").getKeys(false)) {
            String path = "shops." + key + ".";
            String worldName = getConfig().getString(path + "world");
            if (worldName == null || Bukkit.getWorld(worldName) == null) continue;
            try {
                Location location = new Location(Bukkit.getWorld(worldName), getConfig().getInt(path + "x"),
                        getConfig().getInt(path + "y"), getConfig().getInt(path + "z"));
                Shop shop = new Shop(getConfig().getString(path + "name", key),
                        UUID.fromString(getConfig().getString(path + "owner")), location);
                String encoded = getConfig().getString(path + "item");
                if (encoded != null && !encoded.isBlank()) shop.item = deserializeItem(encoded);
                shop.price = getConfig().getDouble(path + "price");
                shops.put(key.toLowerCase(Locale.ROOT), shop);
            } catch (RuntimeException | IOException | ClassNotFoundException exception) {
                getLogger().warning("Skipping invalid saved shop: " + key);
            }
        }
    }

    private void saveShops() {
        getConfig().set("shops", null);
        for (Map.Entry<String, Shop> entry : shops.entrySet()) {
            String path = "shops." + entry.getKey() + ".";
            Shop shop = entry.getValue();
            getConfig().set(path + "name", shop.name);
            getConfig().set(path + "owner", shop.owner.toString());
            getConfig().set(path + "world", shop.chest.getWorld().getName());
            getConfig().set(path + "x", shop.chest.getBlockX());
            getConfig().set(path + "y", shop.chest.getBlockY());
            getConfig().set(path + "z", shop.chest.getBlockZ());
            getConfig().set(path + "item", shop.item == null ? null : serializeItem(shop.item));
            getConfig().set(path + "price", shop.price);
        }
        saveConfig();
    }

    private String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(item); }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize shop item", exception);
        }
    }

    private ItemStack deserializeItem(String encoded) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object value = input.readObject();
            if (value instanceof ItemStack item) return item;
            throw new IOException("Saved shop item has an invalid type");
        }
    }

    private static final class Shop {
        private final String name;
        private final UUID owner;
        private final Location chest;
        private ItemStack item;
        private double price;

        private Shop(String name, UUID owner, Location chest) {
            this.name = name;
            this.owner = owner;
            this.chest = chest;
        }
    }

    private record PendingPrice(Shop shop, ItemStack item) { }

    private final class EconomyAccess {
        private Object provider() {
            try {
                Class<?> vaultEconomy = Class.forName("net.milkbowl.vault.economy.Economy");
                Method registrationMethod = Bukkit.getServicesManager().getClass()
                        .getMethod("getRegistration", Class.class);
                Object registration = registrationMethod.invoke(Bukkit.getServicesManager(), vaultEconomy);
                if (registration != null) {
                    Method providerMethod = registration.getClass().getMethod("getProvider");
                    Object provider = providerMethod.invoke(registration);
                    if (provider != null) return provider;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            Plugin economyX = Bukkit.getPluginManager().getPlugin("EconomyX");
            if (economyX == null || !economyX.isEnabled()) return null;
            for (Method method : economyX.getClass().getMethods()) {
                if (!method.getName().equalsIgnoreCase("getEconomy") || method.getParameterCount() != 0) continue;
                try {
                    Object provider = method.invoke(economyX);
                    if (provider != null) return provider;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return economyX;
        }

        boolean withdraw(Player player, double amount) {
            Object provider = provider();
            if (provider == null) {
                player.sendMessage(ChatColor.RED + "EconomyX não está instalado ou não está disponível.");
                return false;
            }
            try {
                Object result = invokeMoney(provider, "withdraw", player, amount);
                if (result instanceof Boolean success) return success;
                if (result != null && result.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("response")) {
                    Method success = result.getClass().getMethod("transactionSuccess");
                    return Boolean.TRUE.equals(success.invoke(result));
                }
                return result != null;
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + "A API do EconomyX não pôde processar o pagamento.");
                return false;
            }
        }

        boolean deposit(org.bukkit.OfflinePlayer player, double amount) {
            Object provider = provider();
            if (provider == null) return false;
            try {
                Object result = invokeMoney(provider, "deposit", player, amount);
                if (result instanceof Boolean success) return success;
                if (result != null && result.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("response")) {
                    Method success = result.getClass().getMethod("transactionSuccess");
                    return Boolean.TRUE.equals(success.invoke(result));
                }
                return result != null;
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                return false;
            }
        }

        private Object invokeMoney(Object provider, String operation, org.bukkit.OfflinePlayer player, double amount)
                throws ReflectiveOperationException {
            for (Method method : provider.getClass().getMethods()) {
                if (!method.getName().toLowerCase(Locale.ROOT).contains(operation)
                        || method.getParameterCount() != 2) continue;
                Class<?> accountType = method.getParameterTypes()[0];
                Object account = player;
                if (!accountType.isInstance(account)) {
                    account = player;
                    if (!accountType.isInstance(account) && player.isOnline()) {
                        account = player.getPlayer();
                    }
                }
                if (!accountType.isInstance(account)) {
                    if (accountType == String.class) account = player.getName();
                    else continue;
                }
                Class<?> amountType = method.getParameterTypes()[1];
                Object value = amountType == int.class || amountType == Integer.class
                        ? (int) amount : amountType == long.class || amountType == Long.class
                        ? (long) amount : amount;
                return method.invoke(provider, account, value);
            }
            throw new NoSuchMethodException(operation);
        }
    }

    private final class ClaimAccess {
        boolean canBuild(Player player, Location location, Material material) {
            if (player.hasPermission("exstartshops.admin")) return true;
            Object claim = getClaim(location);
            if (claim == null) return false;
            try {
                Object owner = claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (owner != null && (player.getUniqueId().toString().equalsIgnoreCase(String.valueOf(owner))
                        || player.getName().equalsIgnoreCase(String.valueOf(owner)))) return true;
                for (Method method : claim.getClass().getMethods()) {
                    if (!method.getName().equals("allowBuild") || method.getParameterCount() != 2) continue;
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    Object firstArgument = parameterTypes[0].isAssignableFrom(Player.class) ? player : player.getName();
                    Object result = method.invoke(claim, firstArgument, material);
                    return result == null;
                }
                for (Method method : claim.getClass().getMethods()) {
                    if (!method.getName().equals("allowBuild") || method.getParameterCount() != 1) continue;
                    Object result = method.invoke(claim, player.getName());
                    return result == null;
                }
                return false;
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                return false;
            }
        }

        boolean isClaimed(Location location) { return getClaim(location) != null; }

        private Object getClaim(Location location) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
            if (plugin == null || !plugin.isEnabled()) return null;
            try {
                Object dataStore = plugin.getClass().getMethod("getDataStore").invoke(plugin);
                for (Method method : dataStore.getClass().getMethods()) {
                    if (!method.getName().equals("getClaimAt") || method.getParameterCount() != 3
                            || method.getParameterTypes()[0] != Location.class
                            || method.getParameterTypes()[1] != boolean.class) continue;
                    return method.invoke(dataStore, location, true, null);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException exception) {
                return null;
            }
            return null;
        }
    }
}
