package twitch.scheduler;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public interface PluginScheduler {
    CancellableTask runAtFixedRate(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks);

    void execute(Plugin plugin, Runnable runnable);

    void runForPlayer(Plugin plugin, Player player, Runnable runnable);
}
