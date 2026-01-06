package twitch.scheduler;

import org.bukkit.Server;

public final class PluginSchedulerFactory {

    private PluginSchedulerFactory() {
    }

    public static PluginScheduler create(Server server) {
        try {
            server.getClass().getMethod("getGlobalRegionScheduler");
            server.getClass().getMethod("getRegionScheduler");
            return new FoliaPluginScheduler(server);
        } catch (NoSuchMethodException ignored) {
            return new PaperPluginScheduler();
        }
    }
}
