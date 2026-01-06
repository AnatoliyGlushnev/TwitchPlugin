package twitch.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import twitch.model.StreamerInfo;
import twitch.scheduler.PluginScheduler;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
/* Дублирующий анонс стримеров */
public class TwitchAnnounceTask implements Runnable {
    private final JavaPlugin plugin;
    private final StreamerManager streamerManager;
    private final PluginScheduler pluginScheduler;

    public TwitchAnnounceTask(JavaPlugin plugin, StreamerManager streamerManager, PluginScheduler pluginScheduler) {
        this.plugin = plugin;
        this.streamerManager = streamerManager;
        this.pluginScheduler = pluginScheduler;
    }

    @Override
    public void run() {
        Map<String, Boolean> liveStatus = streamerManager.getStreamerLiveStatus();
        List<StreamerInfo> liveStreamers = streamerManager.getStreamers().stream()
                .filter(s -> liveStatus.getOrDefault(s.twitchName.toLowerCase(), false))
                .collect(Collectors.toList());
        if (!liveStreamers.isEmpty()) {
            for (StreamerInfo s : liveStreamers) {
                if (Bukkit.getPlayerExact(s.mcName) != null) {
                    String msg = plugin.getConfig().getString("messages.stream_repeat_broadcast", "{player} стрим: {link}")
                            .replace("{player}", s.mcName)
                            .replace("{link}", s.url);
                    for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        pluginScheduler.runForPlayer(plugin, p, () -> {
                            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
                        });
                    }
                }
            }
        }
    }
}
