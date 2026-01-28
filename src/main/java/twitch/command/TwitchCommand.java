package twitch.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

import twitch.TwitchStreamPlugin;
import twitch.model.StreamerInfo;
import twitch.service.StreamerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/* Команда /стрим и обработка событий входа */
public class TwitchCommand implements CommandExecutor, Listener {
    private final TwitchStreamPlugin plugin;
    private final StreamerManager streamerManager;

    private static final String STREAMER_MENU_TITLE_PREFIX = "§8Стримеры";
    private final NamespacedKey streamerUrlKey;
    private final NamespacedKey streamerTwitchKey;
    private final NamespacedKey menuActionKey;

    public TwitchCommand(TwitchStreamPlugin plugin, StreamerManager streamerManager) {
        this.plugin = plugin;
        this.streamerManager = streamerManager;
        this.streamerUrlKey = new NamespacedKey(plugin, "streamer_url");
        this.streamerTwitchKey = new NamespacedKey(plugin, "streamer_twitch");
        this.menuActionKey = new NamespacedKey(plugin, "menu_action");
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
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

    private void openStreamerListMenu(Player player, int page) {
        List<StreamerInfo> snapshot;
        synchronized (streamerManager.getStreamers()) {
            snapshot = new ArrayList<>(streamerManager.getStreamers());
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
                meta.setLore(java.util.List.of(
                        "§7" + s.mcName,
                        "§eПерейти на страницу Twitch"
                ));
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

    // Обработка команды /стрим
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eИспользуйте: /стрим список или /стрим <ник>");
            return true;
        }

        String action = args[0].toLowerCase();

        //noinspection ConstantConditions
        return switch (action) {
            case "добавить" -> {
                if (args.length != 4) {
                    sender.sendMessage(plugin.getMessage("usage"));
                    yield true;
                }
                if (!sender.isOp() && !sender.hasPermission("twitch.stream.admin")) {
                    sender.sendMessage(plugin.getMessage("no_add_permission"));
                    yield true;
                }
                String mcName = args[1];
                String twitchName = args[2];
                String url = args[3];
                if (!(url.startsWith("https://www.twitch.tv/") || url.startsWith("https://twitch.tv/"))) {
                    sender.sendMessage(plugin.getMessage("invalid_link"));
                    yield true;
                }
                // соответствия ника Twitch и ника в ссылке
                String nameFromUrl = url.replace("https://www.twitch.tv/", "")
                                       .replace("https://twitch.tv/", "")
                                       .replaceAll("/", "");
                if (!twitchName.equalsIgnoreCase(nameFromUrl)) {
                    sender.sendMessage("§cОшибка: ник Twitch в поле и в ссылке не совпадают. Проверьте ввод.");
                    yield true;
                }
                boolean exists = streamerManager.getStreamers().stream().anyMatch(s ->
                        s.mcName.equalsIgnoreCase(mcName) ||
                                s.twitchName.equalsIgnoreCase(twitchName) ||
                                s.url.equalsIgnoreCase(url));
                if (exists) {
                    sender.sendMessage("§cСтример с таким ником, Twitch-ником или ссылкой уже есть в списке.");
                    yield true;
                }
                streamerManager.addStreamer(mcName, twitchName, url, "");
                sender.sendMessage(plugin.getMessage("streamer_added", mcName, url, twitchName));
                yield true;
            }
            case "удалить" -> {
                if (args.length != 2) {
                    sender.sendMessage(plugin.getMessage("usage"));
                    yield true;
                }
                if (!sender.isOp() && !sender.hasPermission("twitch.stream.admin")) {
                    sender.sendMessage(plugin.getMessage("no_remove_permission"));
                    yield true;
                }
                String name = args[1];
                StreamerInfo toRemove = streamerManager.getStreamers().stream()
                        .filter(s -> s.mcName.equalsIgnoreCase(name) || s.twitchName.equalsIgnoreCase(name))
                        .findFirst().orElse(null);
                streamerManager.removeStreamer(name);

                String targetMcName = toRemove != null ? toRemove.mcName : name;
                LuckPerms luckPerms = plugin.getLuckPerms();
                if (luckPerms != null) {
                    Player online = org.bukkit.Bukkit.getPlayerExact(targetMcName);
                    if (online != null) {
                        UUID uuid = online.getUniqueId();
                        luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                            user.data().clear(node -> node instanceof InheritanceNode &&
                                    ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                            luckPerms.getUserManager().saveUser(user);
                        });
                    } else {
                        luckPerms.getUserManager().lookupUniqueId(targetMcName).thenAcceptAsync(uuid -> {
                            if (uuid == null) {
                                return;
                            }
                            luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                                user.data().clear(node -> node instanceof InheritanceNode &&
                                        ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                                luckPerms.getUserManager().saveUser(user);
                            });
                        });
                    }
                }
                sender.sendMessage(plugin.getMessage("streamer_removed", name, "", ""));
                yield true;
            }
            case "стоп" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(plugin.getMessage("only_player"));
                    yield true;
                }
                Player player = (Player) sender;
                if (!player.hasPermission("twitch.stream")) {
                    player.sendMessage(plugin.getMessage("no_permission"));
                    yield true;
                }
                LuckPerms luckPerms = plugin.getLuckPerms();
                if (luckPerms != null) {
                    luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                        user.data().clear(node -> node instanceof InheritanceNode &&
                                ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                        luckPerms.getUserManager().saveUser(user);
                    });
                }
                StreamerInfo me = streamerManager.getStreamers().stream()
                        .filter(s -> s.mcName.equalsIgnoreCase(player.getName()))
                        .findFirst().orElse(null);
                if (me != null) {
                    streamerManager.getStreamerLiveStatus().put(me.twitchName.toLowerCase(), false);
                }
                player.sendMessage("§aВы остановили стрим. Группа снята.");
                yield true;
            }
            case "список" -> {
                if (sender instanceof Player player) {
                    openStreamerListMenu(player, 0);
                    yield true;
                }
                sender.sendMessage(plugin.getMessage("streamer_list_header"));
                for (StreamerInfo s : streamerManager.getStreamers()) {
                    sender.sendMessage(plugin.getMessage("streamer_list_entry", s.mcName, s.url, s.twitchName));
                }
                yield true;
            }
            case "онлайн" -> {
                Map<String, Boolean> liveStatus = streamerManager.getStreamerLiveStatus();
                List<StreamerInfo> liveStreamers = new ArrayList<>();
                synchronized (streamerManager.getStreamers()) {
                    for (StreamerInfo s : streamerManager.getStreamers()) {
                        String mcName = s.mcName == null ? "" : s.mcName.trim();
                        boolean isOnline = org.bukkit.Bukkit.getOnlinePlayers().stream()
                                .anyMatch(p -> p.getName().equalsIgnoreCase(mcName));
                        if (isOnline && liveStatus.getOrDefault(s.twitchName.toLowerCase(), false)) {
                            liveStreamers.add(s);
                        }
                    }
                }

                if (sender instanceof Player player) {
                    if (liveStreamers.isEmpty()) {
                        player.sendMessage(plugin.getMessage("streamers_online_none"));
                        yield true;
                    }
                    player.sendMessage(plugin.getMessage("streamers_online_header"));
                    for (StreamerInfo s : liveStreamers) {
                        player.sendMessage(plugin.getMessage("streamers_online_entry", s.mcName, s.url, s.twitchName));
                        TextComponent link = new TextComponent(s.url);
                        link.setColor(ChatColor.BLUE);
                        link.setUnderlined(true);
                        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, s.url));
                        player.spigot().sendMessage(link);
                    }
                    yield true;
                }

                if (liveStreamers.isEmpty()) {
                    sender.sendMessage(plugin.getMessage("streamers_online_none"));
                    yield true;
                }
                sender.sendMessage(plugin.getMessage("streamers_online_header"));
                for (StreamerInfo s : liveStreamers) {
                    sender.sendMessage(plugin.getMessage("streamers_online_entry", s.mcName, s.url, s.twitchName) + " " + s.url);
                }
                yield true;
            }
            case "reload" -> {
                if (sender instanceof Player player) {
                    if (!player.isOp() && !player.hasPermission("twitch.stream.admin")) {
                        player.sendMessage(plugin.getMessage("no_reload_permission"));
                        yield true;
                    }
                }
                plugin.reloadTwitchConfig();
                sender.sendMessage(plugin.getMessage("reload_success"));
                yield true;
            }
            case "статус" -> {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(plugin.getMessage("only_player"));
                    yield true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage(plugin.getMessage("usage"));
                    yield true;
                }
                String name = args[1];
                StreamerInfo streamer = streamerManager.getStreamers().stream()
                        .filter(s -> s.mcName.equalsIgnoreCase(name) || s.twitchName.equalsIgnoreCase(name))
                        .findFirst().orElse(null);
                if (streamer != null) {
                    Boolean isLive = streamerManager.getStreamerLiveStatus().get(streamer.twitchName.toLowerCase());
                    if (Boolean.TRUE.equals(isLive)) {
                        player.sendMessage(plugin.getMessage("streamer_status_online", streamer.mcName, streamer.twitchName));
                    } else {
                        player.sendMessage(plugin.getMessage("streamer_status_offline", streamer.mcName, streamer.twitchName));
                    }
                } else {
                    player.sendMessage(plugin.getMessage("streamer_not_found"));
                }
                yield true;
            }
            default -> {
                // Проверяем, не ссылка ли это
                if (args[0].startsWith("https://www.twitch.tv/") || args[0].startsWith("https://twitch.tv/")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(plugin.getMessage("only_player"));
                        yield true;
                    }
                    Player player = (Player) sender;
                    if (!player.hasPermission("twitch.stream")) {
                        player.sendMessage(plugin.getMessage("no_permission"));
                        yield true;
                    }
                    String url = args[0];
                    String desc = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
                    String mcName = player.getName();
                    String twitchName = url.replace("https://www.twitch.tv/", "").replace("https://twitch.tv/", "").replaceAll("/", "");
                    LuckPerms luckPerms = plugin.getLuckPerms();
                    if (luckPerms != null) {
                        luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                            user.data().add(InheritanceNode.builder(plugin.getTwitchGroup()).build());
                            luckPerms.getUserManager().saveUser(user);
                        });
                    }
                    String msg = plugin.getMessage("stream_manual_broadcast", mcName, url, desc);
                    for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        p.sendMessage(msg);
                    }
                    yield true;
                }
                // Если не ссылка, то показать ссылку на стрим по нику
                if (!(sender instanceof Player)) {
                    sender.sendMessage(plugin.getMessage("only_player"));
                    yield true;
                }
                Player player = (Player) sender;
                String searchName = args[0];
                StreamerInfo streamer = streamerManager.getStreamers().stream()
                        .filter(s -> s.mcName.equalsIgnoreCase(searchName) || s.twitchName.equalsIgnoreCase(searchName))
                        .findFirst().orElse(null);
                if (streamer != null) {
                    player.sendMessage(plugin.getMessage("show_streamer", streamer.twitchName, streamer.url));
                } else {
                    player.sendMessage(plugin.getMessage("streamer_not_found"));
                }
                yield true;
            }
        };
    }

    // Проверка LuckPerms есть ли группа для выдачи префикса
    public void isStreaming(Player player, java.util.function.Consumer<Boolean> callback) {
        LuckPerms luckPerms = plugin.getLuckPerms();
        if (luckPerms != null) {
            luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                boolean streaming = user.getNodes().stream().anyMatch(node ->
                        node instanceof InheritanceNode &&
                                ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                callback.accept(streaming);
            });
        } else {
            callback.accept(false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String mcName = player.getName();
        StreamerInfo streamer = streamerManager.getStreamers().stream()
            .filter(s -> s.mcName.equalsIgnoreCase(mcName))
            .findFirst().orElse(null);
        LuckPerms luckPerms = plugin.getLuckPerms();
        if (luckPerms == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (streamer == null) {
            luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                user.data().clear(node -> node instanceof InheritanceNode &&
                        ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                luckPerms.getUserManager().saveUser(user);
            });
            return;
        }

        Boolean isLive = streamerManager.getStreamerLiveStatus().get(streamer.twitchName.toLowerCase());
        luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
            if (Boolean.TRUE.equals(isLive)) {
                plugin.getLogger().info("[DEBUG] Выдаём группу " + plugin.getTwitchGroup() + " игроку UUID=" + uuid);
                user.data().add(InheritanceNode.builder(plugin.getTwitchGroup()).build());
            } else {
                plugin.getLogger().info("[DEBUG] Снимаем группу " + plugin.getTwitchGroup() + " с игрока UUID=" + uuid);
                user.data().clear(node -> node instanceof InheritanceNode &&
                        ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
            }
            luckPerms.getUserManager().saveUser(user);
        });
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
                openStreamerListMenu(player, holder.page - 1);
                return;
            }
            if (action.equalsIgnoreCase("next")) {
                openStreamerListMenu(player, holder.page + 1);
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