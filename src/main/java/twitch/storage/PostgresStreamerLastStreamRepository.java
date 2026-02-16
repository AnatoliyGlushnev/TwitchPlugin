package twitch.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class PostgresStreamerLastStreamRepository implements StreamerLastStreamRepository {
    private final DataSource dataSource;

    public PostgresStreamerLastStreamRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS twitch_streamer_last_stream (" +
                "server_id TEXT NOT NULL," +
                "mc_name TEXT NOT NULL," +
                "last_stream_ms BIGINT NOT NULL," +
                "PRIMARY KEY (server_id, mc_name)" +
                ")";

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
            st.execute("CREATE INDEX IF NOT EXISTS idx_twitch_streamer_last_stream_server ON twitch_streamer_last_stream (server_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_twitch_streamer_last_stream_mc_name ON twitch_streamer_last_stream (lower(mc_name))");
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure last stream schema", e);
        }
    }

    @Override
    public void upsert(String serverId, String mcName, long lastStreamMs) {
        if (serverId == null || serverId.isEmpty() || mcName == null || mcName.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO twitch_streamer_last_stream (server_id, mc_name, last_stream_ms) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (server_id, mc_name) DO UPDATE SET last_stream_ms = EXCLUDED.last_stream_ms";

        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, mcName);
            ps.setLong(3, lastStreamMs);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to upsert last stream", e);
        }
    }

    @Override
    public Long getLastStreamMs(String serverId, String mcName) {
        if (serverId == null || serverId.isEmpty() || mcName == null || mcName.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT last_stream_ms FROM twitch_streamer_last_stream WHERE server_id = ? AND lower(mc_name) = lower(?) LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, mcName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query last stream", e);
        }
    }

    @Override
    public Map<String, Long> findAllForServer(String serverId) {
        Map<String, Long> result = new HashMap<>();
        if (serverId == null || serverId.isEmpty()) {
            return result;
        }

        String sql = "SELECT mc_name, last_stream_ms FROM twitch_streamer_last_stream WHERE server_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String mcName = rs.getString(1);
                    long ms = rs.getLong(2);
                    if (mcName != null) {
                        result.put(mcName, ms);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list last streams", e);
        }
    }
}
