package twitch.storage;

import java.util.Map;
import java.util.UUID;

public interface PlayerPresenceRepository {
    void ensureSchema();

    void upsert(UUID playerUuid, String mcName, String serverId);

    void delete(UUID playerUuid);

    void heartbeat(String serverId, Map<UUID, String> onlinePlayers);

    boolean isPresentFreshByMcName(String mcName, long maxAgeMs);
}
