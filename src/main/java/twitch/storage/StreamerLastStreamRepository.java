package twitch.storage;

import java.util.Map;

public interface StreamerLastStreamRepository {
    void ensureSchema();

    void upsert(String serverId, String mcName, long lastStreamMs);

    Long getLastStreamMs(String serverId, String mcName);

    Map<String, Long> findAllForServer(String serverId);
}
