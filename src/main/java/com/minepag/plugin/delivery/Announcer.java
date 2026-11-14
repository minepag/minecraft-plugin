package com.minepag.plugin.delivery;

import com.minepag.plugin.config.Messages;
import com.minepag.plugin.platform.Chat;
import com.minepag.plugin.platform.SchedulerAdapter;
import com.minepag.plugin.protocol.AgentProtocol.Announcement;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Anúncios de compra no chat, com intervalo mínimo entre eles, fila limitada e sem repetir o mesmo anúncio. */
public interface Announcer {
    void enqueue(List<Announcement> announcements);

    void stop();

    int MAX_QUEUED = 20;
    int REMEMBERED_IDS = 500;

    static Announcer create(
            SchedulerAdapter scheduler,
            Chat chat,
            Messages messages,
            boolean enabled,
            long minIntervalSeconds,
            Supplier<String> fallbackStoreUrl) {
        if (!enabled) {
            return new Announcer() {
                @Override
                public void enqueue(List<Announcement> announcements) {}

                @Override
                public void stop() {}
            };
        }

        Object lock = new Object();
        Deque<Announcement> queue = new ArrayDeque<>();
        Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > REMEMBERED_IDS;
            }
        });

        return new Announcer() {
            private boolean draining;
            private boolean stopped;
            private SchedulerAdapter.TaskHandle pending = SchedulerAdapter.NOOP_HANDLE;

            @Override
            public void enqueue(List<Announcement> announcements) {
                synchronized (lock) {
                    if (stopped) return;
                    announcements.stream()
                            .filter(announcement -> seen.add(announcement.id()))
                            .forEach(announcement -> {
                                if (queue.size() < MAX_QUEUED) queue.addLast(announcement);
                            });
                    if (!draining && !queue.isEmpty()) {
                        draining = true;
                        scheduler.runAsync(this::announceNext);
                    }
                }
            }

            @Override
            public void stop() {
                synchronized (lock) {
                    stopped = true;
                    queue.clear();
                    pending.cancel();
                }
            }

            private void announceNext() {
                Announcement next;
                synchronized (lock) {
                    next = stopped ? null : queue.pollFirst();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                String storeUrl = next.storeUrl() != null ? next.storeUrl() : fallbackStoreUrl.get();
                String template = messages.text(storeUrl == null ? "announcement-no-store" : "announcement");
                Map<String, String> placeholders = storeUrl == null
                        ? Map.of("player", next.playerName(), "product", next.productName())
                        : Map.of("player", next.playerName(), "product", next.productName(), "store_url", storeUrl);
                scheduler.runGlobal(() -> chat.broadcast(template, placeholders));
                synchronized (lock) {
                    if (stopped) return;
                    pending = scheduler.runAsyncLater(this::announceNext, minIntervalSeconds * 1000);
                }
            }
        };
    }
}
