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
import twitch.command.TwitchStreamerMenu;
import twitch.command.TwitchLuckPermsJoinListener;
import twitch.storage.DatabaseManager;
import twitch.storage.PostgresStreamerRepository;
import twitch.storage.StreamerRepository;
import twitch.storage.PlayerPresenceRepository;
import twitch.storage.PostgresPlayerPresenceRepository;
import twitch.storage.StreamerLastStreamRepository;
import twitch.storage.PostgresStreamerLastStreamRepository;

import com.zaxxer.hikari.HikariDataSource;

import twitch.scheduler.CancellableTask;
import twitch.scheduler.PluginScheduler;
import twitch.scheduler.PluginSchedulerFactory;

import twitch.service.PlayerPresenceListener;

public class TwitchStreamPlugin extends JavaPlugin {

    private static java.util.Map<String, Long> lastErrorLogTime = new java.util.HashMap<>();
    private final java.util.Set<String> streamCheckInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService executorService; //асинхронная задач
    private FileConfiguration config;
    private TwitchCommand twitchCommand;
    private TwitchStreamerMenu streamerMenu;
    private TwitchLuckPermsJoinListener luckPermsJoinListener;
    private LuckPerms luckPerms;
    private String clientId;
    private String oauthToken;
    private String twitchGroup; 
    private TwitchApiService twitchApiService;
    private StreamerManager streamerManager;
    private PluginScheduler pluginScheduler;
    private CancellableTask announceTask = null;
    private CancellableTask streamCheckerTask = null;
    private CancellableTask presenceHeartbeatTask = null;
    private CancellableTask streamersReloadTask = null;
    private HikariDataSource dataSource;
    private StreamerRepository streamerRepository;
    private PlayerPresenceRepository presenceRepository;
    private StreamerLastStreamRepository lastStreamRepository;
    private PlayerPresenceListener presenceListener;

    public boolean isPresenceEnabled() {
        return config.getBoolean("twitch.presence.enabled", true);
    }

    public String getServerId() {
        return config.getString("twitch.server_id", "default");
    }

    private long getPresenceHeartbeatPeriodTicks() {
        return config.getLong("twitch.presence.heartbeat_period_ticks", 200L);
    }

    private long getPresenceFreshnessMs() {
        return config.getLong("twitch.presence.freshness_ms", 30_000L);
    }

    private long getStreamersReloadPeriodTicks() {
        return config.getLong("twitch.streamers_reload_period_ticks", 600L);
    }

    public String getTwitchGroup() {
        return twitchGroup;
    }

    public StreamerLastStreamRepository getLastStreamRepository() {
        return lastStreamRepository;
    }

    @Override
    public void onEnable() {
        getLogger().info("[TWITCH INIT] Вызван onEnable(). Начало инициализации плагина...");
        getLogger().info("[TWITCH INIT] Инициализация ExecutorService...");
        this.executorService = java.util.concurrent.Executors.newCachedThreadPool();
        this.pluginScheduler = PluginSchedulerFactory.create(getServer());
        getLogger().info("[TWITCH INIT] Сохранение/загрузка стандартного конфига...");
        saveDefaultConfig();
        this.config = getConfig();

        try {
            this.dataSource = DatabaseManager.createDataSource(this.config);
            this.streamerRepository = new PostgresStreamerRepository(this.dataSource);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            getLogger().severe("[TWITCH INIT] Не удалось инициализировать PostgreSQL: " + sw.toString());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        try {
            this.streamerManager = new StreamerManager(config, streamerRepository);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            getLogger().severe("[TWITCH INIT] Ошибка инициализации StreamerManager/БД: " + sw.toString());
            try {
                if (this.dataSource != null) {
                    this.dataSource.close();
                }
            } catch (Exception ignored) {
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("[TWITCH INIT] Загрузка API LuckPerms...");
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        getLogger().info("[TWITCH INIT] Инициализация TwitchApiService...");
        String proxyUrl = config.getString("twitch.proxy_url", "");
        this.twitchApiService = new TwitchApiService(clientId, oauthToken, getLogger(), proxyUrl);
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

        if (this.dataSource != null) {
            try {
                this.dataSource.close();
            } catch (Exception ignored) {
            }
            this.dataSource = null;
        }
        this.streamerRepository = null;

        try {
            this.dataSource = DatabaseManager.createDataSource(this.config);
            this.streamerRepository = new PostgresStreamerRepository(this.dataSource);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            getLogger().severe("[TWITCH INIT] Не удалось переинициализировать PostgreSQL: " + sw.toString());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        reloadPlugin();
    }

    public void reloadPlugin() {
        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        this.twitchGroup = config.getString("twitch.group", "twitch_on"); // Группа для выдачи, ПО УМОЛЧАНИЮ twitch_on
        String proxyUrl = config.getString("twitch.proxy_url", "");
        this.twitchApiService = new TwitchApiService(clientId, oauthToken, getLogger(), proxyUrl);
        if (executorService != null) {
            executorService.submit(() -> this.twitchApiService.validateConnection());
        }
        this.streamerManager = new StreamerManager(config, streamerRepository);

        if (presenceListener != null) {
            HandlerList.unregisterAll(presenceListener);
            presenceListener = null;
        }
        this.presenceRepository = new PostgresPlayerPresenceRepository(this.dataSource);
        this.presenceRepository.ensureSchema();
        this.lastStreamRepository = new PostgresStreamerLastStreamRepository(this.dataSource);
        this.lastStreamRepository.ensureSchema();
        this.presenceListener = new PlayerPresenceListener(this, presenceRepository, executorService, streamerManager);
        org.bukkit.Bukkit.getPluginManager().registerEvents(this.presenceListener, this);
        startPresenceHeartbeat();

        twitchCommand = null;
        if (streamerMenu != null) {
            HandlerList.unregisterAll(streamerMenu);
            streamerMenu = null;
        }
        if (luckPermsJoinListener != null) {
            HandlerList.unregisterAll(luckPermsJoinListener);
            luckPermsJoinListener = null;
        }

        streamerMenu = new TwitchStreamerMenu(this, streamerManager);
        org.bukkit.Bukkit.getPluginManager().registerEvents(streamerMenu, this);
        luckPermsJoinListener = new TwitchLuckPermsJoinListener(this, streamerManager);
        org.bukkit.Bukkit.getPluginManager().registerEvents(luckPermsJoinListener, this);

        twitchCommand = new TwitchCommand(this, streamerManager, streamerMenu);
        getCommand("стрим").setExecutor(twitchCommand);
        startStreamChecker();
        startAnnounceTask();
        startStreamersReloadTask();
    }

    private void startAnnounceTask() {
        long announcePeriod = config.getLong("twitch.announce_period", 72000L);
        if (announceTask != null) {
            announceTask.cancel();
        }
        announceTask = pluginScheduler.runAtFixedRate(
            this,
            () -> new TwitchAnnounceTask(this, streamerManager, pluginScheduler).run(),
            announcePeriod,
            announcePeriod
        );
    }

    private void startPresenceHeartbeat() {
        if (!isPresenceEnabled()) {
            if (presenceHeartbeatTask != null) {
                presenceHeartbeatTask.cancel();
                presenceHeartbeatTask = null;
            }
            return;
        }
        long period = Math.max(20L, getPresenceHeartbeatPeriodTicks());
        if (presenceHeartbeatTask != null) {
            presenceHeartbeatTask.cancel();
        }

        presenceHeartbeatTask = pluginScheduler.runAtFixedRate(
            this,
            () -> {
                if (presenceRepository == null) {
                    return;
                }
                java.util.Map<java.util.UUID, String> online = new java.util.HashMap<>();
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    String name = p.getName();
                    boolean tracked = false;
                    java.util.List<twitch.model.StreamerInfo> list = streamerManager == null ? null : streamerManager.getStreamers();
                    if (list != null) {
                        synchronized (list) {
                            for (twitch.model.StreamerInfo s : list) {
                                if (s != null && s.mcName != null && s.mcName.equalsIgnoreCase(name)) {
                                    tracked = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (tracked) {
                        online.put(p.getUniqueId(), name);
                    }
                }
                if (online.isEmpty()) {
                    return;
                }

                String serverId = getServerId();
                executorService.submit(() -> {
                    try {
                        presenceRepository.heartbeat(serverId, online);
                    } catch (Exception e) {
                        getLogger().warning("[TWITCH] Presence heartbeat error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                });
            },
            1L,
            period
        );
    }

    private void startStreamChecker() {
        long checkPeriod = config.getLong("twitch.stream_check_period", 1200L);
        if (streamCheckerTask != null) {
            streamCheckerTask.cancel();
        }

        streamCheckerTask = pluginScheduler.runAtFixedRate(
            this,
            () -> {
                for (StreamerInfo streamer : streamerManager.getStreamers()) {
                    checkTwitchStream(streamer);
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

    private void startStreamersReloadTask() {
        long period = Math.max(20L, getStreamersReloadPeriodTicks());
        if (streamersReloadTask != null) {
            streamersReloadTask.cancel();
            streamersReloadTask = null;
        }
        if (executorService == null || streamerManager == null) {
            return;
        }

        streamersReloadTask = pluginScheduler.runAtFixedRate(
            this,
            () -> {
                if (executorService == null || streamerManager == null) {
                    return;
                }
                executorService.submit(() -> {
                    try {
                        streamerManager.reloadFromDatabase();
                    } catch (Exception e) {
                        getLogger().warning("[TWITCH] Ошибка авто-обновления списка стримеров: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                });
            },
            period,
            period
        );
    }

    private void checkTwitchStream(StreamerInfo streamer) {
        String streamerKey = streamer.twitchName == null ? "" : streamer.twitchName.toLowerCase();
        if (!streamCheckInFlight.add(streamerKey)) {
            return;
        }
        executorService.submit(() -> {
            try {
                if (isPresenceEnabled() && presenceRepository != null) {
                    boolean present;
                    try {
                        present = presenceRepository.isPresentFreshByMcName(streamer.mcName, getPresenceFreshnessMs());
                    } catch (Exception e) {
                        present = false;
                        String errorKey = streamer.twitchName.toLowerCase() + ":presence_check_error";
                        long now = System.currentTimeMillis();
                        synchronized (TwitchStreamPlugin.class) {
                            Long last = lastErrorLogTime.get(errorKey);
                            if (last == null || now - last > 60_000) {
                                getLogger().warning("[TWITCH] Ошибка presence-проверки для " + streamer.mcName + " (" + streamer.twitchName + "): " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                                lastErrorLogTime.put(errorKey, now);
                            }
                        }
                    }
                    if (!present) {
                        return;
                    }
                }

                String endpoint = "https://api.twitch.tv/helix/streams?user_login=" + streamer.twitchName;
                String response = twitchApiService.sendGetRequest(endpoint);
                if (response != null && response.contains("\"error\": \"rate_limit\"")) {
                    String errorKey = streamer.twitchName.toLowerCase() + ":rate_limit";
                    long now = System.currentTimeMillis();
                    synchronized (TwitchStreamPlugin.class) {
                        Long last = lastErrorLogTime.get(errorKey);
                        if (last == null || now - last > 60_000) {
                            getLogger().warning("[TWITCH API] Превышен лимит запросов к Twitch API (429). Пропускаем обновление статуса для " + streamer.twitchName);
                            lastErrorLogTime.put(errorKey, now);
                        }
                    }
                    return;
                }

                boolean isLive = response != null && response.contains("\"type\":\"live\"");
                boolean wasLive = streamerManager.getStreamerLiveStatus().getOrDefault(streamer.twitchName.toLowerCase(), false);

                streamerManager.getStreamerLiveStatus().put(streamer.twitchName.toLowerCase(), isLive);
                if (isLive && !wasLive) {
                    getLogger().info("Стрим начался для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");

                    if (lastStreamRepository != null) {
                        org.bukkit.entity.Player streamerPlayer = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                        if (streamerPlayer != null) {
                            String serverId = getServerId();
                            long now = System.currentTimeMillis();
                            executorService.submit(() -> {
                                try {
                                    lastStreamRepository.upsert(serverId, streamer.mcName, now);
                                } catch (Exception e) {
                                    getLogger().warning("[TWITCH] Ошибка записи last_stream для " + streamer.mcName + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                                }
                            });
                        }
                    }

                    pluginScheduler.execute(this, () -> {
                        String streamMsg = getMessage("stream_start_broadcast", streamer.mcName, streamer.url, streamer.twitchName);
                        String streamMsgWithoutUrl = streamMsg.replace(streamer.url, "").trim();

                        net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(streamer.url);
                        link.setColor(net.md_5.bungee.api.ChatColor.BLUE);
                        link.setUnderlined(true);
                        link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, streamer.url));
                        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                            pluginScheduler.runForPlayer(this, p, () -> {
                                if (!streamMsgWithoutUrl.isEmpty()) {
                                    p.sendMessage(streamMsgWithoutUrl);
                                }
                                p.spigot().sendMessage(link);
                            });
                        }

                        org.bukkit.entity.Player streamerPlayer = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                        LuckPerms luckPerms = getLuckPerms();
                        if (streamerPlayer != null && luckPerms != null) {
                            java.util.UUID uuid = streamerPlayer.getUniqueId();
                            luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
                                user.data().add(net.luckperms.api.node.types.InheritanceNode.builder(getTwitchGroup()).build());
                                luckPerms.getUserManager().saveUser(user);
                            });
                        }
                    });
                } else if (!isLive && wasLive) {
                    getLogger().info("Стрим завершён для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");
                    pluginScheduler.execute(this, () -> {
                        LuckPerms luckPerms = getLuckPerms();
                        if (luckPerms != null) {
                            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
                            if (player != null) {
                                java.util.UUID uuid = player.getUniqueId();
                                luckPerms.getUserManager().loadUser(uuid).thenAcceptAsync(user -> {
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
                if (isTemporaryNetworkError(cause, msg)) {
                    String errorKey = streamer.twitchName.toLowerCase() + ":" + cause.getClass().getSimpleName();
                    long now = System.currentTimeMillis();
                    synchronized (TwitchStreamPlugin.class) {
                        Long last = lastErrorLogTime.get(errorKey);
                        if (last == null || now - last > 60_000) {
                            getLogger().info("[TwitchStream] Не удалось проверить Twitch для " + streamer.twitchName + ": " + msg);
                            lastErrorLogTime.put(errorKey, now);
                        }
                    }
                    return;
                }

                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                getLogger().warning("Ошибка при проверке Twitch для " + streamer.twitchName + ": " + sw.toString());
            } finally {
                streamCheckInFlight.remove(streamerKey);
            }
        });
    }

    private boolean isTemporaryNetworkError(Throwable cause, String msg) {
        return cause instanceof java.net.ConnectException ||
                cause instanceof java.net.UnknownHostException ||
                cause instanceof java.net.SocketTimeoutException ||
                cause instanceof javax.net.ssl.SSLException ||
                msg.toLowerCase().contains("timed out") ||
                msg.toLowerCase().contains("connection refused");
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
        if (presenceHeartbeatTask != null) {
            presenceHeartbeatTask.cancel();
        }
        if (streamersReloadTask != null) {
            streamersReloadTask.cancel();
        }
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
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

    public boolean isStreamerPresentInNetwork(String mcName) {
        if (!isPresenceEnabled()) {
            return true;
        }
        if (presenceRepository == null) {
            return false;
        }
        try {
            return presenceRepository.isPresentFreshByMcName(mcName, getPresenceFreshnessMs());
        } catch (Exception e) {
            return false;
        }
    }
}