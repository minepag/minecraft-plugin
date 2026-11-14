package com.minepag.plugin.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Implementação com os schedulers regionalizados (Paper 1.20+ e Folia). Fica isolada nesta classe para que o Spigot
 * nunca carregue as classes do Paper.
 */
final class RegionSchedulerAdapter {
    private RegionSchedulerAdapter() {}

    static SchedulerAdapter create(Plugin plugin) {
        return new SchedulerAdapter() {
            @Override
            public void runAsync(Runnable task) {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
            }

            @Override
            public TaskHandle runAsyncLater(Runnable task, long delayMillis) {
                if (delayMillis <= 0) {
                    runAsync(task);
                    return NOOP_HANDLE;
                }
                ScheduledTask scheduled = Bukkit.getAsyncScheduler()
                        .runDelayed(plugin, ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
                return scheduled::cancel;
            }

            @Override
            public void runGlobal(Runnable task) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            }

            @Override
            public void runForPlayer(Player player, Runnable task, Runnable retired) {
                if (player.getScheduler().run(plugin, scheduled -> task.run(), retired) == null) retired.run();
            }

            @Override
            public void cancelAll() {
                Bukkit.getAsyncScheduler().cancelTasks(plugin);
                Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            }
        };
    }
}
