package twitch.storage;

import twitch.model.StreamerInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class PostgresStreamerRepository implements StreamerRepository {
    private final DataSource dataSource;

    public PostgresStreamerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS twitch_streamers (" +
                "id BIGSERIAL PRIMARY KEY," +
                "mc_name TEXT NOT NULL," +
                "twitch_name TEXT NOT NULL," +
                "url TEXT NOT NULL," +
                "desc_text TEXT NOT NULL DEFAULT ''," +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()," +
                "UNIQUE (mc_name)," +
                "UNIQUE (twitch_name)" +
                ")";

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure schema", e);
        }
    }

    @Override
    public long countStreamers() {
        String sql = "SELECT COUNT(*) FROM twitch_streamers";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to count streamers", e);
        }
    }

    @Override
    public List<StreamerInfo> findAll() {
        String sql = "SELECT mc_name, twitch_name, url, desc_text FROM twitch_streamers ORDER BY mc_name ASC";
        List<StreamerInfo> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String mc = rs.getString(1);
                String twitch = rs.getString(2);
                String url = rs.getString(3);
                String desc = rs.getString(4);
                result.add(new StreamerInfo(mc, twitch, url, desc));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load streamers", e);
        }
    }

    @Override
    public boolean upsert(StreamerInfo streamer) {
        String sql = "INSERT INTO twitch_streamers (mc_name, twitch_name, url, desc_text) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (mc_name) DO UPDATE SET twitch_name = EXCLUDED.twitch_name, url = EXCLUDED.url, desc_text = EXCLUDED.desc_text";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, streamer.mcName);
            ps.setString(2, streamer.twitchName);
            ps.setString(3, streamer.url);
            ps.setString(4, streamer.desc == null ? "" : streamer.desc);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upsert streamer", e);
        }
    }

    @Override
    public int deleteByMcOrTwitchName(String name) {
        String sql = "DELETE FROM twitch_streamers WHERE lower(mc_name) = lower(?) OR lower(twitch_name) = lower(?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, name);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete streamer", e);
        }
    }
}
