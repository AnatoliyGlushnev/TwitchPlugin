package twitch.storage;

import twitch.model.StreamerInfo;

import java.util.List;

public interface StreamerRepository {
    void ensureSchema();

    long countStreamers();

    List<StreamerInfo> findAll();

    boolean upsert(StreamerInfo streamer);

    int deleteByMcOrTwitchName(String name);
}
