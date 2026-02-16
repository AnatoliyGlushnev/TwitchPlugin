package twitch.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import twitch.TwitchStreamPlugin;
import twitch.model.StreamerInfo;
import twitch.storage.StreamerLastStreamRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TwitchStreamerMenu implements Listener {
    private final TwitchStreamPlugin plugin;
    private final twitch.service.StreamerManager streamerManager;

    private static final SimpleDateFormat LAST_STREAM_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private static final String STREAMER_MENU_TITLE_PREFIX = "§8Стримеры";
    private final NamespacedKey streamerUrlKey;
    private final NamespacedKey streamerTwitchKey;
    private final NamespacedKey menuActionKey;

    public TwitchStreamerMenu(TwitchStreamPlugin plugin, twitch.service.StreamerManager streamerManager) {
        this.plugin = plugin;
        this.streamerManager = streamerManager;
        this.streamerUrlKey = new NamespacedKey(plugin, "streamer_url");
        this.streamerTwitchKey = new NamespacedKey(plugin, "streamer_twitch");
        this.menuActionKey = new NamespacedKey(plugin, "menu_action");
    }

    private static final class StreamerMenuHolder implements InventoryHolder {
        private final int page;

        private StreamerMenuHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private String formatLastStreamLine(Long lastStreamMs) {
        if (lastStreamMs == null || lastStreamMs <= 0L) {
            return null;
        }
        synchronized (LAST_STREAM_FORMAT) {
            return "§7Последний стрим: §f" + LAST_STREAM_FORMAT.format(new Date(lastStreamMs));
        }
    }

    public void open(Player player, int page) {
        List<StreamerInfo> snapshot;
        synchronized (streamerManager.getStreamers()) {
            snapshot = new ArrayList<>(streamerManager.getStreamers());
        }

        Map<String, Long> lastByMcName = new HashMap<>();
        StreamerLastStreamRepository repo = plugin.getLastStreamRepository();
        if (repo != null) {
            try {
                for (Map.Entry<String, Long> e : repo.findAllForServer(plugin.getServerId()).entrySet()) {
                    if (e.getKey() == null) {
                        continue;
                    }
                    lastByMcName.put(e.getKey().toLowerCase(), e.getValue());
                }
            } catch (Exception ignored) {
            }
        }

        int perPage = 45;
        int totalPages = Math.max(1, (int) Math.ceil(snapshot.size() / (double) perPage));
        int safePage = Math.min(Math.max(page, 0), totalPages - 1);

        Inventory inv = org.bukkit.Bukkit.createInventory(
                new StreamerMenuHolder(safePage),
                54,
                STREAMER_MENU_TITLE_PREFIX + " §7(" + (safePage + 1) + "/" + totalPages + ")"
        );

        int start = safePage * perPage;
        int end = Math.min(start + perPage, snapshot.size());
        for (int i = start; i < end; i++) {
            StreamerInfo s = snapshot.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = head.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                meta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(s.mcName));
                meta.setDisplayName("§d" + s.twitchName);
                String lastLine = formatLastStreamLine(lastByMcName.get(s.mcName == null ? "" : s.mcName.toLowerCase()));
                List<String> lore = new ArrayList<>();
                lore.add("§7" + s.mcName);
                lore.add("§eПерейти на страницу Twitch");
                if (lastLine != null) {
                    lore.add(lastLine);
                }
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(streamerUrlKey, PersistentDataType.STRING, s.url);
                meta.getPersistentDataContainer().set(streamerTwitchKey, PersistentDataType.STRING, s.twitchName);
                head.setItemMeta(meta);
            }

            inv.setItem(i - start, head);
        }

        if (safePage > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§aПредыдущая страница");
                meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, "prev");
                prev.setItemMeta(meta);
            }
            inv.setItem(45, prev);
        }

        if (safePage < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§aСледующая страница");
                meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, "next");
                next.setItemMeta(meta);
            }
            inv.setItem(53, next);
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onStreamerMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof StreamerMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }

        String action = meta.getPersistentDataContainer().get(menuActionKey, PersistentDataType.STRING);
        if (action != null) {
            if (action.equalsIgnoreCase("prev")) {
                open(player, holder.page - 1);
                return;
            }
            if (action.equalsIgnoreCase("next")) {
                open(player, holder.page + 1);
                return;
            }
        }

        String url = meta.getPersistentDataContainer().get(streamerUrlKey, PersistentDataType.STRING);
        String twitchName = meta.getPersistentDataContainer().get(streamerTwitchKey, PersistentDataType.STRING);
        if (url == null || url.isEmpty()) {
            return;
        }

        player.closeInventory();
        String showMsg = plugin.getMessage("show_streamer", twitchName, url);
        if (showMsg != null && !showMsg.isEmpty() && !showMsg.equals(url)) {
            player.sendMessage(showMsg);
        } else {
            TextComponent link = new TextComponent(url);
            link.setColor(ChatColor.BLUE);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            player.spigot().sendMessage(link);
        }
    }
}
