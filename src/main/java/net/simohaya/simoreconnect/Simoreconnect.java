package net.simohaya.simoreconnect;

import com.google.inject.Inject;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "simoreconnect",
        name = "simoreconnect",
        version = BuildConstants.VERSION
)
public class Simoreconnect {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ServerMonitor serverMonitor;
    private PluginConfig config;

    private static final String STATE_FILE         = "player_state.json";
    private static final long   RESTORE_WINDOW_SEC = 120;
    private final Gson gson = new Gson();

    // 常時記録: UUID → 現在の接続先サーバー名
    private final Map<String, String> currentServers = new ConcurrentHashMap<>();

    // 再起動後の復元待ち: UUID → 接続先サーバー名
    private Map<String, String> pendingRestore = new ConcurrentHashMap<>();

    @Inject
    public Simoreconnect(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server        = server;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("simoreconnect 起動中...");

        config = new PluginConfig(dataDirectory, logger);
        config.load();

        serverMonitor = new ServerMonitor(server, logger, config);
        serverMonitor.start();

        ServerSelectorBridge bridge = new ServerSelectorBridge(server, logger, config, serverMonitor);
        bridge.register();

        server.getEventManager().register(this,
                new FallbackListener(server, logger, config, serverMonitor, bridge));
        server.getEventManager().register(this, bridge);

        loadPlayerState();

        logger.info("simoreconnect 起動完了！");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // currentServers に全員分が入っているのでそのまま保存
        savePlayerState();
        if (serverMonitor != null) serverMonitor.stop();
        logger.info("simoreconnect 停止しました");
    }

    // プレイヤーがサーバーに接続するたびに記録
    @Subscribe
    public void onServerConnected(com.velocitypowered.api.event.player.ServerConnectedEvent event) {
        String uuid       = event.getPlayer().getUniqueId().toString();
        String serverName = event.getServer().getServerInfo().getName();
        currentServers.put(uuid, serverName);
        logger.info("[Restore] 記録: {} → {} (合計{}人)",
                event.getPlayer().getUsername(), serverName, currentServers.size());
    }

    // 再ログイン時に元のサーバーへ転送
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        String uuid       = event.getPlayer().getUniqueId().toString();
        String targetName = pendingRestore.remove(uuid);
        if (targetName == null) return;

        Optional<RegisteredServer> target = server.getServer(targetName);
        if (target.isEmpty()) {
            logger.warn("[Restore] 復元先サーバー '{}' が見つかりません", targetName);
            return;
        }

        event.setInitialServer(target.get());
        event.getPlayer().sendMessage(Component.text(
                "✔ 前回のサーバー「" + targetName + "」に接続します。", NamedTextColor.GREEN));
        logger.info("[Restore] {} → {}", event.getPlayer().getUsername(), targetName);
    }

    // ─────────────────────────────────────────────────────────────

    private void savePlayerState() {
        Map<String, Object> data = new HashMap<>();
        data.put("shutdownEpoch", System.currentTimeMillis() / 1000L);
        data.put("players", new HashMap<>(currentServers));

        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve(STATE_FILE);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(data, w);
            }
            logger.info("[Restore] {}人分の状態を保存しました。", currentServers.size());
        } catch (IOException e) {
            logger.error("[Restore] 保存失敗: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadPlayerState() {
        Path file = dataDirectory.resolve(STATE_FILE);
        if (!Files.exists(file)) return;

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = gson.fromJson(r,
                    new TypeToken<Map<String, Object>>(){}.getType());

            long shutdownEpoch = ((Number) data.get("shutdownEpoch")).longValue();
            long elapsed       = System.currentTimeMillis() / 1000L - shutdownEpoch;

            if (elapsed > RESTORE_WINDOW_SEC) {
                logger.info("[Restore] 保存データが古すぎます（{}秒前）。スキップ。", elapsed);
                Files.deleteIfExists(file);
                return;
            }

            pendingRestore = new ConcurrentHashMap<>((Map<String, String>) data.get("players"));
            logger.info("[Restore] {}人分の復元データを読み込みました（{}秒前）。",
                    pendingRestore.size(), elapsed);

            Files.deleteIfExists(file);
        } catch (Exception e) {
            logger.error("[Restore] 読み込み失敗: {}", e.getMessage());
        }
    }

    public void reload() {
        logger.info("simoreconnect 設定をリロード中...");
        config.load();
        serverMonitor.reload(config);
        logger.info("simoreconnect リロード完了！");
    }
}