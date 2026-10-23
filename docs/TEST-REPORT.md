# TeamLocatorBar manual test report

Copy this file for each test run. Use `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN` for each
case in [TESTING.md](./TESTING.md). Do not include unredacted player identifiers, IP
addresses, or other personal data in shared copies.

## Test environment

- Date/time and timezone:
- Tester:
- Paper version/build:
- Java version (`java -version`):
- ProtocolLib version/build and source:
- TeamLocatorBar JAR/version:
- Minecraft client versions:
- Number of clients:
- Other scoreboard/team plugins and versions:
- World names and restriction configuration:
- `debug` setting:
- Automated build/test command and result:

## Results

| Test case | Result (PASS/FAIL/BLOCKED/NOT RUN) | Delay / observations | Evidence or log reference |
|---|---|---|---|
| Clean load of Paper and ProtocolLib | | | |
| Teammates see each other | | | |
| Different teams: TEAM/PUBLIC matrix | | | |
| Players without teams | | | |
| `/locator off` removes existing marker without reconnect | | | |
| `/locator on` restores saved transmission mode | | | |
| Hot reception changes | | | |
| `/team join` reconciliation (20 ticks) | | | |
| `/team leave` reconciliation (20 ticks) | | | |
| Same team name on different scoreboards | | | |
| Preferences survive server restart | | | |
| Corrupt/empty preference file handling | | | |
| Blacklist and whitelist | | | |
| World change/teleport revocation and return | | | |
| Reload reevaluates visible markers | | | |
| Normal player permissions versus operator | | | |
| Fast disconnect/reconnect | | | |
| Several simultaneous players | | | |
| Optional debug logging and rate limiting | | | |

## Failures and log excerpts

For each failure, include the test case, exact steps, expected result, actual result,
time to reproduce, and a **redacted** excerpt from the server log. Note whether players
had to move, reconnect, or change worlds before the result appeared.

```text
Paste redacted log excerpt here.
```

## Summary

- Passed:
- Failed:
- Blocked:
- Not run:
- Runtime compatibility blockers:
- Locator Bar revocation without reconnect confirmed by client: Yes / No
- Overall assessment and follow-up:
