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

    public FallbackListener(ProxyServer proxy, Logger logger, PluginConfig config) {
        this.proxy  = proxy;
        this.logger = logger;
        this.config = config;
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