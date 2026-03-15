package net.simohaya.simoreconnect;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerMonitor {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;

    private final Map<String, Boolean> serverStatus = new HashMap<>();

    // プレイヤーUUID → 転送前にいたサーバー名
    private final Map<UUID, String> previousServer = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ServerMonitor(ProxyServer proxy, Logger logger, PluginConfig config) {
        this.proxy  = proxy;
        this.logger = logger;
        this.config = config;

        for (String name : config.getWatchServers()) {
            serverStatus.put(name, true);
        }
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::checkAll,
                config.getCheckIntervalSeconds(),
                config.getCheckIntervalSeconds(),
                TimeUnit.SECONDS
        );
        logger.info("サーバー監視開始（{}秒ごと）", config.getCheckIntervalSeconds());
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void checkAll() {
        for (String serverName : config.getWatchServers()) {
            Optional<RegisteredServer> opt = proxy.getServer(serverName);
            if (opt.isEmpty()) {
                logger.warn("velocity.toml に '{}' が登録されていません", serverName);
                continue;
            }
            RegisteredServer target = opt.get();

            target.ping().whenComplete((ping, ex) -> {
                boolean wasOnline = serverStatus.getOrDefault(serverName, true);

                if (ex != null) {
                    if (wasOnline) {
                        serverStatus.put(serverName, false);
                        logger.warn("サーバーダウン検知: {}", serverName);
                        handleServerDown(serverName);
                    }
                } else {
                    if (!wasOnline) {
                        serverStatus.put(serverName, true);
                        logger.info("サーバー復活検知: {}", serverName);
                        handleServerUp(serverName, target);
                    }
                }
            });
        }
    }

    private void handleServerDown(String downServer) {
        Optional<RegisteredServer> lobbyOpt = proxy.getServer(config.getLobbyServerName());
        if (lobbyOpt.isEmpty()) {
            logger.error("lobbyサーバーが見つかりません: {}", config.getLobbyServerName());
            return;
        }
        RegisteredServer lobby = lobbyOpt.get();

        proxy.getAllPlayers().forEach(p -> p.sendMessage(Component.text(
                "サーバー [" + downServer + "] がダウンしました！",
                NamedTextColor.RED
        )));

        for (Player player : proxy.getAllPlayers()) {
            player.getCurrentServer().ifPresent(conn -> {
                if (!conn.getServerInfo().getName().equals(downServer)) return;

                // 先に記録してから転送
                previousServer.put(player.getUniqueId(), downServer);
                logger.info("元サーバー記録: {} → {}", player.getUsername(), downServer);

                player.sendMessage(Component.text("lobbyに転送します...", NamedTextColor.RED));
                player.createConnectionRequest(lobby).fireAndForget();
            });
        }
    }

    private void handleServerUp(String upServer, RegisteredServer target) {
        proxy.getAllPlayers().forEach(p -> p.sendMessage(Component.text(
                "サーバー [" + upServer + "] が復活しました！",
                NamedTextColor.GREEN
        )));

        scheduler.schedule(() -> {
            for (Player player : proxy.getAllPlayers()) {
                player.getCurrentServer().ifPresent(conn -> {
                    if (!conn.getServerInfo().getName().equals(config.getLobbyServerName())) return;

                    String origin = previousServer.getOrDefault(player.getUniqueId(), "");
                    logger.info("リコネクト判定: {} origin={} upServer={}",
                            player.getUsername(), origin, upServer);

                    // 元いたサーバーが復活したサーバーと一致する場合のみ転送
                    if (!origin.equals(upServer)) return;

                    player.sendMessage(Component.text(
                            "[" + upServer + "] に自動接続します...",
                            NamedTextColor.YELLOW
                    ));
                    player.createConnectionRequest(target).fireAndForget();
                    previousServer.remove(player.getUniqueId());
                });
            }
        }, config.getReconnectDelaySeconds(), TimeUnit.SECONDS);
    }

    // FallbackListener から呼ばれる記録メソッド
    public void recordPreviousServer(UUID uuid, String serverName) {
        previousServer.put(uuid, serverName);
    }

}