package twitch;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.HandlerList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.util.Tristate;

public class TwitchCommand implements CommandExecutor, Listener {

    @org.bukkit.event.EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        String mcName = player.getName();
        TwitchStreamPlugin.StreamerInfo streamer = plugin.getStreamers().stream()
            .filter(s -> s.mcName.equalsIgnoreCase(mcName))
            .findFirst().orElse(null);
        if (streamer != null) {
            Boolean isLive = plugin.getStreamerLiveStatus().get(streamer.twitchName.toLowerCase());
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                java.util.UUID uuid = player.getUniqueId();
                luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                    if (Boolean.TRUE.equals(isLive)) {
                        plugin.getLogger().info("[DEBUG] twitch_on игроку UUID=" + uuid);
                        user.data().add(net.luckperms.api.node.types.InheritanceNode.builder(plugin.getTwitchGroup()).build());
                    } else {
                        plugin.getLogger().info("[DEBUG] Снимаем группу twitch_on с игрока UUID=" + uuid);
                        user.data().clear(node -> node instanceof net.luckperms.api.node.types.InheritanceNode &&
                                ((net.luckperms.api.node.types.InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                    }
                    luckPerms.getUserManager().saveUser(user);
                });
            }
        }
    }
    // Проверка LuckPerms есть ли группа twitch_on
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
    private final TwitchStreamPlugin plugin;
    // хранение активных стримов: UUID -> Twitch URL
    private final Map<UUID, String> activeStreams = new HashMap<>();
    public TwitchCommand(TwitchStreamPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMessage("only_player"));
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 1 && !args[0].equalsIgnoreCase("стоп") && !args[0].equalsIgnoreCase("список") && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("статус") && !args[0].equalsIgnoreCase("добавить") && !args[0].equalsIgnoreCase("удалить")) {
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
                player.sendMessage("§cУ вас нет прав на добавление стримеров!");
                return true;
            }
            String mcName = args[1];
            String twitchName = args[2];
            String url = args[3];
            boolean exists = plugin.getStreamers().stream().anyMatch(s -> s.mcName.equalsIgnoreCase(mcName));
            if (exists) {
                player.sendMessage("§cСтример с ником '" + mcName + "' уже есть в списке!");
                return true;
            }

            if (!(url.startsWith("https://www.twitch.tv/") || url.startsWith("https://twitch.tv/"))) {
                player.sendMessage("§cВведите корректную ссылку на Twitch!");
                return true;
            }
            plugin.addStreamer(mcName, twitchName, url);
            player.sendMessage("§aСтример " + mcName + " (Twitch: " + twitchName + ") добавлен!");
            return true;
        }
        // Список стримеров: /стрим список
        if (args.length == 1 && args[0].equalsIgnoreCase("список")) {
            java.util.List<TwitchStreamPlugin.StreamerInfo> streamers = plugin.getStreamers();
            if (streamers.isEmpty()) {
                player.sendMessage("§eСписок стримеров пуст!");
            } else {
                player.sendMessage("§6Отслеживаемые стримеры:");
                for (TwitchStreamPlugin.StreamerInfo info : streamers) {
                    net.md_5.bungee.api.chat.TextComponent msg = new net.md_5.bungee.api.chat.TextComponent("§f- §a" + info.mcName + " §7(Twitch: §b" + info.twitchName + "§7, ");
                    net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(info.url);
                    link.setColor(net.md_5.bungee.api.ChatColor.BLUE);
                    link.setUnderlined(true);
                    link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, info.url));
                    msg.addExtra(link);
                    msg.addExtra("§7)");
                    player.spigot().sendMessage(msg);
                }
            }
            return true;
        }
        // /стрим стоп — завершить стрим
        if (args.length == 1 && args[0].equalsIgnoreCase("стоп")) {
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                    boolean streaming = user.getNodes().stream().anyMatch(node ->
                            node instanceof InheritanceNode &&
                                    ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                    if (streaming) {
                        InheritanceNode node = InheritanceNode.builder(plugin.getTwitchGroup()).build();
                        user.data().remove(node);
                        luckPerms.getUserManager().saveUser(user);
                        player.sendMessage(plugin.getMessage("stop"));
                    } else {
                        player.sendMessage(plugin.getMessage("not_streaming"));
                    }
                });
            } else {
                player.sendMessage(plugin.getMessage("not_streaming"));
            }
            return true;
        }
        // /стрим <ник> — показать ссылку на стрим
        if (args.length == 1) {
            String searchName = args[0];
            TwitchStreamPlugin.StreamerInfo streamer = plugin.getStreamers().stream()
                    .filter(s -> s.mcName.equalsIgnoreCase(searchName) || s.twitchName.equalsIgnoreCase(searchName))
                    .findFirst().orElse(null);
            if (streamer != null) {
                player.sendMessage("§aTwitch канал §f" + streamer.twitchName + ": §9§n" + streamer.url);
            } else {
                player.sendMessage("§cСтример не найден!");
            }
            return true;
        }
        // /стрим статус <ник> тестовая чтобы тестить
        if (args.length == 2 && args[0].equalsIgnoreCase("статус")) {
            String twitchName = args[1].toLowerCase();
            Boolean live = plugin.getStreamerLiveStatus().get(twitchName);
            if (live == null) {
                player.sendMessage("§eСтример '" + twitchName + "' не найден в списке!");
            } else if (live) {
                player.sendMessage("§aСтример '" + twitchName + "' сейчас В ЭФИРЕ!");
            } else {
                player.sendMessage("§cСтример '" + twitchName + "' сейчас оффлайн.");
            }
            return true;
        }
        // /стрим удалить <ник>
        if (args.length == 2 && args[0].equalsIgnoreCase("удалить")) {
            if (!player.isOp() && !player.hasPermission("twitch.stream.admin")) {
                player.sendMessage("§cУ вас нет прав на удаление стримеров!");
                return true;
            }
            String name = args[1].toLowerCase();
            plugin.removeStreamer(name);
            player.sendMessage("§aСтример " + name + " удалён!");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.isOp() && !player.hasPermission("twitch.stream.reload")) {
                player.sendMessage("§cУ вас нет прав на перезагрузку плагина!");
                return true;
            }
            plugin.reloadTwitchConfig();
            player.sendMessage("§aПлагин TwitchStream успешно перезагружен!");
            return true;
        } else if (args.length == 1 && args[0].equalsIgnoreCase("стоп")) {
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                    boolean streaming = user.getNodes().stream().anyMatch(node ->
                            node instanceof InheritanceNode &&
                                    ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                    if (streaming) {
                        InheritanceNode node = InheritanceNode.builder(plugin.getTwitchGroup()).build();
                        user.data().remove(node);
                        luckPerms.getUserManager().saveUser(user);
                        player.sendMessage(plugin.getMessage("stop"));
                    } else {
                        player.sendMessage(plugin.getMessage("not_streaming"));
                    }
                });
            } else {
                player.sendMessage(plugin.getMessage("not_streaming"));
            }
            return true;
        } else if (args.length >= 1) {
            String twitchLink = args[0];
            if (!(twitchLink.startsWith("https://www.twitch.tv/") || twitchLink.startsWith("https://twitch.tv/"))) {
                player.sendMessage(plugin.getMessage("invalid_link"));
                return true;
            }
            String description = "";
            if (args.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    sb.append(args[i]);
                    if (i < args.length - 1) sb.append(" ");
                }
                description = sb.toString();
            }
            // выдача twitch_on LuckPerms
            LuckPerms luckPerms = plugin.getLuckPerms();
            if (luckPerms != null) {
                luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
                    boolean streaming = user.getNodes().stream().anyMatch(node ->
                            node instanceof InheritanceNode &&
                                    ((InheritanceNode) node).getGroupName().equalsIgnoreCase(plugin.getTwitchGroup()));
                    if (!streaming) {
                        InheritanceNode node = InheritanceNode.builder(plugin.getTwitchGroup()).build();
                        user.data().add(node);
                        luckPerms.getUserManager().saveUser(user);
                    }
                });
            }
            String msg = "§cСтример §f" + player.getName() + " §cначал стрим: §9§n" + twitchLink;
            if (!description.isEmpty()) {
                msg += " §7(" + description + ")";
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(msg);
            }

            return true;
        } else {
            player.sendMessage(plugin.getMessage("usage"));
            return true;
        }
    }
}
