package twitch.service;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import twitch.TwitchStreamPlugin;
import twitch.storage.PlayerPresenceRepository;
import twitch.service.StreamerManager;

import twitch.model.StreamerInfo;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.List;

public class PlayerPresenceListener implements Listener {
    private final TwitchStreamPlugin plugin;
    private final PlayerPresenceRepository repository;
    private final ExecutorService executor;
    private final StreamerManager streamerManager;

    public PlayerPresenceListener(TwitchStreamPlugin plugin, PlayerPresenceRepository repository, ExecutorService executor, StreamerManager streamerManager) {
        this.plugin = plugin;
        this.repository = repository;
        this.executor = executor;
        this.streamerManager = streamerManager;
    }

    private boolean isTrackedStreamer(String mcName) {
        if (mcName == null || mcName.isEmpty() || streamerManager == null) {
            return false;
        }
        List<StreamerInfo> list = streamerManager.getStreamers();
        synchronized (list) {
            for (StreamerInfo s : list) {
                if (s != null && s.mcName != null && s.mcName.equalsIgnoreCase(mcName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isPresenceEnabled()) {
            return;
        }
        String name = event.getPlayer().getName();
        if (!isTrackedStreamer(name)) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String serverId = plugin.getServerId();

        executor.submit(() -> repository.upsert(uuid, name, serverId));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.isPresenceEnabled()) {
            return;
        }
        String name = event.getPlayer().getName();
        if (!isTrackedStreamer(name)) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        executor.submit(() -> repository.delete(uuid));
    }
}
