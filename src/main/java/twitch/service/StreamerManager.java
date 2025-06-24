package twitch.service;

import org.bukkit.configuration.file.FileConfiguration;
import twitch.model.StreamerInfo;
import java.util.*;

/* управления стримерами */
public class StreamerManager {
    private final FileConfiguration config;
    private final List<StreamerInfo> streamers = new ArrayList<>();
    private final Map<String, Boolean> streamerLiveStatus = new HashMap<>();

    public StreamerManager(FileConfiguration config) {
        this.config = config;
        loadStreamersFromConfig();
    }

    public void loadStreamersFromConfig() {
        streamers.clear();
        List<?> rawList = config.getMapList("twitch.streamers");
        for (Object obj : rawList) {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                String mc = (String) map.get("mc");
                String twitch = (String) map.get("twitch");
                String url = (String) map.get("url");
                if (mc != null && twitch != null && url != null) {
                    streamers.add(new StreamerInfo(mc, twitch, url, ""));
                }
            }
        }
    }

    public List<StreamerInfo> getStreamers() {
        return streamers;
    }

    public Map<String, Boolean> getStreamerLiveStatus() {
        return streamerLiveStatus;
    }

    public void addStreamer(String mcName, String twitchName, String url, String desc) {
        streamers.add(new StreamerInfo(mcName, twitchName, url, desc));
        saveStreamersToConfig();
    }

    public void removeStreamer(String name) {
        streamers.removeIf(s -> name.equalsIgnoreCase(s.mcName) || name.equalsIgnoreCase(s.twitchName));
        saveStreamersToConfig();
    }

    private void saveStreamersToConfig() {
        List<Map<String, Object>> rawList = new ArrayList<>();
        for (StreamerInfo s : streamers) {
            Map<String, Object> map = new HashMap<>();
            map.put("mc", s.mcName);
            map.put("twitch", s.twitchName);
            map.put("url", s.url);
            rawList.add(map);
        }
        config.set("twitch.streamers", rawList);
        if (config instanceof org.bukkit.configuration.file.FileConfiguration) {
            try {
                ((org.bukkit.configuration.file.FileConfiguration) config).save("plugins/TwitchStream/config.yml");
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }
    }
}
