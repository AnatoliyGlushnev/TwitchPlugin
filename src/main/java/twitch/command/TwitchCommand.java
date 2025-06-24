package twitch.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.HandlerList;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

import twitch.TwitchStreamPlugin;
import twitch.model.StreamerInfo;
import twitch.service.StreamerManager;

import java.util.UUID;
import java.util.function.Consumer;

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
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMessage("only_player"));
            return true;
        }
        Player player = (Player) sender;
        // /стрим стоп
        if (args.length == 1 && args[0].equalsIgnoreCase("стоп")) {
            if (!player.hasPermission("twitch.stream")) {
                player.sendMessage(plugin.getMessage("no_permission"));
                return true;
            }
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                    user.data().clear(node -> node instanceof InheritanceNode &&
                            ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                    luckPerms.getUserManager().saveUser(user);
                });
            }
            // Можно также удалить из списка стримеров, если требуется:
            streamerManager.removeStreamer(player.getName());
            player.sendMessage("§aВы остановили стрим. Группа снята.");
            return true;
        }
        // /стрим <ссылка> <описание>
        if (args.length >= 1 && (args[0].startsWith("https://www.twitch.tv/") || args[0].startsWith("https://twitch.tv/"))) {
            if (!player.hasPermission("twitch.stream")) {
                player.sendMessage(plugin.getMessage("no_permission"));
                return true;
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
            return true;
        }
        if (args.length == 1 && !args[0].equalsIgnoreCase("стоп") && !args[0].equalsIgnoreCase("список") && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("статус") && !args[0].equalsIgnoreCase("добавить") && !args[0].equalsIgnoreCase("удалить")) {
            // /стрим <ник> — показать ссылку на стрим
            String searchName = args[0];
            StreamerInfo streamer = streamerManager.getStreamers().stream()
                    .filter(s -> s.mcName.equalsIgnoreCase(searchName) || s.twitchName.equalsIgnoreCase(searchName))
                    .findFirst().orElse(null);
            if (streamer != null) {
                player.sendMessage(plugin.getMessage("show_streamer", streamer.twitchName, streamer.url));
            } else {
                player.sendMessage(plugin.getMessage("streamer_not_found"));
            }
            return true;
        } else {
            if (!player.hasPermission("twitch.stream")) {
                player.sendMessage(plugin.getMessage("no_permission"));
                return true;
            }
        }
        if (args.length < 1) {
            player.sendMessage(plugin.getMessage("usage"));
            return true;
        }
        // /стрим добавить <mc_ник> <twitch_ник> <ссылка>
        if (args.length == 4 && args[0].equalsIgnoreCase("добавить")) {
            if (!player.isOp() && !player.hasPermission("twitch.stream.admin")) {
                player.sendMessage(plugin.getMessage("no_add_permission"));
                return true;
            }
            String mcName = args[1];
            String twitchName = args[2];
            String url = args[3];
            if (!(url.startsWith("https://www.twitch.tv/") || url.startsWith("https://twitch.tv/"))) {
                player.sendMessage(plugin.getMessage("invalid_link"));
                return true;
            }
            // проверека дублоирование
            boolean exists = streamerManager.getStreamers().stream().anyMatch(s ->
                s.mcName.equalsIgnoreCase(mcName) ||
                s.twitchName.equalsIgnoreCase(twitchName) ||
                s.url.equalsIgnoreCase(url)
            );
            if (exists) {
                player.sendMessage("§cСтример с таким ником, Twitch-ником или ссылкой уже есть в списке.");
                return true;
            }
            streamerManager.addStreamer(mcName, twitchName, url, "");
            player.sendMessage(plugin.getMessage("streamer_added", mcName, url, twitchName));
            return true;
        }
        // /стрим удалить <ник>
        if (args.length == 2 && args[0].equalsIgnoreCase("удалить")) {
            if (!player.isOp() && !player.hasPermission("twitch.stream.admin")) {
                player.sendMessage(plugin.getMessage("no_remove_permission"));
                return true;
            }
            String name = args[1];
            streamerManager.removeStreamer(name);
            player.sendMessage(plugin.getMessage("streamer_removed", name, "", ""));
            return true;
        }
        // /стрим список
        if (args.length == 1 && args[0].equalsIgnoreCase("список")) {
            player.sendMessage(plugin.getMessage("streamer_list_header"));
            for (StreamerInfo s : streamerManager.getStreamers()) {
                player.sendMessage(plugin.getMessage("streamer_list_entry", s.mcName, s.url, s.twitchName));
            }
            return true;
        }
        // /стрим reload - перезагружка конфига
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.isOp() && !player.hasPermission("twitch.stream.admin")) {
                player.sendMessage(plugin.getMessage("no_reload_permission"));
                return true;
            }
            plugin.reloadTwitchConfig();
            player.sendMessage(plugin.getMessage("reload_success"));
            return true;
        }
        // /стрим статус <ник>
        if (args.length == 2 && args[0].equalsIgnoreCase("статус")) {
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
            return true;
        }
        player.sendMessage(plugin.getMessage("usage"));
        return true;
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