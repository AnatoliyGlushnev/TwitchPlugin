package twitch;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.event.HandlerList;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;

public class TwitchStreamPlugin extends JavaPlugin {
    private FileConfiguration config;

    private TwitchCommand twitchCommand;
    private LuckPerms luckPerms;

    private String clientId;
    private String oauthToken;
    private String twitchGroup = "Группа"; // Группа для выдачи
    private java.util.List<StreamerInfo> streamers = new java.util.ArrayList<>();

    public java.util.List<StreamerInfo> getStreamers() {
        return streamers;
    }

    public String getTwitchGroup() {
        return twitchGroup;
    }

    public void addStreamer(String mcName, String twitchName, String url) {
        streamers.add(new StreamerInfo(mcName, twitchName, url));
        java.util.List<java.util.Map<String, Object>> rawList = new java.util.ArrayList<>();
        for (StreamerInfo s : streamers) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("mc", s.mcName);
            map.put("twitch", s.twitchName);
            map.put("url", s.url);
            rawList.add(map);
        }
        config.set("twitch.streamers", rawList);
        saveConfig();
        reloadPlugin();
    }
    private java.util.Map<String, Boolean> streamerLiveStatus = new java.util.HashMap<>();

    public java.util.Map<String, Boolean> getStreamerLiveStatus() {
        return streamerLiveStatus;
    }

    public static class StreamerInfo {
        public final String mcName;
        public final String twitchName;
        public final String url;
        public StreamerInfo(String mcName, String twitchName, String url) {
            this.mcName = mcName;
            this.twitchName = twitchName;
            this.url = url;
        }
    }

    private void loadStreamersFromConfig() {
        streamers.clear();
        java.util.List<?> rawList = config.getMapList("twitch.streamers");
        for (Object obj : rawList) {
            if (obj instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) obj;
                String mc = (String) map.get("mc");
                String twitch = (String) map.get("twitch");
                String url = (String) map.get("url");
                if (mc != null && twitch != null && url != null) {
                    streamers.add(new StreamerInfo(mc, twitch, url));
                }
            }
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = getConfig();
        this.clientId = config.getString("twitch.client_id");
        this.oauthToken = config.getString("twitch.oauth_token");
        loadStreamersFromConfig();
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (this.luckPerms == null) {
            getLogger().severe("LuckPerms не найден! Плагин не сможет выдавать группы.");
        }
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
        this.twitchGroup = config.getString("twitch.group", "Группа"); // Группа для выдачи
        loadStreamersFromConfig();
        if (twitchCommand != null) {
            HandlerList.unregisterAll(twitchCommand);
        }
        twitchCommand = new TwitchCommand(this);
        getCommand("стрим").setExecutor(twitchCommand);
        startStreamChecker();
    }
    private void startStreamChecker() {
        getServer().getGlobalRegionScheduler().runAtFixedRate(
            this,
            task -> {
                for (StreamerInfo streamer : streamers) {
                    checkTwitchStream(streamer);
                }
            },
            1L,
            1200L // (60 секунд)
        );
    }

    private void checkTwitchStream(StreamerInfo streamer) {
        try {
            
            java.net.URL url = new java.net.URL("https://api.twitch.tv/helix/streams?user_login=" + streamer.twitchName);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Authorization", "Bearer " + oauthToken);
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            boolean isLive = response.toString().contains("\"type\":\"live\"");
            boolean wasLive = streamerLiveStatus.getOrDefault(streamer.twitchName.toLowerCase(), false);
            
            if (isLive && !wasLive) {
    getLogger().info("Стрим начался для " + streamer.mcName + " (Twitch: " + streamer.twitchName + ")");
    getServer().getGlobalRegionScheduler().execute(this, () -> {
        org.bukkit.entity.Player streamerPlayer = org.bukkit.Bukkit.getPlayerExact(streamer.mcName);
        if (streamerPlayer != null) {
            net.md_5.bungee.api.chat.TextComponent msg = new net.md_5.bungee.api.chat.TextComponent("Стрим на Twitch канале " + streamer.mcName + ": ");
            net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(streamer.url);
            link.setColor(net.md_5.bungee.api.ChatColor.BLUE);
            link.setUnderlined(true);
            link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, streamer.url));
            msg.addExtra(link);
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                p.spigot().sendMessage(msg);
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
            streamerLiveStatus.put(streamer.twitchName.toLowerCase(), isLive);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            getLogger().warning("Ошибка при проверке Twitch для " + streamer.twitchName + ": " + sw.toString());
        }
    }

    public void addStreamer(String name, String url) {
        java.util.List<?> rawList = config.getMapList("twitch.streamers");
        java.util.List<java.util.Map<String, Object>> streamerList = new java.util.ArrayList<>();
        for (Object obj : rawList) {
            if (obj instanceof java.util.Map) {
                java.util.Map<?, ?> rawMap = (java.util.Map<?, ?>) obj;
                java.util.Map<String, Object> safeMap = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) {
                        safeMap.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                streamerList.add(safeMap);
            }
        }
        java.util.Map<String, Object> newStreamer = new java.util.HashMap<>();
        newStreamer.put("name", name);
        newStreamer.put("url", url);
        streamerList.add(newStreamer);
        config.set("twitch.streamers", streamerList);
        saveConfig();
        
    }

    public void removeStreamer(String name) {
    java.util.List<?> rawList = config.getMapList("twitch.streamers");
    java.util.List<java.util.Map<String, Object>> streamerList = new java.util.ArrayList<>();
    for (Object obj : rawList) {
        if (obj instanceof java.util.Map) {
            java.util.Map<?, ?> rawMap = (java.util.Map<?, ?>) obj;
            java.util.Map<String, Object> safeMap = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    safeMap.put(entry.getKey().toString(), entry.getValue());
                }
            }
            streamerList.add(safeMap);
        }
    }
    int before = streamerList.size();
    streamerList.removeIf(map ->
        name.equalsIgnoreCase((String) map.get("mc")) ||
        name.equalsIgnoreCase((String) map.get("twitch"))
    );
    int after = streamerList.size();
    getLogger().info("[DEBUG] removeStreamer: удалено " + (before - after) + " стример(ов) по запросу '" + name + "'.");
    config.set("twitch.streamers", streamerList);
    saveConfig();
    streamers.removeIf(s -> name.equalsIgnoreCase(s.mcName) || name.equalsIgnoreCase(s.twitchName));
    reloadPlugin();
}

    public String getMessage(String key, String player, String link) {
        return getMessage(key, player, link, "");
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
