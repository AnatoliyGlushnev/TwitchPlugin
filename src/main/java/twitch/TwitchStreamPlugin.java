package twitch;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.event.HandlerList;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;

import twitch.model.StreamerInfo;
import twitch.service.StreamerManager;
import twitch.service.TwitchApiService;
import twitch.service.TwitchAnnounceTask;
import twitch.command.TwitchCommand;

public class TwitchStreamPlugin extends JavaPlugin {

    private static java.util.Map<String, Long> lastErrorLogTime = new java.util.HashMap<>();
    private java.util.concurrent.ExecutorService executorService; //асинхронная задач
    private FileConfiguration config;
    private TwitchCommand twitchCommand;
    private LuckPerms luckPerms;
    private String clientId;
    private String oauthToken;
    private String twitchGroup; 
    private TwitchApiService twitchApiService;
    private StreamerManager streamerManager;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask announceTask = null;

    public String getTwitchGroup() {
        return twitchGroup;
    }

    @Override
    public void onEnable() {
        this.executorService = java.util.concurrent.Executors.newCachedThreadPool();
        saveDefaultConfig();
        this.config = getConfig();
        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        this.streamerManager = new StreamerManager(config);
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.twitchApiService = new TwitchApiService(clientId, oauthToken, getLogger());
        this.twitchApiService.validateConnection();
        this.twitchGroup = config.getString("group", "twitch_on"); // по умолчанию
        if (this.luckPerms == null) {
            getLogger().severe("LuckPerms не найден! Плагин не сможет выдавать группы.");
        }
        // анонс стримеров
        startAnnounceTask();
        reloadPlugin();
        getLogger().info("[TWITCH PLUGIN ANNONCE] TwitchStreamPlugin работает.");
    }

    public String getMessage(String key) {
        String msg = config.getString("messages." + key, "");
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public void reloadTwitchConfig() {
        reloadConfig();
        this.config = getConfig();
        reloadPlugin();
    }

    public void reloadPlugin() {
        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        this.twitchGroup = config.getString("twitch.group", "twitch_on"); // Группа для выдачи, ПО УМОЛЧАНИЮ twitch_on
        this.streamerManager = new StreamerManager(config);
        if (twitchCommand != null) {
            HandlerList.unregisterAll(twitchCommand);
        }
        twitchCommand = new TwitchCommand(this, streamerManager);
        getCommand("стрим").setExecutor(twitchCommand);
        startStreamChecker();
        startAnnounceTask();
    }
    private void startAnnounceTask() {
        long announcePeriod = config.getLong("twitch.announce_period", 72000L);
        if (announceTask != null) {
            announceTask.cancel();
        }
        announceTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
            this,
            task -> new TwitchAnnounceTask(this, streamerManager).run(),
            announcePeriod, // initial delay
            announcePeriod  // period
        );
    }

    private void startStreamChecker() {
        long checkPeriod = config.getLong("twitch.stream_check_period", 1200L);
        getServer().getGlobalRegionScheduler().runAtFixedRate(
            this,
            task -> {
                for (StreamerInfo streamer : streamerManager.getStreamers()) {
                    checkTwitchStream(streamer);
                }
            },
            1L,
            checkPeriod
        );
    }

    private void checkTwitchStream(StreamerInfo streamer) {
        executorService.submit(() -> {
            try {
                String response = twitchApiService.sendGetRequest("https://api.twitch.tv/helix/streams?user_login=" + streamer.twitchName);
                boolean isLive = response.contains("\"type\":\"live\"");
                boolean wasLive = streamerManager.getStreamerLiveStatus().getOrDefault(streamer.twitchName.toLowerCase(), false);

                streamerManager.getStreamerLiveStatus().put(streamer.twitchName.toLowerCase(), isLive);

                if (isLive && !wasLive) {
                    getLogger().info("Стрим начался для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");
                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                        org.bukkit.entity.Player streamerPlayer = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                        if (streamerPlayer != null) {
                            String streamMsg = getMessage("stream_start_broadcast", streamer.mcName, streamer.url, streamer.twitchName);
                            net.md_5.bungee.api.chat.TextComponent msg = new net.md_5.bungee.api.chat.TextComponent(streamMsg.replace("{link}", ""));
                            net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(streamer.url);
                            link.setColor(net.md_5.bungee.api.ChatColor.BLUE);
                            link.setUnderlined(true);
                            link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, streamer.url));
                            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                                p.sendMessage(msg.getText());
                            }
                            // префикс через LuckPerms
                            LuckPerms luckPerms = getLuckPerms();
                            if (luckPerms != null) {
                                java.util.UUID uuid = streamerPlayer.getUniqueId();
                                luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                                    user.data().add(net.luckperms.api.node.types.InheritanceNode.builder(getTwitchGroup()).build());
                                    luckPerms.getUserManager().saveUser(user);
                                });
                            }
                        }
                    });
                } else if (!isLive && wasLive) {
                    getLogger().info("Стрим завершён для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");
                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                        LuckPerms luckPerms = getLuckPerms();
                        if (luckPerms != null) {
                            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                            if (player != null) {
                                java.util.UUID uuid = player.getUniqueId();
                                luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user ->
                                {
                                    user.data().clear(node -> node instanceof net.luckperms.api.node.types.InheritanceNode &&
                                            ((net.luckperms.api.node.types.InheritanceNode) node).getGroupName().equalsIgnoreCase(getTwitchGroup()));
                                    luckPerms.getUserManager().saveUser(user);
                                });
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                if (cause instanceof java.net.ConnectException ||
    cause instanceof java.net.UnknownHostException ||
    cause instanceof java.net.SocketTimeoutException ||
    cause instanceof javax.net.ssl.SSLException ||
    msg.toLowerCase().contains("timed out") ||
    msg.toLowerCase().contains("connection refused")) {
    // Rate limiting
    String errorKey = streamer.twitchName.toLowerCase() + ":" + cause.getClass().getSimpleName();
    long now = System.currentTimeMillis();
    synchronized (TwitchStreamPlugin.class) {
        if (lastErrorLogTime == null) lastErrorLogTime = new java.util.HashMap<>();
        Long last = lastErrorLogTime.get(errorKey);
        if (last == null || now - last > 60_000) {
            getLogger().info("[TwitchStream] Не удалось проверить Twitch для " + streamer.twitchName + ": " + msg);
            lastErrorLogTime.put(errorKey, now);
        }
    }

                } else {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    getLogger().warning("Ошибка при проверке Twitch для " + streamer.twitchName + ": " + sw.toString());
                }
            }
        });
    }

    public String getMessage(String key, String player, String link) {
        return getMessage(key, player, link, "");
    }
    
    @Override
    public void onDisable() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (announceTask != null) {
            announceTask.cancel();
        }
        getLogger().info("[TWITCH] Плагин успешно выгружен.");
    }
    
    public String getMessage(String key, String player, String link, String desc) {
        String msg = config.getString("messages." + key, "");
        msg = msg.replace("{player}", player).replace("{link}", link).replace("{desc}", desc);
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public LuckPerms getLuckPerms() {
        return luckPerms;
    }
}