package twitch.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public class FoliaPluginScheduler implements PluginScheduler {

    private final Server server;

    public FoliaPluginScheduler(Server server) {
        this.server = server;
    }

    @Override
    public CancellableTask runAtFixedRate(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        try {
            Object globalScheduler = invoke(server, "getGlobalRegionScheduler");
            Method runAtFixedRate = globalScheduler.getClass().getMethod(
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class
            );

            Consumer<Object> consumer = taskConsumer(runnable);
            Object scheduledTask = runAtFixedRate.invoke(globalScheduler, plugin, consumer, initialDelayTicks, periodTicks);
            return cancellableFromScheduledTask(scheduledTask);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void execute(Plugin plugin, Runnable runnable) {
        try {
            Object globalScheduler = invoke(server, "getGlobalRegionScheduler");
            Method execute = globalScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            execute.invoke(globalScheduler, plugin, runnable);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void runForPlayer(Plugin plugin, Player player, Runnable runnable) {
        Location location = player.getLocation();
        try {
            Object regionScheduler = invoke(server, "getRegionScheduler");
            Method run = regionScheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            run.invoke(regionScheduler, plugin, location, taskConsumer(runnable));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static Consumer<Object> taskConsumer(Runnable runnable) {
        // Consumer<ScheduledTask> but we avoid referencing the Folia ScheduledTask type.
        return task -> runnable.run();
    }

    private static CancellableTask cancellableFromScheduledTask(Object scheduledTask) {
        return () -> {
            try {
                Method cancel;
                try {
                    cancel = scheduledTask.getClass().getDeclaredMethod("cancel");
                } catch (NoSuchMethodException ignored) {
                    cancel = scheduledTask.getClass().getMethod("cancel");
                }
                cancel.setAccessible(true);
                cancel.invoke(scheduledTask);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                // Best-effort cancellation. Folia may use non-exported internal task types.
            }
        };
    }
}
