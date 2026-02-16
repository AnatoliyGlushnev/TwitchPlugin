package twitch.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

public class PostgresPlayerPresenceRepository implements PlayerPresenceRepository {
    private final DataSource dataSource;

    public PostgresPlayerPresenceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS twitch_player_presence (" +
                "player_uuid UUID PRIMARY KEY," +
                "mc_name TEXT NOT NULL," +
                "server_id TEXT NOT NULL," +
                "last_seen_ms BIGINT NOT NULL" +
                ")";

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
            st.execute("CREATE INDEX IF NOT EXISTS idx_twitch_player_presence_mc_name ON twitch_player_presence (lower(mc_name))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_twitch_player_presence_last_seen ON twitch_player_presence (last_seen_ms)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure presence schema", e);
        }
    }

    @Override
    public void upsert(UUID playerUuid, String mcName, String serverId) {
        if (playerUuid == null || mcName == null || serverId == null) {
            return;
        }
        String sql = "INSERT INTO twitch_player_presence (player_uuid, mc_name, server_id, last_seen_ms) " +
                "SELECT ?, ?, ?, ? " +
                "WHERE EXISTS (SELECT 1 FROM twitch_streamers WHERE lower(mc_name) = lower(?)) " +
                "ON CONFLICT (player_uuid) DO UPDATE SET mc_name = EXCLUDED.mc_name, server_id = EXCLUDED.server_id, last_seen_ms = EXCLUDED.last_seen_ms";

        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, playerUuid);
            ps.setString(2, mcName);
            ps.setString(3, serverId);
            ps.setLong(4, now);
            ps.setString(5, mcName);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to upsert presence", e);
        }
    }

    @Override
    public void delete(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        String sql = "DELETE FROM twitch_player_presence WHERE player_uuid = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, playerUuid);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete presence", e);
        }
    }

    @Override
    public void heartbeat(String serverId, Map<UUID, String> onlinePlayers) {
        if (serverId == null || onlinePlayers == null || onlinePlayers.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO twitch_player_presence (player_uuid, mc_name, server_id, last_seen_ms) " +
                "SELECT ?, ?, ?, ? " +
                "WHERE EXISTS (SELECT 1 FROM twitch_streamers WHERE lower(mc_name) = lower(?)) " +
                "ON CONFLICT (player_uuid) DO UPDATE SET mc_name = EXCLUDED.mc_name, server_id = EXCLUDED.server_id, last_seen_ms = EXCLUDED.last_seen_ms";

        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<UUID, String> e : onlinePlayers.entrySet()) {
                UUID uuid = e.getKey();
                String mcName = e.getValue();
                if (uuid == null || mcName == null) {
                    continue;
                }
                ps.setObject(1, uuid);
                ps.setString(2, mcName);
                ps.setString(3, serverId);
                ps.setLong(4, now);
                ps.setString(5, mcName);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new RuntimeException("Failed to heartbeat presence", e);
        }
    }

    @Override
    public boolean isPresentFreshByMcName(String mcName, long maxAgeMs) {
        if (mcName == null || mcName.trim().isEmpty()) {
            return false;
        }
        long minSeen = System.currentTimeMillis() - Math.max(0L, maxAgeMs);

        String sql = "SELECT 1 FROM twitch_player_presence WHERE lower(mc_name) = lower(?) AND last_seen_ms >= ? LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mcName);
            ps.setLong(2, minSeen);
            return ps.executeQuery().next();
        } catch (Exception e) {
            throw new RuntimeException("Failed to query presence", e);
        }
    }
}
