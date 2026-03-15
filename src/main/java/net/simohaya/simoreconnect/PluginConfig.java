package net.simohaya.simoreconnect;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class PluginConfig {

    private final Path dataDirectory;
    private final Logger logger;

    private String lobbyServerName       = "lobby";
    private List<String> watchServers    = Arrays.asList("s1", "s2");
    private int checkIntervalSeconds     = 5;
    private int reconnectDelaySeconds    = 3;

    public PluginConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger        = logger;
    }

    public void load() {
        Path configFile = dataDirectory.resolve("config.properties");

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warn("設定ディレクトリの作成に失敗しました", e);
        }

        if (!Files.exists(configFile)) {
            try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                if (in != null) {
                    Files.copy(in, configFile);
                    logger.info("デフォルト設定ファイルを作成しました: {}", configFile);
                }
            } catch (IOException e) {
                logger.warn("デフォルト設定のコピーに失敗しました", e);
            }
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("設定ファイルの読み込みに失敗しました。デフォルト値を使用します", e);
            return;
        }

        lobbyServerName       = props.getProperty("lobby-server", "lobby");
        watchServers          = Arrays.asList(props.getProperty("watch-servers", "s1,s2").split(","));
        checkIntervalSeconds  = Integer.parseInt(props.getProperty("check-interval-seconds", "5"));
        reconnectDelaySeconds = Integer.parseInt(props.getProperty("reconnect-delay-seconds", "3"));

        logger.info("設定ロード完了 — lobby={}, watch={}, interval={}s, reconnect-delay={}s",
                lobbyServerName, watchServers, checkIntervalSeconds, reconnectDelaySeconds);
    }

    public String getLobbyServerName()    { return lobbyServerName; }
    public List<String> getWatchServers() { return watchServers; }
    public int getCheckIntervalSeconds()  { return checkIntervalSeconds; }
    public int getReconnectDelaySeconds() { return reconnectDelaySeconds; }
}