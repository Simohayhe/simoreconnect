package net.simohaya.simoreconnect;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Optional;

public class FallbackListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final ServerMonitor serverMonitor;

    public FallbackListener(ProxyServer proxy, Logger logger, PluginConfig config, ServerMonitor serverMonitor) {
        this.proxy         = proxy;
        this.logger        = logger;
        this.config        = config;
        this.serverMonitor = serverMonitor;
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String kickedFrom = event.getServer().getServerInfo().getName();

        if (!config.getWatchServers().contains(kickedFrom)) return;

        Optional<RegisteredServer> lobbyOpt = proxy.getServer(config.getLobbyServerName());
        if (lobbyOpt.isEmpty()) {
            logger.error("lobbyサーバーが見つかりません: {}", config.getLobbyServerName());
            return;
        }

        // キック時に元サーバーを記録
        serverMonitor.recordPreviousServer(event.getPlayer().getUniqueId(), kickedFrom);
        logger.info("元サーバー記録（キック時）: {} → {}", event.getPlayer().getUsername(), kickedFrom);

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                lobbyOpt.get(),
                Component.text(
                        "サーバー [" + kickedFrom + "] から切断されました。lobbyに転送します...",
                        NamedTextColor.RED
                )
        ));

        logger.info("{}が{}からキックされたためlobbyに転送",
                event.getPlayer().getUsername(), kickedFrom);
    }
}