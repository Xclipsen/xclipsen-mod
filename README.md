# Xclipsen Mod

Fabric-Clientmod fuer Minecraft `1.21.10` mit modularen Client-Features.

## Module

- `IRC Bridge`: Discord-/IRC-Nachrichten lokal im Client-Chat, Hypixel-Co-op-Relay und `/irc <nachricht>`.
- `Hideonleaf Helper`: Shulker Glow, Shulker-Linie, Lost-Fight-Alert und konfigurierbare Sounds.
- `Time Changer`: clientseitige Zeit-Presets wie Day, Noon, Sunset, Night, Midnight, Sunrise und Real Time.
- `Status`: Config-Pfad, Backend-Status und HUD-Editor.

## Einrichtung

1. `./gradlew build`
2. Die erzeugte Jar aus `build/libs/` in den `mods/`-Ordner des Clients legen.
3. Minecraft mit Fabric starten.
4. `/xclipsen` oeffnen und die gewuenschten Module aktivieren.
5. Optional `config/xclipsen-mod.json` bearbeiten oder `/irc reload` zum Neuladen ausfuehren.

## Backend-Modus

Dieses Repo enthaelt nur noch den Fabric-Clientmod. Das Backend laeuft in deinem bestehenden Discord-Bot-Projekt `Xclipsen Bot`.

1. Mod-Config in `config/xclipsen-mod.json`:
   - `ircBridgeEnabled = true`
   - `backendBaseUrl = "http://DEIN-SERVER:8765"`
   - `backendAuthToken = "dein-shared-secret"`
2. Im Bot-Projekt `.env` setzen:
   - `IRC_BRIDGE_ENABLED=true`
   - `IRC_BRIDGE_HOST=0.0.0.0`
   - `IRC_BRIDGE_PORT=8765`
   - `IRC_BRIDGE_AUTH_TOKEN=dein-shared-secret`
   - `IRC_BRIDGE_CHANNEL_ID=dein-discord-kanal`
3. Den bestehenden Bot starten. Der Bot ist dann gleichzeitig auch das Backend.

## Discord-Bot

Der Bot braucht mindestens:

- `View Channels`
- `Send Messages`
- `Read Message History`

Und in den Bot-Einstellungen muss `MESSAGE CONTENT INTENT` aktiviert sein, damit Discord-Nachrichten zurueck nach Minecraft gespiegelt werden.
