package net.simohaya.simoreconnect;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ServerSelectorBridge {

    private static final MinecraftChannelIdentifier SERVER_LIST_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "serverlist");
    private static final MinecraftChannelIdentifier CONNECT_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "connect");
    private static final MinecraftChannelIdentifier REFRESH_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "refresh");
    private static final MinecraftChannelIdentifier KICKED_FROM_CHANNEL =
            MinecraftChannelIdentifier.create("simoreconnect", "kickedfrom");

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
        proxy.getChannelRegistrar().register(REFRESH_CHANNEL);
        proxy.getChannelRegistrar().register(KICKED_FROM_CHANNEL);
        logger.info("Plugin Messaging チャンネル登録完了");
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!event.getServer().getServerInfo().getName()
                .equalsIgnoreCase(config.getLobbyServerName())) return;

        Player player = event.getPlayer();
        proxy.getScheduler().buildTask(getPlugin(), () -> sendServerList(player))
                .delay(1, TimeUnit.SECONDS)
                .schedule();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection conn)) return;

        if (event.getIdentifier().equals(REFRESH_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            sendServerList(conn.getPlayer());
            return;
        }

        if (event.getIdentifier().equals(CONNECT_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            try {
                DataInputStream in = new DataInputStream(
                        new ByteArrayInputStream(event.getData()));
                String playerName   = in.readUTF();
                String targetServer = in.readUTF();

                proxy.getPlayer(playerName).ifPresent(player ->
                        proxy.getServer(targetServer).ifPresent(target -> {
                            player.createConnectionRequest(target).fireAndForget();
                            logger.info("{}を{}に転送（GUIリクエスト）", playerName, targetServer);
                        }));
            } catch (IOException e) {
                logger.error("転送リクエストの読み込みに失敗", e);
            }
        }
    }

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

        } catch (IOException e) {
            logger.error("サーバー一覧の送信に失敗", e);
        }
    }

    // キック元サーバー名をlobbyに通知
    public void notifyKickedFrom(Player player, String serverName) {
        proxy.getScheduler().buildTask(getPlugin(), () -> {
            player.getCurrentServer().ifPresent(conn -> {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    out.writeUTF(serverName);
                    conn.sendPluginMessage(KICKED_FROM_CHANNEL, bytes.toByteArray());
                } catch (IOException e) {
                    logger.error("キック通知の送信に失敗", e);
                }
            });
        }).delay(2, TimeUnit.SECONDS).schedule();
    }

    private Object getPlugin() {
        return proxy.getPluginManager()
                .getPlugin("simoreconnect")
                .flatMap(c -> c.getInstance())
                .orElseThrow();
    }
}