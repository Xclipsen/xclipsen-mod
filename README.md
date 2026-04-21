# Xclipsen IRC Bridge

Fabric-Clientmod fuer Minecraft `1.21.10`, die einen einfachen IRC-aehnlichen Chatkanal mit Discord bridged.

## Features

- zeigt Discord- und IRC-Nachrichten lokal im Client-Chat an
- spiegelt Hypixel-Co-op-Chat automatisch nach Discord/IRC sobald ein verlinkter Client online ist
- `/irc <nachricht>` als Client-Befehl, ganz ohne Minecraft-Servermod
- `/xclipsen` oder `/irc config` oeffnet die ClickGUI-aehnliche Settings-Oberflaeche
- `/irc reload` zum Neuladen der JSON-Konfiguration
- Shulker und Shulker-Projektile bekommen clientseitig einen Glow-Outline-Effekt, der durch Waende sichtbar ist
- `/shulkerglow on|off|toggle` oder `/irc shulkerglow on|off|toggle` zum Umschalten des Shulker-Glows
- optionaler Backend-Modus, damit die Discord-Verbindung als separater Dienst dauerhaft online bleibt

## Einrichtung

1. `./gradlew build`
2. Die erzeugte Jar aus `build/libs/` in den `mods/`-Ordner des Clients legen.
3. Minecraft mit Fabric starten.
4. `config/xclipsen-irc-bridge.json` ausfuellen:
   - `bridgeMode`: `backend`
   - `backendBaseUrl`: URL deines bestehenden Bots, z. B. `http://127.0.0.1:8765`
   - `backendAuthToken`: shared secret, das auch im Bot gesetzt ist
5. Client neu starten oder `/irc reload` ausfuehren.

## Backend-Modus

Dieses Repo enthaelt nur noch den Fabric-Clientmod. Das Backend laeuft in deinem bestehenden Discord-Bot-Projekt `Xclipsen Bot`.

1. Mod-Config in `config/xclipsen-irc-bridge.json`:
   - `bridgeMode = "backend"`
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
