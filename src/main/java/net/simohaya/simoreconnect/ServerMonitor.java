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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerMonitor {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;

    private final Map<String, Boolean> serverStatus = new HashMap<>();
    private ScheduledExecutorService scheduler;

    public ServerMonitor(ProxyServer proxy, Logger logger, PluginConfig config) {
        this.proxy  = proxy;
        this.logger = logger;
        this.config = config;

        // 初期状態はオンラインとして扱う（起動直後の誤転送防止）
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
                    // ダウンを検知
                    if (wasOnline) {
                        serverStatus.put(serverName, false);
                        logger.warn("サーバーダウン検知: {}", serverName);
                        handleServerDown(serverName);
                    }
                } else {
                    // 復活を検知
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

        for (Player player : proxy.getAllPlayers()) {
            // 全員にダウン通知（復活通知と同じテイスト）
            player.sendMessage(Component.text(
                    "サーバー [" + downServer + "] がダウンしました！",
                    NamedTextColor.RED
            ));

            // ダウンしたサーバーにいる人だけ lobby に転送
            player.getCurrentServer().ifPresent(conn -> {
                if (conn.getServerInfo().getName().equals(downServer)) {
                    player.sendMessage(Component.text(
                            "lobbyに転送します...",
                            NamedTextColor.RED
                    ));
                    player.createConnectionRequest(lobby).fireAndForget();
                }
            });
        }
    }

    private void handleServerUp(String upServer, RegisteredServer target) {
        // 全員に復活通知
        proxy.getAllPlayers().forEach(p -> p.sendMessage(Component.text(
                "サーバー [" + upServer + "] が復活しました！",
                NamedTextColor.GREEN
        )));

        // delay後にlobbyのプレイヤーを自動転送
        scheduler.schedule(() -> {
            for (Player player : proxy.getAllPlayers()) {
                player.getCurrentServer().ifPresent(conn -> {
                    if (conn.getServerInfo().getName().equals(config.getLobbyServerName())) {
                        player.sendMessage(Component.text(
                                "[" + upServer + "] に自動接続します...",
                                NamedTextColor.YELLOW
                        ));
                        player.createConnectionRequest(target).fireAndForget();
                    }
                });
            }
        }, config.getReconnectDelaySeconds(), TimeUnit.SECONDS);
    }
}