package twitch.service;

import org.bukkit.configuration.file.FileConfiguration;
import twitch.model.StreamerInfo;
import java.util.*;

/* управления стримерами */
public class StreamerManager {
    private final FileConfiguration config;
    private final List<StreamerInfo> streamers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> streamerLiveStatus = new HashMap<>();

    public StreamerManager(FileConfiguration config) {
        this.config = config;
        loadStreamersFromConfig();
    }

    public void loadStreamersFromConfig() {
        List<?> rawList = config.getMapList("twitch.streamers");
        if (rawList == null || rawList.isEmpty()) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "twitch.streamers отсутствует или пуст. Список стримеров не будет обновлён.");
            return;
        }
        streamers.clear();
        int invalidCount = 0;
        for (Object obj : rawList) {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                String mc = (String) map.get("mc");
                String twitch = (String) map.get("twitch");
                String url = (String) map.get("url");
                if (mc != null && twitch != null && url != null && !mc.isEmpty() && !twitch.isEmpty() && !url.isEmpty()) {
                    streamers.add(new StreamerInfo(mc, twitch, url, ""));
                } else {
                    invalidCount++;
                    System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "Обнаружена невалидная запись стримера в конфиге: " + map);
                }
            }
        }
        if (streamers.isEmpty()) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "В результате загрузки не найдено ни одного валидного стримера!");
        }
        if (invalidCount > 0) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "Количество невалидных записей стримеров: " + invalidCount);
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
        System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Добавлен стример: MC='" + mcName + "' Twitch='" + twitchName + "' URL='" + url + "'");
        saveStreamersToConfig();
    }

    public void removeStreamer(String name) {
        int before = streamers.size();
        streamers.removeIf(s -> name.equalsIgnoreCase(s.mcName) || name.equalsIgnoreCase(s.twitchName));
        int after = streamers.size();
        if (after < before) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Удалён стример с ником или Twitch: '" + name + "'");
        } else {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Попытка удалить стримера: '" + name + "', стример не найден.");
        }
        saveStreamersToConfig();
    }

    private void saveStreamersToConfig() {
        List<Map<String, Object>> rawList = new ArrayList<>();
        synchronized (streamers) {
            for (StreamerInfo s : streamers) {
                Map<String, Object> map = new HashMap<>();
                map.put("mc", s.mcName);
                map.put("twitch", s.twitchName);
                map.put("url", s.url);
                rawList.add(map);
            }
        }
        config.set("twitch.streamers", rawList);
        if (config instanceof org.bukkit.configuration.file.FileConfiguration) {
            try {
                ((org.bukkit.configuration.file.FileConfiguration) config).save("plugins/TwitchStream/config.yml");
            } catch (java.io.IOException e) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "Ошибка сохранения конфига стримеров: " + sw.toString());
            }
        }
    }
}
