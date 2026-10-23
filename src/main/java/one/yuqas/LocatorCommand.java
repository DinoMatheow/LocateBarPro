package one.yuqas;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public final class LocatorCommand implements CommandExecutor {

    private static final String PERMISSION = "teamlocatorbar.locator";
    private static final String PREFIX = "§a[Locator] §f";

    private final PlayerPrivacyService privacyService;

    public LocatorCommand(PlayerPrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args == null) {
            args = new String[0];
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "This command can only be used by a player.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        UUID playerId = player.getUniqueId();
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> setEnabled(player, playerId, args, true);
            case "off" -> setEnabled(player, playerId, args, false);
            case "mode" -> setTransmissionMode(player, playerId, args);
            case "receive" -> setReceptionMode(player, playerId, args);
            case "status" -> showStatus(player, playerId, args);
            case "help" -> showHelp(player, args);
            default -> unknownSubcommand(player);
        };
    }

    private boolean setEnabled(Player player, UUID playerId, String[] args, boolean enabled) {
        if (args.length != 1) {
            return unknownSubcommand(player);
        }

        privacyService.setTransmissionEnabled(playerId, enabled);
        if (enabled) {
            player.sendMessage(PREFIX + "Transmission enabled using your saved "
                    + display(privacyService.getLastTransmissionMode(playerId)) + " mode.");
        } else {
            player.sendMessage(PREFIX + "Transmission disabled. Your saved mode was not changed.");
        }
        return true;
    }

    private boolean setTransmissionMode(Player player, UUID playerId, String[] args) {
        if (args.length != 2) {
            return unknownSubcommand(player);
        }

        VisibilityMode mode = parseMode(args[1], false);
        if (mode == null) {
            return unknownSubcommand(player);
        }

        privacyService.setTransmissionMode(playerId, mode);
        player.sendMessage(PREFIX + "Transmission mode set to " + display(mode) + " and enabled.");
        return true;
    }

    private boolean setReceptionMode(Player player, UUID playerId, String[] args) {
        if (args.length != 2) {
            return unknownSubcommand(player);
        }

        VisibilityMode mode = parseMode(args[1], true);
        if (mode == null) {
            return unknownSubcommand(player);
        }

        privacyService.setReceptionMode(playerId, mode);
        player.sendMessage(PREFIX + "Reception mode set to " + display(mode) + ".");
        return true;
    }

    private boolean showStatus(Player player, UUID playerId, String[] args) {
        if (args.length != 1) {
            return unknownSubcommand(player);
        }

        boolean enabled = privacyService.isTransmissionEnabled(playerId);
        player.sendMessage("§6Locator preferences:");
        player.sendMessage("§fTransmission: " + (enabled ? "§aactive" : "§cdisabled")
                + " §7(saved mode: " + display(privacyService.getLastTransmissionMode(playerId)) + ")");
        player.sendMessage("§fReception: §e" + display(privacyService.getReceptionMode(playerId)));
        player.sendMessage("§fWorld restriction: "
                + (privacyService.isWorldRestricted(player) ? "§crestricted" : "§aallowed"));
        player.sendMessage("§7Change transmission: /locator on, /locator off, /locator mode <team|public>");
        player.sendMessage("§7Change reception: /locator receive <off|team|public>");
        return true;
    }

    private boolean showHelp(Player player, String[] args) {
        if (args.length != 1) {
            return unknownSubcommand(player);
        }
        sendHelp(player);
        return true;
    }

    private boolean unknownSubcommand(Player player) {
        player.sendMessage("§cUnknown or invalid Locator command.");
        sendHelp(player);
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6Locator commands:");
        player.sendMessage("§f/locator on §7- Enable transmission using the last selected mode.");
        player.sendMessage("§f/locator off §7- Disable transmission without changing the saved mode.");
        player.sendMessage("§f/locator mode <team|public> §7- Select and enable transmission.");
        player.sendMessage("§f/locator receive <off|team|public> §7- Select locations you receive.");
        player.sendMessage("§f/locator status §7- Show your preferences.");
    }

    private VisibilityMode parseMode(String value, boolean allowOff) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "off" -> allowOff ? VisibilityMode.OFF : null;
            case "team" -> VisibilityMode.TEAM;
            case "public" -> VisibilityMode.PUBLIC;
            default -> null;
        };
    }

    private String display(VisibilityMode mode) {
        return mode.name();
    }
}
