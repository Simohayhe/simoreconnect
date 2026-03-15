package net.simohaya.simoreconnect;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

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

    @Inject
    public Simoreconnect(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server        = server;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("simoreconnect 起動中...");

        PluginConfig config = new PluginConfig(dataDirectory, logger);
        config.load();

        serverMonitor = new ServerMonitor(server, logger, config);
        serverMonitor.start();

        // ServerSelectorBridge を追加
        ServerSelectorBridge bridge = new ServerSelectorBridge(server, logger, config, serverMonitor);
        bridge.register();

        server.getEventManager().register(this, new FallbackListener(server, logger, config, serverMonitor));
        server.getEventManager().register(this, bridge);  // ← 追加

        logger.info("simoreconnect 起動完了！");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (serverMonitor != null) serverMonitor.stop();
        logger.info("simoreconnect 停止しました");
    }
}