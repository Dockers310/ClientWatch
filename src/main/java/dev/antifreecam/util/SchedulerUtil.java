package dev.antifreecam.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Планировщик, совместимый и с Paper, и с Folia.
 *
 * На Paper EntityScheduler/GlobalRegionScheduler работают так же, как и
 * в обычном окружении, а на Folia выполняют работу на правильном регионе.
 */
public final class SchedulerUtil {

    private SchedulerUtil() {}

    public static void onEntity(Plugin plugin, Player player, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            task.run();
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    public static ScheduledTask onEntityLater(Plugin plugin, Player player, long delayTicks, Runnable task) {
        if (delayTicks <= 0 && Bukkit.isOwnedByCurrentRegion(player)) {
            task.run();
            return null;
        }
        return player.getScheduler().runDelayed(plugin, ignored -> task.run(), null, Math.max(1L, delayTicks));
    }

    public static ScheduledTask onEntityTimer(Plugin plugin, Player player,
                                                long initialDelayTicks, long periodTicks,
                                                Consumer<ScheduledTask> task) {
        return player.getScheduler().runAtFixedRate(plugin, task, null,
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    public static ScheduledTask globalOnce(Plugin plugin, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    public static ScheduledTask globalTimer(Plugin plugin, long initialDelayTicks,
                                            long periodTicks, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                ignored -> task.run(),
                Math.max(1L, initialDelayTicks),
                Math.max(1L, periodTicks)
        );
    }

    public static ScheduledTask async(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    public static ScheduledTask asyncLater(Plugin plugin, long delay, TimeUnit unit, Runnable task) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(), delay, unit);
    }
}
