package com.minepag.plugin.platform;

import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Agendamento independente da plataforma. Paper e Folia usam os schedulers regionalizados; Spigot usa o
 * BukkitScheduler. A escolha é feita por reflexão para que o mesmo jar rode nas três.
 */
public interface SchedulerAdapter {
    @FunctionalInterface
    interface TaskHandle {
        void cancel();
    }

    TaskHandle NOOP_HANDLE = () -> {};

    void runAsync(Runnable task);

    TaskHandle runAsyncLater(Runnable task, long delayMillis);

    /** Thread global (Folia) ou principal (Paper/Spigot): use para dispatch de comandos do console e broadcast. */
    void runGlobal(Runnable task);

    /** Thread dona do jogador. {@code retired} roda se o jogador sair antes da tarefa executar. */
    void runForPlayer(Player player, Runnable task, Runnable retired);

    void cancelAll();

    default Executor asyncExecutor() {
        return this::runAsync;
    }

    default Executor globalExecutor() {
        return this::runGlobal;
    }

    String REGION_SCHEDULER_CLASS = "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";

    static SchedulerAdapter create(Plugin plugin) {
        return ServerPlatform.classExists(REGION_SCHEDULER_CLASS) ? RegionSchedulerAdapter.create(plugin) : bukkit(plugin);
    }

    private static SchedulerAdapter bukkit(Plugin plugin) {
        return new SchedulerAdapter() {
            @Override
            public void runAsync(Runnable task) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }

            @Override
            public TaskHandle runAsyncLater(Runnable task, long delayMillis) {
                long ticks = Math.max(1, (delayMillis + 49) / 50);
                BukkitTask scheduled = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
                return scheduled::cancel;
            }

            @Override
            public void runGlobal(Runnable task) {
                Bukkit.getScheduler().runTask(plugin, task);
            }

            @Override
            public void runForPlayer(Player player, Runnable task, Runnable retired) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) task.run();
                    else retired.run();
                });
            }

            @Override
            public void cancelAll() {
                Bukkit.getScheduler().cancelTasks(plugin);
            }
        };
    }
}
