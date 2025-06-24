package twitch.model;

/* Модель стримера */
public class StreamerInfo {
    public final String mcName;
    public final String twitchName;
    public final String url;

    public StreamerInfo(String mcName, String twitchName, String url) {
        this.mcName = mcName;
        this.twitchName = twitchName;
        this.url = url;
    }
}