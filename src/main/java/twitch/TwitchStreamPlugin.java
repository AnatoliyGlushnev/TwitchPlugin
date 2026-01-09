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
    private final java.util.Set<String> streamCheckInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask streamCheckerTask = null;

    public String getTwitchGroup() {
        return twitchGroup;
    }

    @Override
    public void onEnable() {
    getLogger().info("[TWITCH INIT] Вызван onEnable(). Начало инициализации плагина...");
        getLogger().info("[TWITCH INIT] Инициализация ExecutorService...");
        this.executorService = java.util.concurrent.Executors.newCachedThreadPool();
        getLogger().info("[TWITCH INIT] Сохранение/загрузка стандартного конфига...");
        saveDefaultConfig();
        this.config = getConfig();
        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        this.streamerManager = new StreamerManager(config);
        getLogger().info("[TWITCH INIT] Загрузка API LuckPerms...");
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        getLogger().info("[TWITCH INIT] Инициализация TwitchApiService...");
        this.twitchApiService = new TwitchApiService(clientId, oauthToken, getLogger());
    // Валидация подключения к Twitch API, чтобы не блокировать основной поток
        getLogger().info("[TWITCH INIT] Отправка задачи проверки подключения к Twitch API в отдельный поток...");
        executorService.submit(() -> this.twitchApiService.validateConnection());
        getLogger().info("[TWITCH INIT] Чтение twitch-группы из конфига...");
        this.twitchGroup = config.getString("group", "twitch_on"); // по умолчанию
        getLogger().info("[TWITCH INIT] Проверка LuckPerms...");
        if (this.luckPerms == null) {
            getLogger().severe("LuckPerms не найден! Плагин не сможет выдавать группы.");
        }
        getLogger().info("[TWITCH INIT] Запуск задачи анонса стримеров...");
        // анонс стримеров
        startAnnounceTask();
        reloadPlugin();
        getLogger().info("[TWITCH INIT] Инициализация завершена!");
        getLogger().info("[TWITCH] TwitchStreamPlugin работает.");
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
        if (streamCheckerTask != null) {
            streamCheckerTask.cancel();
        }
        streamCheckerTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
            this,
            task -> {
                for (StreamerInfo streamer : streamerManager.getStreamers()) {
                    if (org.bukkit.Bukkit.getPlayerExact(streamer.mcName) != null) {
                        checkTwitchStream(streamer);
                    } else {
                        streamerManager.getStreamerLiveStatus().put(streamer.twitchName.toLowerCase(), false);
                    }
                }
                if (twitchApiService != null) {
                    String limit = twitchApiService.getLastRateLimitLimit();
                    String remaining = twitchApiService.getLastRateLimitRemaining();
                    String reset = twitchApiService.getLastRateLimitReset();
                    if (limit != null && !limit.isEmpty() && remaining != null && !remaining.isEmpty()) {
                        getLogger().info("[TWITCH] Период проверки API: осталось " + remaining + " из " + limit + " запросов" + (reset != null && !reset.isEmpty() ? (" (reset=" + reset + ")") : ""));
                    }
                }
            },
            1L,
            checkPeriod
        );
    }

    private static boolean isApiTwitchTvHost(String host) {
        if (host == null) {
            return false;
        }
        return "api.twitch.tv".equalsIgnoreCase(host);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String buildCauseChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            String msg = cur.getMessage();
            sb.append(cur.getClass().getName());
            if (msg != null && !msg.isEmpty()) {
                sb.append(": ").append(msg);
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String classifyNetworkProblem(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = safeLower(cur.getMessage());

            if (cur instanceof java.net.UnknownHostException) {
                return "dns_unknown_host";
            }
            if (cur instanceof java.net.SocketTimeoutException || msg.contains("timed out")) {
                return "timeout";
            }
            if (cur instanceof java.net.ConnectException || msg.contains("connection refused")) {
                return "connect_refused";
            }
            if (cur instanceof javax.net.ssl.SSLException) {
                return "ssl_error";
            }
            if (cur instanceof java.net.SocketException) {
                if (msg.contains("connection reset")) {
                    return "socket_connection_reset";
                }
                if (msg.contains("broken pipe")) {
                    return "socket_broken_pipe";
                }
                return "socket_error";
            }
            if (msg.contains("no route to host")) {
                return "no_route_to_host";
            }
            if (msg.contains("network is unreachable")) {
                return "network_unreachable";
            }

            cur = cur.getCause();
        }
        return "unknown";
    }

    private void logApiTwitchTvConnectionProblem(String endpoint, Throwable e) {
        String host = null;
        try {
            host = new java.net.URL(endpoint).getHost();
        } catch (Exception ignored) {
        }

        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String type = classifyNetworkProblem(e);
        String chain = buildCauseChain(e);
        String rootMsg = root.getMessage() != null ? root.getMessage() : root.toString();

        java.util.logging.Level level;
        if (!isApiTwitchTvHost(host)) {
            level = java.util.logging.Level.WARNING;
        } else if ("dns_unknown_host".equals(type) || "timeout".equals(type) || "connect_refused".equals(type)) {
            level = java.util.logging.Level.INFO;
        } else {
            level = java.util.logging.Level.WARNING;
        }

        getLogger().log(level,
                "[TWITCH API] Ошибка соединения" +
                        " host=" + (host == null ? "?" : host) +
                        " endpoint=" + endpoint +
                        " type=" + type +
                        " root=\"" + rootMsg.replace("\"", "'") + "\"" +
                        " causes=" + chain,
                e);
    }

    private void checkTwitchStream(StreamerInfo streamer) {
        String streamerKey = streamer.twitchName == null ? "" : streamer.twitchName.toLowerCase();
        if (!streamCheckInFlight.add(streamerKey)) {
            return;
        }
        executorService.submit(() -> {
            String endpoint = "https://api.twitch.tv/helix/streams?user_login=" + streamer.twitchName;
            try {
                String response = twitchApiService.sendGetRequest(endpoint);
                if (response != null && response.contains("\"error\": \"rate_limit\"")) {
                    String errorKey = streamer.twitchName.toLowerCase() + ":rate_limit";
                    long now = System.currentTimeMillis();
                    synchronized (TwitchStreamPlugin.class) {
                        if (lastErrorLogTime == null) lastErrorLogTime = new java.util.HashMap<>();
                        Long last = lastErrorLogTime.get(errorKey);
                        if (last == null || now - last > 60_000) {
                            getLogger().warning("[TWITCH API] Превышен лимит запросов к Twitch API (429). Пропускаем обновление статуса для " + streamer.twitchName);
                            lastErrorLogTime.put(errorKey, now);
                        }
                    }
                    return;
                }

                boolean isLive = response.contains("\"type\":\"live\"");
                boolean wasLive = streamerManager.getStreamerLiveStatus().getOrDefault(streamer.twitchName.toLowerCase(), false);

                streamerManager.getStreamerLiveStatus().put(streamer.twitchName.toLowerCase(), isLive);
                if (isLive && !wasLive) {
                    getLogger().info("Стрим начался для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");
                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                        org.bukkit.entity.Player streamerPlayer = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                        if (streamerPlayer != null) {
                            String streamMsg = getMessage("stream_start_broadcast", streamer.mcName, streamer.url, streamer.twitchName);
                            String streamMsgWithoutUrl = streamMsg.replace(streamer.url, "").trim();
                            net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(streamer.url);
                            link.setColor(net.md_5.bungee.api.ChatColor.BLUE);
                            link.setUnderlined(true);
                            link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, streamer.url));
                            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                                org.bukkit.Bukkit.getRegionScheduler().run(this, p.getLocation(), task -> {
                                    if (!streamMsgWithoutUrl.isEmpty()) {
                                        p.sendMessage(streamMsgWithoutUrl);
                                    }
                                    p.spigot().sendMessage(link);
                                });
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
                String type = classifyNetworkProblem(e);
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                String msg = root.getMessage() != null ? root.getMessage() : root.toString();

                if (isTemporaryNetworkError(type, endpoint)) {
                    String errorKey = streamer.twitchName.toLowerCase() + ":" + type;
                    long now = System.currentTimeMillis();
                    synchronized (TwitchStreamPlugin.class) {
                        if (lastErrorLogTime == null) lastErrorLogTime = new java.util.HashMap<>();
                        Long last = lastErrorLogTime.get(errorKey);
                        if (last == null || now - last > 60_000) {
                            logApiTwitchTvConnectionProblem(endpoint, e);
                            lastErrorLogTime.put(errorKey, now);
                        }
                    }
                    return;
                } else {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    getLogger().warning("Ошибка при проверке Twitch для " + streamer.twitchName + ": " + sw.toString());
                }
            } finally {
                streamCheckInFlight.remove(streamerKey);
            }
        });
    }

    private boolean isTemporaryNetworkError(String type, String endpoint) {
        String host = null;
        try {
            host = new java.net.URL(endpoint).getHost();
        } catch (Exception ignored) {
        }
        if (!isApiTwitchTvHost(host)) {
            return false;
        }
        return "dns_unknown_host".equals(type) ||
                "timeout".equals(type) ||
                "connect_refused".equals(type) ||
                "no_route_to_host".equals(type) ||
                "network_unreachable".equals(type);
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
        if (streamCheckerTask != null) {
            streamCheckerTask.cancel();
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