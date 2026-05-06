package link.fewermc;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class FewerMCLinkPlugin extends JavaPlugin {

    private static final String ADMIN_PERMISSION = "fewermclink.admin";
    private static final String BASE_COMMAND = "fewermclink";

    private FileConfiguration messages;
    private DiscordBotManager discordBotManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveMessagesIfMissing();
        reloadMessages();

        discordBotManager = new DiscordBotManager(this);
        discordBotManager.start();

        registerPluginCommands();

        getLogger().info("FewerMCLink enabled.");
    }

    @Override
    public void onDisable() {
        if (discordBotManager != null) {
            discordBotManager.shutdown();
        }
        getLogger().info("FewerMCLink disabled.");
    }

    public void reloadPluginData() {
        reloadConfig();
        reloadMessages();
        if (discordBotManager != null) {
            discordBotManager.reload();
        }
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public String msg(String path) {
        String prefix = messages.getString("ingame.prefix", "&8[&bFewerMCLink&8] &7");
        String body = messages.getString(path, "Missing message: " + path);
        return color(prefix + body);
    }

    public String rawDiscordMsg(String path) {
        return messages.getString(path, "Missing message: " + path);
    }

    private void saveMessagesIfMissing() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void reloadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void registerPluginCommands() {
        if (getCommand(BASE_COMMAND) == null) {
            return;
        }

        LinkCommand linkCommand = new LinkCommand();
        getCommand(BASE_COMMAND).setExecutor(linkCommand);
        getCommand(BASE_COMMAND).setTabCompleter(linkCommand);
    }

    private final class LinkCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(msg("ingame.no-permission"));
                return true;
            }

            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                sender.sendMessage(msg("ingame.usage"));
                return true;
            }

            reloadPluginData();
            sender.sendMessage(msg("ingame.reload-success"));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Collections.singletonList("reload");
            }
            return Collections.emptyList();
        }
    }
}
