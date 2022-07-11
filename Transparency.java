package dev.Jacrispys.OneEightTests.TransparentPlayers;

import com.comphenix.packetwrapper.WrapperPlayServerScoreboardTeam;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.Jacrispys.OneEightTests.OneEightTestsMain;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagString;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.material.Dye;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static dev.Jacrispys.OneEightTests.utils.color.color;

public class TransparentCommand implements CommandExecutor, Listener {

    private final Plugin plugin;
    private final ProtocolManager manager;
    private final Map<UUID, List<UUID>> ghostMap = new HashMap<>();
    private final Map<UUID, Boolean> isTransparentMap = new HashMap<>();
    private final Map<UUID, Opacity> currentOpacity = new HashMap<>();

    public TransparentCommand(OneEightTestsMain main) {
        this.plugin = main;
        manager = main.getProtoManager();
        initListeners();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        main.getCommand("tpp").setExecutor(this);
    }

    /**
     * Just a command to give the player the item.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player p = (Player) sender;
        if (args.length == 0) {
            giveTransparencyItem(p);
        } else if (args.length == 1 && Bukkit.getOnlinePlayers().contains(Bukkit.getPlayer(args[0]))) {
            giveTransparencyItem(Bukkit.getPlayer(args[0]));
        }
        return false;
    }

    /**
     * Current implementation for housing.
     */
    protected void hidePlayers(Player target, Opacity opacity) {
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == target) return;
            if (opacity.getValue() > count) {
                target.showPlayer(online);
                count++;
            } else {
                target.hidePlayer(online);
            }
        }
    }

    /**
     * Cleans up client teams and removes effects from players that shouldn't have them.
     */
    protected void removeGhost(Player target) throws InvocationTargetException {
        for (UUID player : ghostMap.getOrDefault(target.getUniqueId(), new ArrayList<>())) {
            Player p = Bukkit.getPlayer(player);
            WrapperPlayServerScoreboardTeam teamPacket = new WrapperPlayServerScoreboardTeam();
            PacketContainer metaPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
            WrappedDataWatcher watcher = new WrappedDataWatcher();
            watcher.setEntity(p);
            watcher.setObject(0, (byte) 0, true);
            metaPacket.getIntegers().write(0, p.getEntityId());
            metaPacket.getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
            manager.sendServerPacket(target, metaPacket);

            teamPacket.getHandle().getStrings().write(0, p.getEntityId() + "." + target.getEntityId());
            teamPacket.setMode(1);
            teamPacket.sendPacket(target);

            ghostMap.put(target.getUniqueId(), new ArrayList<>());
        }
    }

    /**
     * Where the magic happens...
     * Here the packet manipulation helps both modify team values (client side) and give invisibility.
     */
    protected void ghostPlayer(Player target, Opacity opacity) throws InvocationTargetException {
        WrapperPlayServerScoreboardTeam teamPacket = new WrapperPlayServerScoreboardTeam();
        PacketContainer metaPacket = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        WrappedDataWatcher watcher = new WrappedDataWatcher();


        List<UUID> ghostViewers = ghostMap.getOrDefault(target.getUniqueId(), new ArrayList<>());
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {

            if (online == target) return;
            if (count >= opacity.getValue()) {
                target.hidePlayer(online);
                return;
            }


            target.showPlayer(online);
            watcher.setEntity(online);
            watcher.setObject(0, (byte) 32, true);
            metaPacket.getIntegers().write(0, online.getEntityId());
            metaPacket.getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
            manager.sendServerPacket(target, metaPacket);
            teamPacket.getHandle().getStrings().write(0, online.getEntityId() + "." + target.getEntityId());
            teamPacket.setMode(0);
            teamPacket.setPlayers(Lists.newArrayList(online.getName(), target.getName()));
            teamPacket.getHandle().getIntegers().write(2, 3);
            teamPacket.sendPacket(target);
            ghostViewers.add(online.getUniqueId());
            count++;
        }
        ghostMap.put(target.getUniqueId(), ghostViewers);
    }

    /**
     * adds a ProtocolLib packet listener to continually update the meta of players who should be transparent
     * otherwise the transparency gets removed after the player moves
     */
    public void initListeners() {
        if (manager != null) {
            manager.addPacketListener(new PacketAdapter(plugin,
                    PacketType.Play.Server.ENTITY_METADATA) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                        for (UUID uuid : ghostMap.keySet()) {
                            if (ghostMap.getOrDefault(uuid, new ArrayList<>()).contains(event.getPlayer().getUniqueId())) {
                                event.setCancelled(true);
                                event.getPacket().getIntegers().write(0, event.getPlayer().getEntityId());
                                WrappedDataWatcher watcher = new WrappedDataWatcher();
                                watcher.setEntity(event.getPlayer());
                                watcher.setObject(0, (byte) 32, true);
                                event.getPacket().getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
                                try {
                                    manager.sendServerPacket(Bukkit.getPlayer(uuid), event.getPacket());
                                } catch (InvocationTargetException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }

                }
            });
        }
    }

    /**
     * Handles the storing of maps... Ideally to be replaced with a DataBase that saves on server close, and pulls from maps during runtime.
     */
    @EventHandler
    public void onLogin(PlayerLoginEvent event) throws InvocationTargetException {
        Player p = event.getPlayer();
        if (!ghostMap.containsKey(event.getPlayer().getUniqueId())) {
            ghostMap.put(event.getPlayer().getUniqueId(), new ArrayList<>());
        }
        if (!isTransparentMap.containsKey(event.getPlayer().getUniqueId())) {
            isTransparentMap.put(event.getPlayer().getUniqueId(), false);
        }
        if (!currentOpacity.containsKey(p.getUniqueId())) {
            currentOpacity.put(p.getUniqueId(), Opacity.UNLIMITED);
        }

            if (isTransparentMap.getOrDefault(p.getUniqueId(), false)) {
                ghostPlayer(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            } else {
                removeGhost(p);
                hidePlayers(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            }
    }

    /**
     *
     * checks if the item was clicked
     */
    @EventHandler
    public void clickListener(PlayerInteractEvent event) {
        if (!((event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK))))
            return;
        if (event.getItem() != null) {
            net.minecraft.server.v1_8_R3.ItemStack craftItem = CraftItemStack.asNMSCopy(event.getItem());
            if (!(craftItem.hasTag()) || !(craftItem.getTag().getString("visMode").equalsIgnoreCase("1b"))) return;
            event.getPlayer().openInventory(visGui(event.getPlayer()));
        }
    }

    /**
     * GUI builder to replicate current Visibility menu.
     * @param target to show menu to
     * @return inventory
     */
    protected Inventory visGui(Player target) {
        Inventory visGui = Bukkit.createInventory(null, (9 * 6), color("&aVisibility Mode"));
        ItemStack blank = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 15);
        ItemMeta meta = blank.getItemMeta();
        meta.setDisplayName(" ");
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        blank.setItemMeta(meta);

        for (int i = 0; i < (9 * 6); i++) {
            if (i < 9) {
                visGui.setItem(i, blank);
            } else if (i > 44) {
                visGui.setItem(i, blank);
            }
        }
        visGui.setItem(9, blank);
        visGui.setItem(18, blank);
        visGui.setItem(27, blank);
        visGui.setItem(36, blank);

        visGui.setItem(17, blank);
        visGui.setItem(26, blank);
        visGui.setItem(35, blank);
        visGui.setItem(44, blank);

        ItemStack paperVis = new ItemStack(Material.PAPER, 1);
        ItemStack dyeVis = new Dye(DyeColor.GRAY).toItemStack();
        ItemStack dyeInUse = new Dye(DyeColor.LIME).toItemStack();
        dyeVis.setAmount(1);
        dyeInUse.setAmount(1);
        ItemMeta visMeta = paperVis.getItemMeta();
        visMeta.setDisplayName(color("&fMode: &COFF"));
        List<String> lore = new ArrayList<>();
        // OFF
        lore.add(color("&7Selecting this setting will hide " +
                "&7all players for you!"));
        lore.add(" ");
        lore.add(color("&6Alias: &7/visibility OFF"));
        lore.add(color("&eClick to enable!"));
        visMeta.setLore(lore);
        paperVis.setItemMeta(visMeta);
        dyeVis.setItemMeta(visMeta);
        visGui.setItem(11, paperVis);
        visGui.setItem(20, dyeVis);
        // 25
        visMeta.setDisplayName(color("&fMode: &825"));
        lore.set(0, color("&7Selecting this setting will " +
                "&7allow you to see up to &a25" +
                "&7 players at once!"));
        lore.set(2, color("&6Alias: &7/visibility 25"));
        visMeta.setLore(lore);
        paperVis.setItemMeta(visMeta);
        dyeVis.setItemMeta(visMeta);
        visGui.setItem(12, paperVis);
        visGui.setItem(21, dyeVis);
        // 50
        visMeta.setDisplayName(color("&fMode: &750"));
        lore.set(0, color("&7Selecting this setting will " +
                "&7allow you to see up to &a50" +
                "&7 players at once!"));
        lore.set(2, color("&6Alias: &7/visibility 50"));
        visMeta.setLore(lore);
        paperVis.setItemMeta(visMeta);
        dyeVis.setItemMeta(visMeta);
        visGui.setItem(13, paperVis);
        visGui.setItem(22, dyeVis);
        // 100
        visMeta.setDisplayName(color("&fMode: &f100"));
        lore.set(0, color("&7Selecting this setting will " +
                "&7allow you to see up to &a100" +
                "&7 players at once!"));
        lore.set(2, color("&6Alias: &7/visibility 100"));
        visMeta.setLore(lore);
        paperVis.setItemMeta(visMeta);
        dyeVis.setItemMeta(visMeta);
        visGui.setItem(14, paperVis);
        visGui.setItem(23, dyeVis);
        // UNLIMITED
        visMeta.setDisplayName(color("&fMode: &aUNLIMITED"));
        lore.clear();
        lore.add(color("&7Selecting this setting will show" +
                "&7 all players for you!"));
        lore.add("");
        lore.add(color("&c&lWARNING: High spec computer" +
                "&C is recommended for this setting!"));
        lore.add("");
        lore.add(color("&6Alias: &7/visibility UNLIMITED"));
        visMeta.setLore(lore);
        paperVis.setItemMeta(visMeta);
        dyeVis.setItemMeta(visMeta);
        visGui.setItem(15, paperVis);
        visGui.setItem(24, dyeVis);

        Opacity opacity = currentOpacity.getOrDefault(target.getUniqueId(), Opacity.UNLIMITED);
        switch (opacity) {
            case OFF:
                visGui.getItem(20).setDurability((short) 10);
                break;
            case TWENTY_FIVE:
                visGui.getItem(21).setDurability((short) 10);
                break;
            case FIFTY:
                visGui.getItem(22).setDurability((short) 10);
                break;
            case ONE_HUNDRED:
                visGui.getItem(23).setDurability((short) 10);
                break;
            case UNLIMITED:
                visGui.getItem(24).setDurability((short) 10);
                break;

        }
        // Transparency Item
        ItemStack skull = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        try {
            GameProfile playerProfile = new GameProfile(UUID.randomUUID(), "");

            String texture = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODM2MjQzOGZmNGVjZjhmNGEyY2FhMTI3NzU2MWM5NTEzYzlhOTg2ZGJlMzhhODBiOWJhZmNiZmVkOGIyYTljOCJ9fX0=";
            playerProfile.getProperties().put("textures", new Property("textures", texture));

            Field profileField;
            profileField = skullMeta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(skullMeta, playerProfile);
            profileField.setAccessible(false);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
        skullMeta.setDisplayName(color("&eChange player opacity"));
        List<String> skullLore = new ArrayList<>();
        skullLore.add(color(" "));
        skullLore.add(color("&7Modifies how visible players"));
        skullLore.add(color("&7appear to you."));
        skullLore.add(color(" "));
        skullLore.add(color("&eLeft/Right click to cycle!"));
        skullLore.add(color(" "));
        boolean isTransparent = isTransparentMap.getOrDefault(target.getUniqueId(), false);
        skullLore.add(isTransparent ? color("&7Current Opacity: &aTransparent") : color("&7Current Opacity: &aFull"));
        skullMeta.setLore(skullLore);

        skull.setItemMeta(skullMeta);
        visGui.setItem(40, skull);

        return visGui;
    }

    private Long clickCooldown = null;

    /**
     * The inventory click action manages all the implementations of which buttons do what.
     */
    @EventHandler
    public void inventoryAction(InventoryClickEvent event) throws InvocationTargetException {
        if (event.getCurrentItem() == null) return;
        if (event.getInventory().getName().equals(color("&aVisibility Mode"))) {
            event.setCancelled(true);
        } else return;
        if (clickCooldown != null && (System.currentTimeMillis() - clickCooldown) < 500) {
            event.getWhoClicked().sendMessage(color("&cWoah! Slow down!"));
            return;
        }
        if (event.getCurrentItem().getType() != Material.AIR && event.getCurrentItem().getItemMeta().getDisplayName().equals(color("&eChange player opacity"))) {
            ItemStack skull = event.getCurrentItem();
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            boolean isTransparent = isTransparentMap.getOrDefault(event.getWhoClicked().getUniqueId(), false);
            List<String> lore = meta.getLore();
            lore.set(6, isTransparent ? color("&7Current Opacity: &aFull") : color("&7Current Opacity: &aTransparent"));
            meta.setLore(lore);
            skull.setItemMeta(meta);
            event.getInventory().setItem(40, skull);
            Player p = (Player) event.getWhoClicked();
            p.playSound(p.getLocation(), Sound.CLICK, 100F, 1.0F);
            isTransparentMap.put(event.getWhoClicked().getUniqueId(), !isTransparent);
            boolean currentlyTransparent = !isTransparent;
            if (currentlyTransparent) {
                ghostPlayer(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            } else {
                removeGhost(p);
                hidePlayers(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            }
        } else if (event.getCurrentItem().getType() != Material.AIR && event.getCurrentItem().getType() != Material.STAINED_GLASS_PANE) {
            Player p = (Player) event.getWhoClicked();
            Opacity opacity = currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED);
            switch (opacity) {
                case OFF:
                    event.getInventory().getItem(20).setDurability((short) 8);
                    break;
                case TWENTY_FIVE:
                    event.getInventory().getItem(21).setDurability((short) 8);
                    break;
                case FIFTY:
                    event.getInventory().getItem(22).setDurability((short) 8);
                    break;
                case ONE_HUNDRED:
                    event.getInventory().getItem(23).setDurability((short) 8);
                    break;
                case UNLIMITED:
                default:
                    event.getInventory().getItem(24).setDurability((short) 8);
                    break;

            }
            p.playSound(p.getLocation(), Sound.CLICK, 100F, 1.0F);

            switch (event.getSlot()) {

                case 11:
                case 20:
                    currentOpacity.put(event.getWhoClicked().getUniqueId(), Opacity.OFF);
                    event.getInventory().getItem(20).setDurability((short) 10);
                    break;
                case 12:
                case 21:
                    currentOpacity.put(event.getWhoClicked().getUniqueId(), Opacity.TWENTY_FIVE);
                    event.getInventory().getItem(21).setDurability((short) 10);
                    break;
                case 13:
                case 22:
                    currentOpacity.put(event.getWhoClicked().getUniqueId(), Opacity.FIFTY);
                    event.getInventory().getItem(22).setDurability((short) 10);
                    break;
                case 14:
                case 23:
                    currentOpacity.put(event.getWhoClicked().getUniqueId(), Opacity.ONE_HUNDRED);
                    event.getInventory().getItem(23).setDurability((short) 10);
                    break;
                case 15:
                case 24:
                    currentOpacity.put(event.getWhoClicked().getUniqueId(), Opacity.UNLIMITED);
                    event.getInventory().getItem(24).setDurability((short) 10);
                    break;
            }
            if (isTransparentMap.getOrDefault(p.getUniqueId(), false)) {
                ghostPlayer(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            } else {
                removeGhost(p);
                hidePlayers(p, currentOpacity.getOrDefault(p.getUniqueId(), Opacity.UNLIMITED));
            }
        }
        clickCooldown = System.currentTimeMillis();
    }

    /**
     *
     * @param target to give the item to
     */
    protected void giveTransparencyItem(Player target) {
        Dye dye = new Dye(DyeColor.GRAY);
        ItemStack im = dye.toItemStack(1);
        im = addNBT(im);
        ItemMeta meta = im.getItemMeta();
        meta.setDisplayName(color("&aVisibility Mode"));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7This item is used to decide how many &7players you can see!"));
        lore.add(" ");
        lore.add(color("&eLeft-Click to cycle forward!"));
        lore.add(color("&eLeft-Click to cycle backwards!"));
        meta.setLore(lore);
        im.setItemMeta(meta);

        target.getInventory().addItem(im);
    }

    /**
     *
     * @param item to apply NBT to
     * @return a itemStack with the correct NBT tag applied
     */
    protected ItemStack addNBT(ItemStack item) {
        net.minecraft.server.v1_8_R3.ItemStack craftItem = CraftItemStack.asNMSCopy(item);
        NBTTagCompound craftTag = new NBTTagCompound();
        craftTag.set("visMode", new NBTTagString("1b"));
        craftItem.setTag(craftTag);

        return CraftItemStack.asBukkitCopy(craftItem);
    }

    /**
     * Opacity Enum to house values for how many players will be shown.
     */

    enum Opacity {
        OFF(0),
        TWENTY_FIVE(25),
        FIFTY(50),
        ONE_HUNDRED(100),
        UNLIMITED(500);


        private final Integer value;

        Opacity(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
