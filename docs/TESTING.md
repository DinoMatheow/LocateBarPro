# Manual server validation

This checklist is for validating TeamLocatorBar with real clients. Passing the build or
these instructions does not establish that the Locator Bar behaves correctly in-game.
Record each outcome in [TEST-REPORT.md](./TEST-REPORT.md).

## Requirements

- A clean Paper **26.3** server, preferably build 218 to match the project's development bundle.
- **Java 25** for the server and the project build.
- A ProtocolLib **plugin JAR explicitly confirmed to support the exact Paper 26.3 build**.
  The project's `net.dmulloy2:ProtocolLib:5.5.0-SNAPSHOT` is a compile-time dependency,
  not an installable server plugin. Do not treat successful compilation or downloading
  that artifact as proof of runtime compatibility.
- The built TeamLocatorBar JAR and at least two Minecraft Java clients. Three or more
  clients make simultaneous-player and team-transition checks more useful.
- Operator access for setup, configuration reloads, and team administration.

## Installation and preparation

1. Build and run automated checks from the project root with JDK 25:

   ```sh
   java -version
   bash ./gradlew clean test build
   ```

2. Create a disposable Paper 26.3 server. Start it once to generate its files, stop it,
   and set `eula=true` only if you accept the Minecraft EULA.
3. Install the ProtocolLib server plugin JAR that its publisher documents as compatible
   with this Paper build. Install TeamLocatorBar from `build/libs/`. Do not put the
   compile-time ProtocolLib Maven artifact in the server's `plugins` folder.
4. Start the server and inspect the complete startup log. Confirm Paper, ProtocolLib,
   and TeamLocatorBar all enable without dependency, packet-type, reflection, or
   configuration errors. If ProtocolLib reports an unsupported server version, stop:
   this is a runtime compatibility blocker, not a passing test.
5. Join with the test clients. Create teams using vanilla commands, for example:

   ```text
   /team add red
   /team add blue
   /team join red Alice
   /team join red Bob
   /team join blue Carol
   ```

6. Keep a copy of the server's plugin data directory before the corrupt-file test.
   Use the actual UUID of a test account when inspecting its preference file.
7. Unless a case says otherwise, use allowed worlds, keep `enabled: true`, and test
   with each player visible on the server's Locator Bar. Note the time between an action
   and any marker change; the recurring reconciliation interval is 20 ticks.

## Checklist

For every case, mark exactly one result and add evidence/notes in the report:
`[ ] PASS` `[ ] FAIL` `[ ] BLOCKED` `[ ] NOT RUN`.

### Plugin startup and basic visibility

- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Clean plugin load:** Start from
  the clean server and confirm both plugins enable, with no ProtocolLib compatibility,
  packet, reflection, or missing dependency errors.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Same-team visibility:** With
  Alice and Bob on `red`, confirm they see one another according to TEAM defaults.
  Confirm neither gets their own marker.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Different teams and modes:**
  With Alice on `red` and Carol on `blue`, check all four combinations:
  transmitter TEAM/receiver PUBLIC (hidden), transmitter PUBLIC/receiver TEAM
  (hidden), PUBLIC/PUBLIC (visible), and TEAM/PUBLIC (hidden). Check both directions.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Players without teams:** Confirm
  two unteamed players are not treated as teammates; cross-team visibility still
  follows PUBLIC/PUBLIC.

### Live preference changes and revocation

- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Critical: `/locator off` and
  `/locator on` without reconnecting:** Make a marker visible, run `/locator off` on
  the transmitting player, and confirm the receiver's existing marker disappears
  without either player disconnecting. Run `/locator on`; confirm it returns using the
  saved transmission mode. Record both the delay and whether movement was needed.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Reception changes in hot state:**
  Make an other-team PUBLIC marker visible. Change the receiver from `receive public`
  to `receive off` and then `receive team`; confirm the marker is removed without
  reconnecting. Set `receive public` again and confirm it can return.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Team join reconciliation:**
  Change team membership with `/team join` while clients stay connected. Check both
  newly allowed and newly denied markers, allowing at least the 20-tick reconciliation
  interval.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Team leave reconciliation:**
  Run `/team leave` while the marker is visible. Confirm the marker is re-evaluated
  and removed/retained according to the resulting teams and preferences.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Equal team names on different
  scoreboards:** Give two players different personal scoreboards, each with a team of
  the same name, but do not put both entries on the common main scoreboard. Confirm
  the plugin does not assume they are teammates based only on the team name. Record
  how the other scoreboard plugin sets these scoreboards.

### Persistence and world restrictions

- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Persistence after restart:** Set
  non-default transmission and reception preferences, stop the server cleanly, restart,
  and verify `/locator status` retains them.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Corrupt preference file:** Stop
  the server, back up and replace one test player's UUID YAML file with invalid YAML
  (and separately try an empty file). Start the server; verify the server remains up,
  a uniquely named `.corrupt` file is retained, and that account receives defaults.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Blacklist:** Set
  `worlds.mode: BLACKLIST` and list a test world. Reload with `/teamlocatorbar reload`;
  confirm players there neither transmit nor receive, and their preferences are
  unchanged.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Whitelist:** Set
  `worlds.mode: WHITELIST` and list only one test world. Confirm tracking works only
  when both players are in allowed worlds.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **World change and teleport:**
  While a marker is visible, change or teleport one player into a restricted world and
  confirm the marker is revoked; return to an allowed world and confirm the saved
  preferences apply again. Check that no cross-world behavior outside the configured
  restriction is assumed.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Reload reevaluation:** Change
  the world list while markers are visible, run `/teamlocatorbar reload`, and verify
  affected markers are re-evaluated without restarting.

### Permissions, lifecycle, and load

- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Normal player versus operator:**
  Confirm a normal player can change only their own settings and cannot reload the
  plugin. Confirm an operator can run the reload command. Verify no command argument
  changes another player's preferences.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Fast disconnect/reconnect:**
  Change preferences, disconnect, and reconnect quickly. Confirm the loaded preference
  is not replaced by defaults or stale data and that disconnected markers disappear.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Several simultaneous players:**
  Use at least four clients across two teams and exercise both directions of visibility,
  preference changes, team changes, and world changes at the same time. Watch server
  logs for packet errors or persistence failures.
- [ ] PASS / [ ] FAIL / [ ] BLOCKED / [ ] NOT RUN — **Optional debug logging:** Set
  `debug: true`, reload, and observe allowed/denied decisions and marker-removal
  attempts. Confirm messages contain no player names, UUIDs, coordinates, or world
  names and are rate-limited. Set it back to `false` and confirm debug messages stop.

## Interpretation and known limits

- A `PASS` applies only to the exact Paper, ProtocolLib, plugin, and client versions
  recorded in the report.
- Compile-time checks do not prove ProtocolLib runtime compatibility or that a client
  removes an already received marker after the plugin sends an UNTRACK packet.
- Packet decisions that cannot safely access Bukkit on the primary server thread are
  denied. If the debug log reports `denied_non_primary_thread` for normal marker traffic,
  investigate the installed ProtocolLib/Paper behavior; do not treat the resulting
  hidden markers as a successful privacy UX test.
- Folia is not supported. The plugin descriptor declares this, and testing on Folia is
  outside this checklist.
- Record unexpected behavior and relevant log excerpts, but redact player names,
  UUIDs, IP addresses, and other personal data before sharing them.
