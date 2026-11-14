package com.minepag.plugin;

import com.minepag.plugin.command.MinePagCommand;
import com.minepag.plugin.config.Credentials;
import com.minepag.plugin.config.PluginSettings;
import com.minepag.plugin.delivery.Announcer;
import com.minepag.plugin.delivery.CommandPolicy;
import com.minepag.plugin.delivery.DeliveryExecutor;
import com.minepag.plugin.delivery.Journal;
import com.minepag.plugin.platform.Chat;
import com.minepag.plugin.platform.SchedulerAdapter;
import com.minepag.plugin.platform.ServerPlatform;
import com.minepag.plugin.polling.Poller;
import com.minepag.plugin.polling.Poller.ServerInfo;
import com.minepag.plugin.polling.Poller.ServerSnapshot;
import com.minepag.plugin.protocol.AgentProtocol;
import com.minepag.plugin.protocol.AgentProtocol.OnlinePlayer;
import com.minepag.plugin.protocol.ApiClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Ponto de entrada exigido pelo Bukkit. Só compõe as peças; a lógica fica nos módulos. */
public final class MinePagPlugin extends JavaPlugin {
    /** Tudo o que depende do config.yml e das credenciais; é recriado inteiro no reload e no pareamento. */
    public record Session(PluginSettings settings, ApiClient api, Optional<Poller> poller, Announcer announcer) {}

    private static final long SNAPSHOT_TIMEOUT_SECONDS = 5;

    private final Clock clock = Clock.systemUTC();
    private final Listener joinListener = new Listener() {};
    private SchedulerAdapter scheduler;
    private Chat chat;
    private Journal journal;
    private ServerInfo serverInfo;
    private volatile Optional<Credentials> credentials = Optional.empty();
    private volatile Session session;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String platform = ServerPlatform.detect();
        serverInfo = new ServerInfo(
                AgentProtocol.truncate(getDescription().getVersion(), AgentProtocol.MAX_VERSION_LENGTH),
                platform,
                ServerPlatform.minecraftVersion(),
                ServerPlatform.javaVersion());
        scheduler = SchedulerAdapter.create(this);
        chat = Chat.create();

        try {
            journal = Journal.open(dataDirectory().resolve("journal.db"));
        } catch (IllegalStateException failure) {
            getLogger().severe(failure.getMessage() + " O plugin foi desativado para não arriscar entregas duplicadas.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        int orphans = journal.markOrphansAsUnknown(clock.instant());
        if (orphans > 0) {
            getLogger().warning(orphans + " comando(s) foram interrompidos por uma parada do servidor e serão reportados "
                    + "como resultado desconhecido. Confira essas entregas no painel.");
        }

        credentials = Credentials.load(credentialsFile());
        PluginCommand command = Objects.requireNonNull(getCommand("minepag"), "comando minepag ausente no plugin.yml");
        var executor = MinePagCommand.create(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvent(
                PlayerJoinEvent.class,
                joinListener,
                EventPriority.MONITOR,
                (listener, event) -> {
                    Session current = session;
                    if (event instanceof PlayerJoinEvent join && current != null) {
                        current.poller().ifPresent(poller -> poller.playerJoined(join.getPlayer().getUniqueId()));
                    }
                },
                this,
                true);

        rebuildSession();
        getLogger().info("MinePag " + serverInfo.pluginVersion() + " ativo em " + platform + " " + serverInfo.mcVersion() + ".");
    }

    @Override
    public void onDisable() {
        if (session != null) stopSession(session);
        HandlerList.unregisterAll(joinListener);
        if (scheduler != null) scheduler.cancelAll();
        if (journal != null) journal.close();
    }

    public void reload() {
        reloadConfig();
        credentials = Credentials.load(credentialsFile());
        rebuildSession();
    }

    public void applyPairing(Credentials paired) throws IOException {
        paired.save(credentialsFile());
        credentials = Optional.of(paired);
        rebuildSession();
    }

    /** Esquece o pareamento local (depois de revogar o token na MinePag) e para as entregas. */
    public void clearPairing() throws IOException {
        Files.deleteIfExists(credentialsFile());
        credentials = Optional.empty();
        rebuildSession();
    }

    private synchronized void rebuildSession() {
        if (session != null) stopSession(session);
        PluginSettings settings = PluginSettings.from(getConfig(), getLogger());
        ApiClient api = ApiClient.create(
                settings.apiUrl(),
                () -> credentials.map(Credentials::token),
                "MinePag-Plugin/" + serverInfo.pluginVersion() + " (" + serverInfo.platform() + " " + serverInfo.mcVersion() + ")");
        Announcer announcer = Announcer.create(
                scheduler,
                chat,
                settings.messages(),
                settings.announcementsEnabled(),
                settings.announcementMinIntervalSeconds(),
                () -> credentials.map(Credentials::storeUrl).orElse(null));
        Optional<Poller> poller = credentials.map(paired -> Poller.create(
                api,
                journal,
                DeliveryExecutor.create(
                        scheduler,
                        journal,
                        CommandPolicy.create(settings.blockedCommands()),
                        chat,
                        settings.messages(),
                        clock,
                        getLogger(),
                        settings.debug()),
                announcer,
                scheduler,
                serverInfo,
                this::snapshot,
                settings.pollIntervalSeconds(),
                settings.checkOnJoin(),
                clock,
                getLogger(),
                settings.debug()));

        session = new Session(settings, api, poller, announcer);
        poller.ifPresentOrElse(
                Poller::start,
                () -> getLogger().warning("Servidor ainda não pareado. Gere um código em Entregas › Minecraft no painel e use "
                        + "/minepag pair <código> no console."));
    }

    private void stopSession(Session current) {
        current.poller().ifPresent(Poller::stop);
        current.announcer().stop();
        current.api().shutdown();
    }

    /** Jogadores online coletados na thread global. Nicks fora do padrão (Java ou Bedrock com prefixo {@code .}) ficam de fora do pull. */
    private ServerSnapshot snapshot() {
        CompletableFuture<ServerSnapshot> future = new CompletableFuture<>();
        scheduler.runGlobal(() -> {
            try {
                var players = Bukkit.getOnlinePlayers();
                future.complete(new ServerSnapshot(
                        players.stream()
                                .filter(player -> AgentProtocol.NICKNAME_PATTERN.matcher(player.getName()).matches())
                                .limit(AgentProtocol.MAX_ONLINE_PLAYERS)
                                .map(player -> new OnlinePlayer(player.getUniqueId().toString(), player.getName()))
                                .toList(),
                        players.size(),
                        Bukkit.getMaxPlayers(),
                        ServerPlatform.tps(serverInfo.platform())));
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Coleta de jogadores interrompida.", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Não foi possível coletar os jogadores online a tempo.", failure);
        }
    }

    public Path dataDirectory() {
        return getDataFolder().toPath().resolve("data");
    }

    private Path credentialsFile() {
        return dataDirectory().resolve("credentials.yml");
    }

    public Session session() {
        return session;
    }

    public Optional<Credentials> credentials() {
        return credentials;
    }

    public ServerInfo serverInfo() {
        return serverInfo;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public Chat chat() {
        return chat;
    }

    public Journal journal() {
        return journal;
    }
}
