package twitch.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;

import twitch.TwitchStreamPlugin;
import twitch.model.StreamerInfo;
import twitch.service.StreamerManager;

import java.util.UUID;

/* Команда /стрим и обработка событий входа */
public class TwitchCommand implements CommandExecutor, Listener {
    private final TwitchStreamPlugin plugin;
    private final StreamerManager streamerManager;

    public TwitchCommand(TwitchStreamPlugin plugin, StreamerManager streamerManager) {
        this.plugin = plugin;
        this.streamerManager = streamerManager;
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
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
                streamerManager.removeStreamer(name);
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
                streamerManager.removeStreamer(player.getName());
                player.sendMessage("§aВы остановили стрим. Группа снята.");
                yield true;
            }
            case "список" -> {
                sender.sendMessage(plugin.getMessage("streamer_list_header"));
                for (StreamerInfo s : streamerManager.getStreamers()) {
                    sender.sendMessage(plugin.getMessage("streamer_list_entry", s.mcName, s.url, s.twitchName));
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
        if (streamer != null) {
            Boolean isLive = streamerManager.getStreamerLiveStatus().get(streamer.twitchName.toLowerCase());
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                UUID uuid = player.getUniqueId();
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
        }
    }
}