# Download
https://modrinth.com/plugin/teamlocatorbar

> The download link above belongs to the original TeamLocatorBar project and is not a
> download link for this LocateBarPro version.

## Créditos y licencia

Este proyecto está basado en [TeamLocatorBar de 1Yuqas](https://github.com/1Yuqas/TeamLocatorBar),
distribuido bajo la licencia MIT. Esta adaptación también se distribuye bajo la licencia
MIT, conservando el aviso de copyright original y añadiendo el de DinoMatheow.

## Cambios respecto al original

- Compatibilidad configurada con Paper 26.3.
- Política central de visibilidad con modos de transmisión y recepción.
- Comandos `/locator` y permisos personales y administrativos.
- Persistencia de preferencias por UUID.
- Restricciones de seguimiento por mundo.
- Equipos de scoreboard administrados por jugadores, con líder e invitaciones.
- Documentación de pruebas manuales.

## Locator Bar privacy defaults

Each player's transmission and reception preferences are keyed by UUID. Both default to `TEAM`: a player shares their
location with scoreboard teammates, and receives teammate locations.
The selected transmission mode is separate from whether transmission is enabled: `off` preserves the last selected
`TEAM` or `PUBLIC` mode, and `on` restores it. Changing reception does not change transmission. Preferences are stored
under `<plugin data folder>/players/<uuid>.yml`, one YAML file per UUID, including the schema version. Legacy UUID
files in the data-folder root are moved into `players/` on first load. Preferences survive server
restarts while `persistence.enabled` is `true`; with persistence disabled, changes remain in memory only. Preferences
loaded for rejected pre-logins are released without changing disk files; if an allowed pre-login never reaches
`PlayerJoinEvent`, its in-memory entry is released after one minute. Reads and
writes run asynchronously; saves use a temporary file and atomic replacement when supported. Corrupt files are
renamed to a unique `.corrupt` filename, while files with a newer schema are left untouched. The autosave interval is
controlled by
`persistence.autosave-minutes` (default 5; set to 0 to disable periodic saves). Changing `persistence.enabled`
requires a server restart.

Set `debug: true` to emit privacy decision categories and marker-removal attempts. Output
is rate-limited per category and excludes player identifiers, coordinates, and world names.
It is intended for short diagnostic sessions, not as proof that a client applied a removal.

Players manage only their own settings with `/locator`, `/locator on`, `/locator off`, `/locator mode <team|public>`,
`/locator receive <off|team|public>`, `/locator status`, and `/locator help`. The `/teamlocatorbar` (alias `/tlb`)
command remains the configuration reload command and requires `teamlocatorbar.reload` (default: operators).
Personal preference commands require `teamlocatorbar.locator` (default: true).

## Player-managed teams (phase 7A)

Players can manage real teams on Minecraft's main scoreboard with `/locator team` or the short alias `/lteam`:

- `/locator team create <name>` creates a team and makes its creator the leader.
- `/locator team invite <player>` invites an online player; only the leader can invite.
- `/locator team accept [name]` accepts an invitation. If several are pending, specify the team name.
- `/locator team deny [name]` rejects an invitation; the connected leader is notified.
- `/locator team leave` leaves as a member. A leader must disband while other members remain.
- `/locator team disband` closes the leader's team and removes it from the main scoreboard.
- `/locator team info` shows only the executing player's managed team, leader, and members.
- `/locator team help` lists the team commands.

`teamlocatorbar.team` (default: true) is required for every team subcommand. Team names are 1-16 ASCII letters,
digits, underscores, or hyphens and are unique without regard to case. Teams use internal scoreboard names beginning
with `tlb_`; a player already on a vanilla or administrator scoreboard team must leave that team before joining a
managed team. Admin/vanilla teams are not listed or modified by these commands. The limits are configured using
`teams.max-members` (default 8, including the leader) and `teams.invite-timeout-seconds` (default 60); those settings
are reloaded by `/teamlocatorbar reload`.

Team membership is stored by Minecraft on the main scoreboard, so it remains when members disconnect. The leader
assignment and pending invitations exist only in memory in phase 7A: invitations are removed on expiry or when either
party disconnects, and leader data is lost on server restart. Persistence of the leader is deferred to phase 7B.
Each create, accept, leave, or disband membership change immediately asks the existing privacy service to reevaluate
visible markers; this does not verify that a client applied a visual removal. Invitation chat messages include
clickable accept/decline actions plus manual command alternatives, but the click behavior has not been verified in a
live Minecraft client.

Team membership is resolved from the same scoreboard when both players share one. If they have different scoreboards,
the main server scoreboard is used as the common source; same-named teams on separate scoreboards are not treated as
the same team. If no shared membership can be verified, visibility is denied where team membership is required.

Previously delivered markers are tracked per receiver and removed with Paper 26.3's waypoint `UNTRACK` operation
when a preference change revokes access. A periodic reconciliation also checks currently visible markers for
scoreboard-team changes.

The existing color labels remain presentation only: being on a different team does not establish hostility.

## World restrictions

`worlds.mode` and `worlds.list` restrict tracking using case-insensitive world names. The default is an empty
`BLACKLIST`, preserving existing behavior. A player in a restricted world cannot transmit or receive Locator Bar
markers; their preferences remain unchanged and take effect again in an allowed world. When both players are in
allowed worlds, the plugin adds no cross-world restriction: whether a marker is sent or visible across dimensions
remains governed by Paper/Minecraft's existing behavior. Reloading the plugin configuration or changing worlds
rechecks tracked markers and sends the existing waypoint removal operation where access was revoked.

For step-by-step real-server checks and a report template, see [docs/TESTING.md](./docs/TESTING.md)
and [docs/TEST-REPORT.md](./docs/TEST-REPORT.md). The plugin does not support Folia: Bukkit
API calls in packet decisions are deliberately denied outside the primary server thread.

## Cómo compilar e instalar

Usa JDK 25 y ejecuta desde la raíz del repositorio:

```sh
./gradlew clean build
```

El JAR instalable generado es `build/libs/TeamLocatorBar-0.1.jar`. Copia ese archivo a
`plugins/` de un servidor Paper 26.3, junto con un JAR de plugin ProtocolLib compatible con
esa versión exacta, y reinicia el servidor. ProtocolLib está declarada como `compileOnly`:
no se incluye dentro del JAR de TeamLocatorBar y debe instalarse por separado.
