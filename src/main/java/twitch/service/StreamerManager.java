package twitch.service;

import org.bukkit.configuration.file.FileConfiguration;
import twitch.model.StreamerInfo;
import twitch.storage.StreamerRepository;
import java.util.*;

/* управления стримерами */
public class StreamerManager {
    private final FileConfiguration config;
    private final List<StreamerInfo> streamers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> streamerLiveStatus = new HashMap<>();
    private final StreamerRepository repository;

    public StreamerManager(FileConfiguration config, StreamerRepository repository) {
        this.config = config;
        this.repository = repository;
        initFromDatabase();
    }

    private void initFromDatabase() {
        repository.ensureSchema();
        if (repository.countStreamers() == 0) {
            migrateFromConfigIfPresent();
        }
        reloadFromDatabase();
    }

    private void migrateFromConfigIfPresent() {
        List<?> rawList = config.getMapList("twitch.streamers");
        if (rawList == null || rawList.isEmpty()) {
            return;
        }

        int invalidCount = 0;
        for (Object obj : rawList) {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                String mc = (String) map.get("mc");
                String twitch = (String) map.get("twitch");
                String url = (String) map.get("url");
                if (mc != null && twitch != null && url != null && !mc.isEmpty() && !twitch.isEmpty() && !url.isEmpty()) {
                    repository.upsert(new StreamerInfo(mc, twitch, url, ""));
                } else {
                    invalidCount++;
                    System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "Обнаружена невалидная запись стримера в конфиге: " + map);
                }
            }
        }
        if (invalidCount > 0) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.WARNING, "Количество невалидных записей стримеров: " + invalidCount);
        }
    }

    public void reloadFromDatabase() {
        List<StreamerInfo> all = repository.findAll();
        synchronized (streamers) {
            streamers.clear();
            streamers.addAll(all);
        }
    }

    public List<StreamerInfo> getStreamers() {
        return streamers;
    }

    public Map<String, Boolean> getStreamerLiveStatus() {
        return streamerLiveStatus;
    }

    public void addStreamer(String mcName, String twitchName, String url, String desc) {
        repository.upsert(new StreamerInfo(mcName, twitchName, url, desc));
        reloadFromDatabase();
        System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Добавлен стример: MC='" + mcName + "' Twitch='" + twitchName + "' URL='" + url + "'");
    }

    public void removeStreamer(String name) {
        int removed = repository.deleteByMcOrTwitchName(name);
        reloadFromDatabase();
        if (removed > 0) {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Удалён стример с ником или Twitch: '" + name + "'");
        } else {
            System.getLogger("TwitchPlugin").log(System.Logger.Level.INFO, "Попытка удалить стримера: '" + name + "', стример не найден.");
        }
    }
}
