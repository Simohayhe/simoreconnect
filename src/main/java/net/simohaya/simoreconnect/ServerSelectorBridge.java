package net.simohaya.simoreconnect;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * lobby との Plugin Messaging を担当。
 * - lobby参加時にサーバー一覧+状態を送信
 * - lobby からの転送リクエストを受信して実行
 */
public class ServerSelectorBridge {

    // Velocity → lobby（サーバー一覧送信）
    private static final MinecraftChannelIdentifier SERVER_LIST_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "serverlist");

    // lobby → Velocity（転送リクエスト受信）
    private static final MinecraftChannelIdentifier CONNECT_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "connect");

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final ServerMonitor serverMonitor;

    public ServerSelectorBridge(ProxyServer proxy, Logger logger,
                                PluginConfig config, ServerMonitor serverMonitor) {
        this.proxy         = proxy;
        this.logger        = logger;
        this.config        = config;
        this.serverMonitor = serverMonitor;
    }

    public void register() {
        proxy.getChannelRegistrar().register(SERVER_LIST_CHANNEL);
        proxy.getChannelRegistrar().register(CONNECT_CHANNEL);
        logger.info("Plugin Messaging チャンネル登録完了");
    }

    // -------------------------------------------------------------------
    // lobby 参加時にサーバー一覧を送信
    // -------------------------------------------------------------------

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String serverName = event.getServer().getServerInfo().getName();

        // lobby に参加したときだけ送信
        if (!serverName.equalsIgnoreCase(config.getLobbyServerName())) return;

        Player player = event.getPlayer();

        // 少し遅延させてlobbyのロードを待つ
        proxy.getScheduler().buildTask(getPlugin(), () -> {
            sendServerList(player);
        }).delay(1, java.util.concurrent.TimeUnit.SECONDS).schedule();
    }

    // -------------------------------------------------------------------
    // lobby からの転送リクエストを受信
    // -------------------------------------------------------------------

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CONNECT_CHANNEL)) return;
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection)) return;

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(event.getData()));
            String playerName  = in.readUTF();
            String targetServer = in.readUTF();

            proxy.getPlayer(playerName).ifPresent(player -> {
                proxy.getServer(targetServer).ifPresent(target -> {
                    player.createConnectionRequest(target).fireAndForget();
                    logger.info("{}を{}に転送（GUIリクエスト）", playerName, targetServer);
                });
            });
        } catch (IOException e) {
            logger.error("転送リクエストの読み込みに失敗", e);
        }
    }

    // -------------------------------------------------------------------
    // サーバー一覧を Plugin Messaging で送信
    // -------------------------------------------------------------------

    public void sendServerList(Player player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);

            List<String> watchServers = config.getWatchServers();
            out.writeInt(watchServers.size());

            for (String name : watchServers) {
                boolean online = serverMonitor.isOnline(name);
                int playerCount = proxy.getServer(name)
                        .map(s -> s.getPlayersConnected().size())
                        .orElse(0);

                out.writeUTF(name);
                out.writeBoolean(online);
                out.writeInt(playerCount);
            }

            player.getCurrentServer().ifPresent(conn ->
                    conn.sendPluginMessage(SERVER_LIST_CHANNEL, bytes.toByteArray()));

            logger.debug("サーバー一覧送信: {}", player.getUsername());
        } catch (IOException e) {
            logger.error("サーバー一覧の送信に失敗", e);
        }
    }

    private Object getPlugin() {
        return proxy.getPluginManager()
                .getPlugin("simoreconnect")
                .flatMap(c -> c.getInstance())
                .orElseThrow();
    }
}