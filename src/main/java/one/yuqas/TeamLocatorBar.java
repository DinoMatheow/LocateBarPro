package one.yuqas;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TeamLocatorBar extends JavaPlugin implements CommandExecutor {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        LocationBarListener listener = new LocationBarListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        if (getCommand("teamlocatorbar") != null) {
            getCommand("teamlocatorbar").setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player && !sender.isOp()) {
            sender.sendMessage("§cYou do not have permission to use this command!");
            return true;
        }

        reloadConfig();
        sender.sendMessage("§x§2§D§F§F§4§Fᴛ§x§3§9§F§B§5§8ᴇ§x§4§4§F§6§6§1ᴀ§x§5§0§F§2§6§Aᴍ§x§5§C§E§D§7§3ʟ§x§6§7§E§9§7§Cᴏ§x§7§3§E§4§8§5ᴄ§x§7§F§E§0§8§Fᴀ§x§8§B§D§B§9§8ᴛ§x§9§6§D§7§A§1ᴏ§x§A§2§D§2§A§Aʀ§x§A§E§C§E§B§3ʙ§x§B§9§C§9§B§Cᴀ§x§C§5§C§5§C§5ʀ " +
                "§f| §x§B§6§F§C§C§1Configuration reloaded successfully!");
        return true;
    }
}