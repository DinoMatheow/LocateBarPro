package one.yuqas;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TeamCommand implements CommandExecutor {

    private static final String PREFIX = "§a[Teams] §f";

    private final TeamManagementService teams;

    public TeamCommand(TeamManagementService teams) {
        this.teams = teams;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Team commands can only be used by a player.");
            return true;
        }
        teams.execute(player, args == null ? new String[0] : args);
        return true;
    }
}
