package com.jacocanete.autoshutdown;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(
    id = "autoshutdown",
    name = "AutoShutdown",
    version = "1.1.2",
    description = "Automatically starts servers when players join",
    authors = {"jacocanete"}
)
public class AutoShutdownPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PterodactylAPI pterodactylAPI;
    private String mainServerName;
    private String limboServerName;
    private String mainServerHost;
    private int mainServerPort;
    private String pterodactylServerId;
    private boolean startupInProgress = false;

    private boolean autoShutdownEnabled;
    private int autoShutdownDelay;
    private int autoShutdownCheckInterval;
    private long lastPlayerLeftTime = 0;
    private ScheduledTask autoShutdownTask;
    private ScheduledTask startupMonitoringTask;
    private volatile boolean isShuttingDown = false;

    @Inject
    public AutoShutdownPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("AutoShutdown plugin is starting...");

        try {
            loadConfig();
            logRegisteredServers();
            startAutoShutdownMonitoring();
            registerCommands();
            logger.info("AutoShutdown plugin loaded successfully!");
        } catch (IOException e) {
            logger.error("Failed to load configuration!", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("AutoShutdown plugin shutting down...");
        isShuttingDown = true;

        // Cancel all tasks immediately
        if (autoShutdownTask != null) {
            autoShutdownTask.cancel();
            autoShutdownTask = null;
        }
        if (startupMonitoringTask != null) {
            startupMonitoringTask.cancel();
            startupMonitoringTask = null;
        }

        // Reset flags
        startupInProgress = false;
        lastPlayerLeftTime = 0;

        if (pterodactylAPI != null) {
            pterodactylAPI.shutdown();
        }

        logger.info("AutoShutdown plugin shutdown complete");
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        String playerName = event.getPlayer().getUsername();
        logger.info("Player '{}' joined the proxy, checking main server status...", playerName);

        if (startupInProgress) {
            logger.info("Server startup in progress, connecting '{}' to limbo server", playerName);
            event.getPlayer().sendMessage(
                Component.text("Server is starting up, please wait...")
                    .color(NamedTextColor.YELLOW)
            );
            return;
        }

        // Check if main server is online
        server.getScheduler()
            .buildTask(this, () -> {
                boolean isOnline = ServerPinger.isServerOnline(mainServerHost, mainServerPort);

                if (!isOnline) {
                    logger.info("Main server is offline, triggering startup for player '{}'", playerName);
                    startMainServer(playerName);
                } else {
                    logger.info("Main server is online, player '{}' can connect normally", playerName);
                }
            })
            .schedule();
    }

    private void startMainServer(String playerName) {
        if (startupInProgress) {
            return;
        }

        startupInProgress = true;

        // Cancel any existing startup monitoring
        if (startupMonitoringTask != null) {
            startupMonitoringTask.cancel();
        }

        logger.info("Player '{}' triggered server startup - limbo plugin will handle connection", playerName);

        // Start the server via Pterodactyl API
        pterodactylAPI.startServer(pterodactylServerId)
            .thenAccept(success -> {
                if (success) {
                    logger.info("Successfully sent start command to main server");

                    // Notify player AFTER API call succeeds
                    server.getPlayer(playerName).ifPresent(player -> {
                        player.sendMessage(
                            Component.text("Main server is offline. Starting server...")
                                .color(NamedTextColor.YELLOW)
                        );
                    });

                    // Wait for server to come online (check every 5 seconds for up to 2 minutes)
                    startupMonitoringTask = server.getScheduler()
                        .buildTask(this, new ServerStartupChecker(playerName))
                        .delay(5, TimeUnit.SECONDS)
                        .schedule();
                } else {
                    logger.error("Failed to start main server via Pterodactyl API");
                    startupInProgress = false;

                    server.getPlayer(playerName).ifPresent(player -> {
                        player.sendMessage(
                            Component.text("Failed to start main server. Please contact an administrator.")
                                .color(NamedTextColor.RED)
                        );
                    });
                }
            });
    }

    private class ServerStartupChecker implements Runnable {
        private final String playerName;
        private int attempts = 0;
        private final int maxAttempts = 24; // 2 minutes with 5-second intervals

        public ServerStartupChecker(String playerName) {
            this.playerName = playerName;
        }

        @Override
        public void run() {
            // Stop if plugin is shutting down
            if (isShuttingDown) {
                startupInProgress = false;
                startupMonitoringTask = null;
                return;
            }

            attempts++;

            boolean isOnline = ServerPinger.isServerOnline(mainServerHost, mainServerPort);

            if (isOnline) {
                logger.info("Main server is now online!");
                startupInProgress = false;
                startupMonitoringTask = null; // Clear the reference

                server.getPlayer(playerName).ifPresent(player -> {
                    player.sendMessage(
                        Component.text("Server is online! Preparing to connect you...")
                            .color(NamedTextColor.GREEN)
                    );
                });

                // Task will automatically stop since it's not rescheduled
                return;
            }

            // Check if player is still online - if not, stop monitoring
            if (server.getPlayer(playerName).isEmpty()) {
                logger.info("Player '{}' disconnected, stopping server startup monitoring", playerName);
                startupInProgress = false;
                startupMonitoringTask = null;
                return;
            }

            if (attempts >= maxAttempts) {
                logger.error("Server failed to start within the timeout period");
                startupInProgress = false;
                startupMonitoringTask = null;

                server.getPlayer(playerName).ifPresent(player -> {
                    player.sendMessage(
                        Component.text("Server startup timed out. Please contact an administrator.")
                            .color(NamedTextColor.RED)
                    );
                });

                return;
            }

            // Only reschedule if not shutting down and task hasn't been cancelled
            if (!isShuttingDown && startupMonitoringTask != null) {
                startupMonitoringTask = server.getScheduler()
                    .buildTask(AutoShutdownPlugin.this, this)
                    .delay(5, TimeUnit.SECONDS)
                    .schedule();
            }
        }
    }

    private void loadConfig() throws IOException {
        if (!Files.exists(dataDirectory)) {
            Files.createDirectories(dataDirectory);
        }

        Path configFile = dataDirectory.resolve("config.properties");

        if (!Files.exists(configFile)) {
            // Create default config file
            try (InputStream defaultConfig = getClass().getResourceAsStream("/config.properties")) {
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile);
                } else {
                    // Create a basic config file
                    Properties defaultProps = new Properties();
                    defaultProps.setProperty("pterodactyl.url", "https://your-pterodactyl-panel.com");
                    defaultProps.setProperty("pterodactyl.api-key", "ptlc_your_api_key_here");
                    defaultProps.setProperty("pterodactyl.server-id", "your-server-id");
                    defaultProps.setProperty("main-server.name", "main");
                    defaultProps.setProperty("main-server.host", "localhost");
                    defaultProps.setProperty("main-server.port", "25565");
                    defaultProps.setProperty("limbo-server.name", "limbo");

                    defaultProps.store(Files.newOutputStream(configFile), "AutoShutdown Plugin Configuration");
                }
            }

            logger.warn("Created default configuration file. Please edit " + configFile + " with your settings!");
            return;
        }

        Properties config = new Properties();
        config.load(Files.newInputStream(configFile));

        String pterodactylUrl = config.getProperty("pterodactyl.url");
        String pterodactylApiKey = config.getProperty("pterodactyl.api-key");
        pterodactylServerId = config.getProperty("pterodactyl.server-id");
        mainServerName = config.getProperty("main-server.name");
        mainServerHost = config.getProperty("main-server.host");
        mainServerPort = Integer.parseInt(config.getProperty("main-server.port"));
        limboServerName = config.getProperty("limbo-server.name");

        // Auto-shutdown settings
        autoShutdownEnabled = Boolean.parseBoolean(config.getProperty("auto-shutdown.enabled", "false"));
        autoShutdownDelay = Integer.parseInt(config.getProperty("auto-shutdown.delay-seconds", "300"));
        autoShutdownCheckInterval = Integer.parseInt(config.getProperty("auto-shutdown.check-interval-seconds", "60"));

        if (pterodactylUrl == null || pterodactylApiKey == null || pterodactylServerId == null ||
            mainServerName == null || mainServerHost == null || limboServerName == null) {
            throw new IOException("Missing required configuration values!");
        }

        pterodactylAPI = new PterodactylAPI(pterodactylUrl, pterodactylApiKey);

        // Validate configuration
        validateConfiguration();
    }

    private void logRegisteredServers() {
        String serverList = server.getAllServers().stream()
            .map(registeredServer -> registeredServer.getServerInfo().getName())
            .collect(Collectors.joining(", "));

        logger.info("Registered servers: [{}]", serverList);

        // Validate server names exist
        validateServerNames();
    }

    private void validateConfiguration() {
        logger.info("Validating Pterodactyl API configuration...");
        logger.info("Panel URL: {}", pterodactylServerId != null ? "Configured" : "NOT SET");
        logger.info("API Key: {}", pterodactylServerId != null ? "Configured (hidden)" : "NOT SET");
        logger.info("Server ID: {}", pterodactylServerId != null ? pterodactylServerId : "NOT SET");

        // Test API connection
        pterodactylAPI.getServerStatus(pterodactylServerId)
            .thenAccept(status -> {
                if (status != null && !status.equals("offline")) {
                    logger.info("✓ Pterodactyl API connection successful - Server status: {}", status);
                } else {
                    logger.warn("⚠ Pterodactyl API connection issues - check API key and server ID");
                }
            })
            .exceptionally(throwable -> {
                logger.error("✗ Pterodactyl API connection failed - check URL, API key, and server ID");
                return null;
            });
    }

    private void validateServerNames() {
        // Check main server
        RegisteredServer mainServer = server.getServer(mainServerName).orElse(null);
        if (mainServer != null) {
            logger.info("✓ Main server '{}' found in Velocity configuration", mainServerName);
        } else {
            logger.error("✗ Main server '{}' NOT found in Velocity configuration!", mainServerName);
            logger.error("Available servers: {}", server.getAllServers().stream()
                .map(s -> s.getServerInfo().getName())
                .collect(Collectors.joining(", ")));
        }

        // Check limbo server
        RegisteredServer limboServer = server.getServer(limboServerName).orElse(null);
        if (limboServer != null) {
            logger.info("✓ Limbo server '{}' found in Velocity configuration", limboServerName);
        } else {
            logger.warn("⚠ Limbo server '{}' NOT found in Velocity configuration", limboServerName);
        }
    }

    private void startAutoShutdownMonitoring() {
        if (!autoShutdownEnabled) {
            logger.info("Auto-shutdown is disabled");
            return;
        }

        logger.info("Starting auto-shutdown monitoring (delay: {}s, check interval: {}s)",
                   autoShutdownDelay, autoShutdownCheckInterval);
        logger.info("Will monitor '{}' server for empty status", mainServerName);

        autoShutdownTask = server.getScheduler()
            .buildTask(this, this::checkForAutoShutdown)
            .repeat(autoShutdownCheckInterval, TimeUnit.SECONDS)
            .schedule();
    }

    private void checkForAutoShutdown() {
        if (!autoShutdownEnabled || isShuttingDown) {
            return;
        }

        // Check if main server is online first
        boolean mainServerOnline = ServerPinger.isServerOnline(mainServerHost, mainServerPort);
        if (!mainServerOnline) {
            return; // Server is already offline
        }

        // Get player count from proxy for the main server
        RegisteredServer mainServer = server.getServer(mainServerName).orElse(null);
        int playerCount = PlayerCountChecker.getPlayerCountFromProxy(mainServer);

        if (playerCount > 0) {
            // Players are online, reset timer
            if (lastPlayerLeftTime != 0) {
                logger.info("Players detected on main server ({}), cancelling auto-shutdown", playerCount);
            } else {
                logger.debug("Main server has {} players online, auto-shutdown not needed", playerCount);
            }
            lastPlayerLeftTime = 0;
            return;
        }

        // No players online
        long currentTime = System.currentTimeMillis();

        if (lastPlayerLeftTime == 0) {
            // First time we detected empty server
            lastPlayerLeftTime = currentTime;
            logger.info("Main server is empty (0 players), starting auto-shutdown timer ({} seconds)", autoShutdownDelay);
            return;
        }

        // Check if enough time has passed
        long timeSinceEmpty = (currentTime - lastPlayerLeftTime) / 1000;
        if (timeSinceEmpty >= autoShutdownDelay) {
            logger.info("No players in server, shutting down now (empty for {} seconds)", timeSinceEmpty);

            pterodactylAPI.stopServer(pterodactylServerId)
                .thenAccept(success -> {
                    if (success) {
                        logger.info("Successfully sent shutdown command to main server via Pterodactyl API");
                        lastPlayerLeftTime = 0; // Reset timer
                    } else {
                        logger.error("Failed to shutdown main server via Pterodactyl API - check credentials and server ID");
                    }
                });
        } else {
            long timeRemaining = autoShutdownDelay - timeSinceEmpty;
            logger.info("No players in server, shutting down in {} seconds", timeRemaining);
        }
    }

    private void registerCommands() {
        server.getCommandManager().register("autoshutdown", new AutoShutdownCommand());
        logger.info("Registered /autoshutdown command");
    }

    private class AutoShutdownCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (!source.hasPermission("autoshutdown.admin")) {
                source.sendMessage(Component.text("You don't have permission to use this command.")
                    .color(NamedTextColor.RED));
                return;
            }

            if (args.length == 0) {
                sendHelp(source);
                return;
            }

            switch (args[0].toLowerCase()) {
                case "shutdown":
                    handleShutdownCommand(source);
                    break;
                case "reload":
                    handleReloadCommand(source);
                    break;
                case "status":
                    handleStatusCommand(source);
                    break;
                case "timer":
                    handleTimerCommand(source);
                    break;
                default:
                    sendHelp(source);
                    break;
            }
        }

        private void handleShutdownCommand(CommandSource source) {
            source.sendMessage(Component.text("Initiating immediate server shutdown in 5 seconds...")
                .color(NamedTextColor.YELLOW));
            logger.info("Immediate shutdown commanded by {}", source);

            server.getScheduler()
                .buildTask(AutoShutdownPlugin.this, () -> {
                    pterodactylAPI.stopServer(pterodactylServerId)
                        .thenAccept(success -> {
                            if (success) {
                                logger.info("Manual shutdown command sent successfully");
                                source.sendMessage(Component.text("Shutdown command sent to server")
                                    .color(NamedTextColor.GREEN));
                            } else {
                                logger.error("Manual shutdown command failed");
                                source.sendMessage(Component.text("Failed to shutdown server - check API connection")
                                    .color(NamedTextColor.RED));
                            }
                        });
                })
                .delay(5, TimeUnit.SECONDS)
                .schedule();
        }

        private void handleReloadCommand(CommandSource source) {
            source.sendMessage(Component.text("Reloading configuration...")
                .color(NamedTextColor.YELLOW));

            try {
                // Stop current monitoring
                if (autoShutdownTask != null) {
                    autoShutdownTask.cancel();
                }

                // Reload config
                loadConfig();

                // Restart monitoring
                startAutoShutdownMonitoring();

                source.sendMessage(Component.text("Configuration reloaded successfully!")
                    .color(NamedTextColor.GREEN));
                logger.info("Configuration reloaded by {}", source);

            } catch (IOException e) {
                source.sendMessage(Component.text("Failed to reload configuration: " + e.getMessage())
                    .color(NamedTextColor.RED));
                logger.error("Failed to reload configuration", e);
            }
        }

        private void handleStatusCommand(CommandSource source) {
            source.sendMessage(Component.text("=== AutoShutdown Status ===").color(NamedTextColor.GOLD));

            // Show configuration details
            source.sendMessage(Component.text("Checking: " + mainServerHost + ":" + mainServerPort)
                .color(NamedTextColor.GRAY));

            RegisteredServer mainServer = server.getServer(mainServerName).orElse(null);
            int playerCount = PlayerCountChecker.getPlayerCountFromProxy(mainServer);

            source.sendMessage(Component.text("Players: " + playerCount).color(NamedTextColor.BLUE));
            source.sendMessage(Component.text("Auto-shutdown: " + (autoShutdownEnabled ? "ENABLED" : "DISABLED"))
                .color(autoShutdownEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));

            // Debug info
            if (mainServer != null) {
                String actualHost = mainServer.getServerInfo().getAddress().getHostString();
                int actualPort = mainServer.getServerInfo().getAddress().getPort();
                source.sendMessage(Component.text("Velocity config: " + actualHost + ":" + actualPort)
                    .color(NamedTextColor.GRAY));
            }

            // Check server status asynchronously to avoid blocking
            source.sendMessage(Component.text("Checking server status...").color(NamedTextColor.YELLOW));

            server.getScheduler()
                .buildTask(AutoShutdownPlugin.this, () -> {
                    logger.info("Checking server status: {}:{}", mainServerHost, mainServerPort);

                    // First check basic TCP connection
                    boolean canConnect = ServerPinger.canConnect(mainServerHost, mainServerPort, 2000);
                    logger.info("TCP connection test: {}", canConnect ? "SUCCESS" : "FAILED");

                    if (!canConnect) {
                        source.sendMessage(Component.text("Main Server: OFFLINE (no TCP connection)")
                            .color(NamedTextColor.RED));
                        return;
                    }

                    // Then try full MC ping
                    boolean serverOnline = ServerPinger.isServerOnline(mainServerHost, mainServerPort, 2000);
                    logger.info("Minecraft ping test: {}", serverOnline ? "SUCCESS" : "FAILED");

                    source.sendMessage(Component.text("Main Server: " + (serverOnline ? "ONLINE" : "OFFLINE"))
                        .color(serverOnline ? NamedTextColor.GREEN : NamedTextColor.RED));
                })
                .schedule();
        }

        private void handleTimerCommand(CommandSource source) {
            if (!autoShutdownEnabled) {
                source.sendMessage(Component.text("Auto-shutdown is disabled").color(NamedTextColor.RED));
                return;
            }

            if (lastPlayerLeftTime == 0) {
                source.sendMessage(Component.text("No shutdown timer active - players are online or server is offline")
                    .color(NamedTextColor.BLUE));
                return;
            }

            long timeSinceEmpty = (System.currentTimeMillis() - lastPlayerLeftTime) / 1000;
            long timeRemaining = autoShutdownDelay - timeSinceEmpty;

            if (timeRemaining > 0) {
                source.sendMessage(Component.text("Server will shutdown in " + timeRemaining + " seconds")
                    .color(NamedTextColor.YELLOW));
            } else {
                source.sendMessage(Component.text("Shutdown should be imminent...")
                    .color(NamedTextColor.RED));
            }
        }

        private void sendHelp(CommandSource source) {
            source.sendMessage(Component.text("=== AutoShutdown Commands ===").color(NamedTextColor.GOLD));
            source.sendMessage(Component.text("/autoshutdown shutdown - Immediately shutdown server (5s delay)")
                .color(NamedTextColor.GRAY));
            source.sendMessage(Component.text("/autoshutdown reload - Reload plugin configuration")
                .color(NamedTextColor.GRAY));
            source.sendMessage(Component.text("/autoshutdown status - Show server and plugin status")
                .color(NamedTextColor.GRAY));
            source.sendMessage(Component.text("/autoshutdown timer - Show time until auto-shutdown")
                .color(NamedTextColor.GRAY));
        }
    }
}