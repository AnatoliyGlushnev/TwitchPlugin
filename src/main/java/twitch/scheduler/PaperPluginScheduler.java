package twitch.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class PaperPluginScheduler implements PluginScheduler {

    @Override
    public CancellableTask runAtFixedRate(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, initialDelayTicks, periodTicks);
        return task::cancel;
    }

    @Override
    public void execute(Plugin plugin, Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    @Override
    public void runForPlayer(Plugin plugin, Player player, Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}
