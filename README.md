# Xclipsen Mod

Fabric-Clientmod fuer Minecraft `1.21.10`.

Aktuelle Version: `0.4.2`

## Kurzuebersicht

- `Settings GUI`: ClickGUI-style Konfigurationsscreen unter `/xclipsen`, `/xclipsen config` oder `/irc config`.
- `IRC Bridge`: Discord-/IRC-Nachrichten im Minecraft-Chat, `/irc <nachricht>`, `/i <nachricht>` und temporaerer IRC-Chatmodus.
- `Account Link`: Minecraft-Account per `/link CODE` mit dem Discord-/Bot-Backend verbinden.
- `Hypixel Co-op Relay`: Hypixel-Co-op-Chat automatisch ins Backend weiterleiten, wenn die Bridge aktiv und der Account gelinkt ist.
- `Image Preview`: Discord-/Chat-Bildlinks als Hover-Preview im Chat anzeigen, inklusive Shift-Grossansicht.
- `Hideonleaf Helper`: Shulker Glow, Projektil-Glow, Tracer-Linie, Lost-Fight-Alert und konfigurierbarer Alert-Sound.
- `Shard Tracker`: Hideonleaf-Shards und Drops tracken, Session/Total-HUD anzeigen, Profit pro Stunde berechnen und Bazaar-Preise vom Backend aktualisieren.
- `HUD Editor`: HUD-Elemente verschieben, skalieren und zuruecksetzen ueber `/xclipsen hud` oder `/irc hud`.
- `Time Changer`: clientseitige Zeit-Presets wie Day, Noon, Sunset, Night, Midnight, Sunrise und Real Time.

## Commands

- `/xclipsen` - Settings oeffnen.
- `/xclipsen hud` - HUD-Editor oeffnen.
- `/irc <nachricht>` oder `/i <nachricht>` - Nachricht ans Backend senden.
- `/irc on|off|status|reload` - Bridge lokal steuern und Status anzeigen.
- `/link CODE` - Minecraft-Account mit dem Backend-Linkcode verbinden.
- `/shulkerglow on|off|toggle` - Shulker Glow schnell umschalten.
- `/shardtracker` oder `/st` - Shard-Tracker-Status anzeigen.
- `/shardtracker reset|resetall|toggle|on|off` - Shard-Tracker steuern.

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
