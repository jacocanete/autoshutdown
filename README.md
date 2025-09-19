# AutoShutdown Plugin

A Velocity plugin that automatically starts and stops Minecraft servers via Pterodactyl Panel API.

## What it does

- **Auto-start**: When a player joins and the main server is offline, starts the server via Pterodactyl API
- **Auto-shutdown**: When the main server has no players for a configurable time, shuts it down to save resources
- **Seamless experience**: Players are handled by existing limbo/reconnection plugins during startup

## Commands

All commands require `autoshutdown.admin` permission.

- `/autoshutdown` - Show help
- `/autoshutdown shutdown` - Immediately shutdown server (5 second delay)
- `/autoshutdown reload` - Reload configuration file
- `/autoshutdown status` - Show server status and plugin info
- `/autoshutdown timer` - Show time remaining until auto-shutdown

## Configuration

Edit `plugins/autoshutdown/config.properties`:

```properties
# Pterodactyl Panel Settings
pterodactyl.url=http://192.168.1.2:1180
pterodactyl.api-key=ptlc_your_client_api_key_here
pterodactyl.server-id=your-server-id

# Main Server Settings
main-server.name=main
main-server.host=localhost
main-server.port=25565

# Limbo Server Settings
limbo-server.name=limbo

# Auto-shutdown Settings
auto-shutdown.enabled=true
auto-shutdown.delay-seconds=300
auto-shutdown.check-interval-seconds=60
```

## Requirements

- Velocity proxy server
- Pterodactyl Panel with client API access
- Existing limbo/reconnection plugin setup

## Limitations

This plugin is custom-built for a specific server setup with Velocity proxy, Pterodactyl Panel, and existing limbo handling. It may require modifications for other configurations.