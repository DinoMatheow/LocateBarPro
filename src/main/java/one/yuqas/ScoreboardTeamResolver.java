package one.yuqas;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.logging.Level;

public final class ScoreboardTeamResolver {

    private final TeamLocatorBar plugin;

    public ScoreboardTeamResolver(TeamLocatorBar plugin) {
        this.plugin = plugin;
    }

    public boolean areTeammates(Player first, Player second) {
        try {
            Scoreboard firstScoreboard = first.getScoreboard();
            Scoreboard secondScoreboard = second.getScoreboard();
            Scoreboard sharedScoreboard;

            if (firstScoreboard == secondScoreboard) {
                sharedScoreboard = firstScoreboard;
            } else {
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                if (manager == null) {
                    return false;
                }
                sharedScoreboard = manager.getMainScoreboard();
            }

            Team firstTeam = sharedScoreboard.getEntryTeam(first.getName());
            Team secondTeam = sharedScoreboard.getEntryTeam(second.getName());
            return firstTeam != null && firstTeam.equals(secondTeam);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not resolve a shared scoreboard team; denying Locator Bar visibility.", exception);
            return false;
        }
    }
}
