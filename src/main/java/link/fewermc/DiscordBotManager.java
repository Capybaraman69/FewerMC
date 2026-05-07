package link.fewermc;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class DiscordBotManager extends ListenerAdapter {
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final String OPTION_USER = "user";
    private static final String OPTION_REASON = "reason";
    private static final String OPTION_TIME = "time";
    private static final String OPTION_MEMBER = "member";

    private final FewerMCLinkPlugin plugin;
    private final Object historyLock = new Object();
    private final File historyFile;
    private volatile JDA jda;

    public DiscordBotManager(FewerMCLinkPlugin plugin) {
        this.plugin = plugin;
        this.historyFile = new File(plugin.getDataFolder(), "history.log");

    }

    public void start() {
        if (!starting.compareAndSet(false, true)) {
            plugin.getLogger().warning("DiscordBotManager start() called while already starting/reloading.");
            return;
        }
        String token = plugin.getConfig().getString("discord.token", "").trim();
        if (token.isEmpty() || token.equalsIgnoreCase("PUT_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Discord token not configured. Bridge disabled until config is updated.");
            starting.set(false);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                jda = JDABuilder.createLight(token, Collections.emptyList())
                        .setStatus(OnlineStatus.ONLINE)
                        .setActivity(readActivity(plugin.getConfig()))
                        .addEventListeners(this)
                        .build();

                jda.awaitReady();
                registerSlashCommands();
                plugin.getLogger().info(plugin.rawDiscordMsg("discord.bot-started"));
            } catch (Exception ex) {
                plugin.getLogger().severe("Failed to start Discord bridge: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                starting.set(false);
            }
        });
    }

    public void shutdown() {
        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Exception ignored) {}
            jda = null;
            plugin.getLogger().info(plugin.rawDiscordMsg("discord.bot-stopped"));
        }
        // Reset flag in case shutdown is called during reload
        starting.set(false);
    }

    public void reload() {
        shutdown();
        loadHistoryData();
        start();
    }

    private Activity readActivity(FileConfiguration config) {
        String text = config.getString("discord.activity-text", "FewerMC moderation bridge");
        String type = config.getString("discord.status", "watching").toLowerCase(Locale.ROOT);

        return switch (type) {
            case "playing" -> Activity.playing(text);
            case "listening" -> Activity.listening(text);
            case "competing" -> Activity.competing(text);
            default -> Activity.watching(text);
        };
    }

    private void registerSlashCommands() {
        if (jda == null) {
            return;
        }

        List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commandData = new ArrayList<>();

        commandData.add(commandWithReason("ipban", "IP ban a player"));
        commandData.add(commandWithReason("ipmute", "IP mute a player"));
        commandData.add(commandWithReason("ban", "Ban a player"));
        commandData.add(commandWithReason("mute", "Mute a player"));
        commandData.add(commandWithTimeAndReason("tempban", "Temporarily ban a player"));
        commandData.add(commandWithTimeAndReason("tempmute", "Temporarily mute a player"));
        commandData.add(commandWithOptionalReason("unban", "Unban a player"));
        commandData.add(commandWithOptionalReason("unmute", "Unmute a player"));
        commandData.add(Commands.slash("history", "View Discord command history for this bot")
            .addOption(OptionType.USER, OPTION_MEMBER, "Discord member to look up", false));

        commandData.add(Commands.slash("linkreload", "Reload plugin config/messages"));

        String guildId = plugin.getConfig().getString("discord.guild-id", "").trim();
        if (guildId.isEmpty()) {
            jda.updateCommands().addCommands(commandData).queue(
                    commands -> {
                        plugin.getLogger().info("Registered " + commands.size() + " global slash commands. May take up to 1 hour to appear.");
                        logGlobalCommands();
                    },
                    error -> {
                        plugin.getLogger().severe("Failed to register global slash commands: " + error.getMessage());
                        error.printStackTrace();
                    }
            );
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().warning("Guild not found: " + guildId + ". Using global commands.");
            jda.updateCommands().addCommands(commandData).queue(
                    commands -> {
                        plugin.getLogger().info("Registered " + commands.size() + " global slash commands. May take up to 1 hour to appear.");
                        logGlobalCommands();
                    },
                    error -> {
                        plugin.getLogger().severe("Failed to register global slash commands: " + error.getMessage());
                        error.printStackTrace();
                    }
            );
            return;
        }

        guild.updateCommands().addCommands(commandData).queue(
                commands -> {
                    plugin.getLogger().info("Registered " + commands.size() + " slash commands for guild " + guild.getName());
                    logGuildCommands(guild);
                },
                error -> {
                    plugin.getLogger().severe("Failed to register slash commands for guild " + guild.getName() + ": " + error.getMessage());
                    error.printStackTrace();
                }
        );
    }

    private SlashCommandData commandWithUser(String name, String description) {
        return Commands.slash(name, description)
                .addOption(OptionType.STRING, OPTION_USER, "Minecraft username", true);
    }

    private SlashCommandData commandWithReason(String name, String description) {
        return commandWithUser(name, description)
                .addOption(OptionType.STRING, OPTION_REASON, "Reason", true);
    }

    private SlashCommandData commandWithOptionalReason(String name, String description) {
        return commandWithUser(name, description)
                .addOption(OptionType.STRING, OPTION_REASON, "Reason", false);
    }

    private SlashCommandData commandWithTimeAndReason(String name, String description) {
        return commandWithUser(name, description)
                .addOption(OptionType.STRING, OPTION_TIME, "Duration format: 1s, 1h, 1d", true)
                .addOption(OptionType.STRING, OPTION_REASON, "Reason", true);
    }

    private void logGuildCommands(Guild guild) {
        guild.retrieveCommands().queue(
                cmds -> {
                    String names = cmds.stream().map(c -> c.getName()).sorted().collect(Collectors.joining(", "));
                    plugin.getLogger().info("Guild commands registered: " + (names.isBlank() ? "<none>" : names));
                },
                err -> plugin.getLogger().warning("Could not fetch guild command list: " + err.getMessage())
        );
    }

    private void logGlobalCommands() {
        jda.retrieveCommands().queue(
                cmds -> {
                    String names = cmds.stream().map(c -> c.getName()).sorted().collect(Collectors.joining(", "));
                    plugin.getLogger().info("Global commands registered: " + (names.isBlank() ? "<none>" : names));
                },
                err -> plugin.getLogger().warning("Could not fetch global command list: " + err.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getMember() == null) {
            replyPublic(event, "discord.member-required", "This command can only be used in a server channel.");
            return;
        }

        String command = event.getName().toLowerCase(Locale.ROOT);
        if (!HANDLED_COMMANDS.contains(command)) {
            return;
        }

        RoleProfile roleProfile = resolveProfile(event.getMember());
        Action action = Action.fromCommand(command);

        if (!roleProfile.allowedActions.contains(action)) {
            replyPublic(event, "discord.no-permission", "No permission.", new Color(0xE74C3C));
            return;
        }

        if (action == Action.HISTORY) {
            handleHistoryCommand(event);
            return;
        }

        String actorName = event.getMember().getEffectiveName();

        if (action == Action.RELOAD) {
            String auditCommand = "reload plugin data";
            recordHistory(event.getUser(), actorName, "linkreload", "system", "", "", auditCommand, "SUCCESS");
            sendLogMessage(event.getUser().getId(), actorName, "linkreload", "system", "", "", auditCommand, "SUCCESS");
                event.replyEmbeds(responseEmbed(discordMsg("discord.bridge-reloaded", "Bridge reloaded."), new Color(0x5865F2)).build())
                    .queue(ignored -> Bukkit.getScheduler().runTask(plugin, plugin::reloadPluginData));
            return;
        }

        String player = sanitize(event.getOption(OPTION_USER) != null ? event.getOption(OPTION_USER).getAsString() : "");
        String reason = sanitize(event.getOption(OPTION_REASON) != null ? event.getOption(OPTION_REASON).getAsString() : "");
        String time = event.getOption(OPTION_TIME) != null ? event.getOption(OPTION_TIME).getAsString().trim() : "";

        if (player.isEmpty()) {
            replyPublic(event, "discord.missing-user", "Missing user.");
            return;
        }

        if (!player.matches("[a-zA-Z0-9_]{1,16}")) {
            String invalidUser = discordMsg(
                    "discord.invalid-user",
                    "Invalid Minecraft username. Use 1-16 characters: letters, numbers, underscore only."
            ).replace("{user}", player);
                event.replyEmbeds(responseEmbed(invalidUser, new Color(0xF1C40F)).build())
                    .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
            return;
        }

        if (action.requiresTime) {
            Duration parsed = DurationParser.parseDuration(time);
            if (parsed == null) {
                replyPublic(event, "discord.invalid-time", "Invalid duration. Use: 30s, 30m, 12h, 7d.");
                return;
            }

            if (roleProfile.maxTempDuration != null && parsed.compareTo(roleProfile.maxTempDuration) > 0) {
                String max = DurationParser.toCompact(roleProfile.maxTempDuration);
                String maxHuman = formatDurationForDisplay(max);
                String inputHuman = formatDurationForDisplay(time);
                String msg = discordMsg("discord.max-time-exceeded", "Invalid. Max {max_human}.")
                        .replace("{max}", max)
                        .replace("{max_human}", maxHuman)
                        .replace("{input}", time)
                        .replace("{input_human}", inputHuman);
                event.replyEmbeds(responseEmbed(msg, new Color(0xF1C40F)).build())
                    .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
                return;
            }
        }

        if (action.requiresReason && reason.isEmpty()) {
            replyPublic(event, "discord.missing-reason", "Missing reason.");
            return;
        }

        String template = plugin.getConfig().getString("commands." + command, "").trim();
        if (template.isEmpty()) {
            template = buildRawConsoleCommand(command, action, player, time, reason);
        }

        String finalCommand = template
                .replace("{player}", player)
                .replace("{reason}", reason)
                .replace("{time}", time)
                .replace("{discord_user}", event.getUser().getName())
                .replace("{discord_tag}", actorName)
            .replaceAll("\\s+", " ")
                .trim();

        event.deferReply().queue(hook -> Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[FewerMCLink] Dispatching: " + finalCommand);
            try {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                if (!dispatched) {
                    plugin.getLogger().warning("Console command returned false: " + finalCommand);
                    recordHistory(event.getUser(), actorName, command, player, time, reason, finalCommand, "FAILED");
                    sendLogMessage(event.getUser().getId(), actorName, command, player, time, reason, finalCommand, "FAILED");
                    String fail = discordMsg("discord.command-failed", "Punishment Failed\nType: /{command}\nPlayer: {user}\nDiscord: {executor}")
                        .replace("{command}", command)
                        .replace("{player}", player)
                        .replace("{user}", player)
                        .replace("{reason}", reason.isEmpty() ? "(none)" : reason)
                        .replace("{time}", time)
                        .replace("{time_human}", formatDurationForDisplay(time))
                        .replace("{executor}", actorName)
                        .replace("{moderator}", actorName);
                    hook.editOriginalEmbeds(responseEmbed(fail, new Color(0xC0392B)).build())
                        .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
                    return;
                }
            } catch (Exception ex) {
                plugin.getLogger().severe("Exception dispatching command: " + finalCommand);
                ex.printStackTrace();
                recordHistory(event.getUser(), actorName, command, player, time, reason, finalCommand, "FAILED");
                sendLogMessage(event.getUser().getId(), actorName, command, player, time, reason, finalCommand, "FAILED");
                String fail = discordMsg("discord.command-failed", "Punishment Failed\nType: /{command}\nPlayer: {user}\nDiscord: {executor}")
                    .replace("{command}", command)
                    .replace("{player}", player)
                    .replace("{user}", player)
                    .replace("{reason}", reason.isEmpty() ? "(none)" : reason)
                    .replace("{time}", time)
                    .replace("{time_human}", formatDurationForDisplay(time))
                    .replace("{executor}", actorName)
                    .replace("{moderator}", actorName);
                hook.editOriginalEmbeds(responseEmbed(fail, new Color(0xC0392B)).build())
                    .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
                return;
            }

            hook.editOriginalEmbeds(commandSuccessEmbed(command, actorName, player, reason, time, finalCommand).build())
                .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
            recordHistory(event.getUser(), actorName, command, player, time, reason, finalCommand, "SUCCESS");
            sendLogMessage(event.getUser().getId(), actorName, command, player, time, reason, finalCommand, "SUCCESS");

            if (plugin.getConfig().getBoolean("logging.log-discord-actions", true)) {
                plugin.getLogger().info("[Discord] " + actorName + " -> " + finalCommand);
            }
        }));
    }


    private EmbedBuilder commandSuccessEmbed(String command, String actorName, String player, String reason, String time,
                                             String finalCommand) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Punishment Applied")
                .setColor(new Color(0x2ECC71))
                .addField("Command", "/" + command, true)
                .addField("Moderator", actorName, true)
                .addField("Player", player, true)
                .setTimestamp(Instant.now());

        if (!reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        if (!time.isBlank()) {
            embed.addField("Duration", formatDurationForDisplay(time), true);
        }
        if (!finalCommand.isBlank()) {
            embed.addField("Console", "`" + finalCommand + "`", false);
        }

        return embed;
    }

    private String buildRawConsoleCommand(String command, Action action, String player, String time, String reason) {
        StringBuilder raw = new StringBuilder(command).append(' ').append(player);
        if (action.requiresTime) {
            raw.append(' ').append(time);
        }
        if (action.requiresReason) {
            raw.append(' ').append(reason);
        }
        return raw.toString().trim();
    }

    private void replyPublic(SlashCommandInteractionEvent event, String path, String fallback) {
        replyPublic(event, path, fallback, new Color(0xF1C40F));
    }

    private void replyPublic(SlashCommandInteractionEvent event, String path, String fallback, Color color) {
        event.replyEmbeds(responseEmbed(discordMsg(path, fallback), color).build())
            .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
    }

    private String discordMsg(String path, String fallback) {
        String msg = plugin.rawDiscordMsg(path);
        return msg.startsWith("Missing message:") ? fallback : msg;
    }

    private EmbedBuilder responseEmbed(String content, Color color) {
        return new EmbedBuilder()
                .setDescription(content)
                .setColor(color)
                .setTimestamp(Instant.now());
    }

    private void handleHistoryCommand(SlashCommandInteractionEvent event) {
        User targetUser = event.getOption(OPTION_MEMBER) != null ? event.getOption(OPTION_MEMBER).getAsUser() : event.getUser();
        List<String> historyEntries = getHistoryEntries(targetUser.getId());
        String targetName = targetUser.getName();

        if (historyEntries.isEmpty()) {
            String none = plugin.rawDiscordMsg("discord.history-none")
                    .replace("{user}", targetName);
            event.replyEmbeds(responseEmbed(none, new Color(0x9B59B6)).build())
                .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
            return;
        }

        String header = plugin.rawDiscordMsg("discord.history-header")
                .replace("{user}", targetName)
                .replace("{count}", String.valueOf(historyEntries.size()));

        event.deferReply().queue(hook -> {
            List<String> chunks = chunkHistory(header, historyEntries);
            hook.editOriginalEmbeds(responseEmbed(chunks.get(0), new Color(0x9B59B6)).build())
                .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
            for (int index = 1; index < chunks.size(); index++) {
                hook.sendMessageEmbeds(responseEmbed(chunks.get(index), new Color(0x9B59B6)).build())
                    .queue(null, err -> plugin.getLogger().warning("Failed to send Discord reply: " + err.getMessage()));
            }

            String actorName = event.getMember().getEffectiveName();
            String auditCommand = "history lookup for " + targetName + " (" + historyEntries.size() + " entries)";
            recordHistory(event.getUser(), actorName, "history", targetName, "", "", auditCommand, "SUCCESS");
            sendLogMessage(event.getUser().getId(), actorName, "history", targetName, "", "", auditCommand, "SUCCESS");
        });
    }

    private List<String> chunkHistory(String header, List<String> entries) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);

        for (String entry : entries) {
            String line = "\n" + entry;
            if (current.length() + line.length() > 1900) {
                chunks.add(current.toString());
                current = new StringBuilder(header).append(line);
            } else {
                current.append(line);
            }
        }

        chunks.add(current.toString());
        return chunks;
    }

    private void recordHistory(User user, String actorName, String command, String player, String time, String reason,
                               String finalCommand, String status) {
        String timestamp = HISTORY_TIME_FORMAT.format(Instant.now());
        String entry = user.getId() + " | [" + timestamp + "] " + status + " /" + command + " by " + actorName
                + (player.isBlank() ? "" : " target=" + player)
                + (time.isBlank() ? "" : " time=" + time)
                + (reason.isBlank() ? "" : " reason=" + reason)
                + (finalCommand.isBlank() ? "" : " | console=" + finalCommand);
        synchronized (historyLock) {
            try (java.io.FileWriter fw = new java.io.FileWriter(historyFile, true)) {
                fw.write(entry + System.lineSeparator());
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Could not append to history.log: " + ex.getMessage());
            }
        }
    }

    private List<String> getHistoryEntries(String userId) {
        synchronized (historyLock) {
            if (!historyFile.exists()) return List.of();
            List<String> result = new ArrayList<>();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(historyFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(userId + " | ")) {
                        result.add(line.substring(line.indexOf("| ") + 2));
                    }
                }
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Could not read history.log: " + ex.getMessage());
            }
            return result;
        }
    }

    private void sendLogMessage(String actorId, String actorName, String command, String player, String time, String reason,
                                String finalCommand, String status) {
        if (jda == null) {
            return;
        }

        String channelId = plugin.getConfig().getString("logging.channel-id", "").trim();
        if (channelId.isEmpty()) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Configured logging.channel-id was not found: " + channelId);
            return;
        }

        Color embedColor = status.equals("SUCCESS") ? new Color(0x2ECC71) : new Color(0xC0392B);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(status.equals("SUCCESS") ? "Moderation Action Completed" : "Moderation Action Failed")
                .setColor(embedColor)
                .addField("Status", status, true)
                .addField("Command", "/" + command, true)
                .addField("Moderator", actorId.isBlank() ? actorName : "<@" + actorId + ">", true);

        if (!player.isBlank()) {
            embed.addField("Player", player, true);
        }
        if (!reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        if (!time.isBlank()) {
            embed.addField("Duration", formatDurationForDisplay(time), true);
        }
        if (!finalCommand.isBlank()) {
            embed.addField("Console", "`" + finalCommand + "`", false);
        }
        embed.setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue(null,
            err -> plugin.getLogger().warning("Failed to send log message: " + err.getMessage())
        );
    }

    private void loadHistoryData() {}
    private void saveHistoryData() {}

    private Duration readLimit(String roleKey) {
        String raw = readSectionValueByKey("limits", roleKey);
        if (raw.isEmpty() || raw.equalsIgnoreCase("unlimited")) {
            return null;
        }
        Duration parsed = DurationParser.parseDuration(raw);
        if (parsed == null) {
            plugin.getLogger().warning("Invalid limits value for key '" + roleKey + "': " + raw + ". Limit ignored.");
        }
        return parsed;
    }

    private String findRoleId(String... aliases) {
        for (String alias : aliases) {
            String roleId = readSectionValueByKey("roles", alias);
            if (!roleId.isEmpty()) {
                return roleId;
            }
        }
        return "";
    }

    private String readSectionValueByKey(String sectionName, String lookupKey) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionName);
        if (section == null) {
            return "";
        }

        String normalizedLookup = normalizeRoleKey(lookupKey);
        for (String key : section.getKeys(false)) {
            if (!normalizeRoleKey(key).equals(normalizedLookup)) {
                continue;
            }

            String value = section.getString(key, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }

        return "";
    }

    private String normalizeRoleKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean hasDisallowedRole(Set<String> memberRoleIds) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("disallowed-roles");
        if (section == null) {
            return false;
        }

        Set<String> blockedRoleIds = new LinkedHashSet<>();
        for (String key : section.getKeys(false)) {
            Object raw = section.get(key);
            if (raw instanceof String text) {
                blockedRoleIds.addAll(parseRoleIdList(text));
                continue;
            }

            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        blockedRoleIds.addAll(parseRoleIdList(item.toString()));
                    }
                }
            }
        }

        for (String blockedRoleId : blockedRoleIds) {
            if (memberRoleIds.contains(blockedRoleId)) {
                return true;
            }
        }

        return false;
    }

    private List<String> parseRoleIdList(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty()) {
                ids.add(cleaned);
            }
        }
        return ids;
    }

    private String parseStarRoleId(String starValue) {
        if (starValue == null) {
            return "";
        }

        String cleaned = starValue.replace("\"", "").replace("'", "").trim();
        if (cleaned.isEmpty()) {
            return "";
        }

        // Supports star: "(123456789)*" while preserving plain role IDs.
        if (cleaned.startsWith("(") && cleaned.contains(")")) {
            int end = cleaned.indexOf(')');
            if (end > 1) {
                cleaned = cleaned.substring(1, end).trim();
            }
        }

        return cleaned.replace("*", "").replace("(", "").replace(")", "").trim();
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        // Remove control chars, shell metachars, and Discord mentions
        return input.replaceAll("[\n\r;|&$`><\\\"'@]", "").replaceAll("[\\p{Cntrl}]", "").trim();
    }

    private String formatDurationForDisplay(String rawDuration) {
        Duration duration = DurationParser.parseDuration(rawDuration);
        if (duration == null) {
            return rawDuration;
        }

        long seconds = duration.getSeconds();
        long day = 24L * 60 * 60;
        long hour = 60L * 60;

        if (seconds % day == 0) {
            long days = seconds / day;
            return days + (days == 1 ? " day" : " days");
        }
        if (seconds % hour == 0) {
            long hours = seconds / hour;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private RoleProfile resolveProfile(Member member) {
        String starRawValue = findRoleId("star", "all");
        String starRoleId = parseStarRoleId(starRawValue);
        String adminRoleId = findRoleId("admin");
        String managerRoleId = findRoleId("manager");
        String srModRoleId = findRoleId("srmod", "sr.mod", "sr_mod");
        String modRoleId = findRoleId("mod");
        String jrModRoleId = findRoleId("jrmod", "jr.mod", "jr_mod");
        String srHelperRoleId = findRoleId("srhelper", "sr.helper", "sr_helper");

        Set<String> memberRoleIds = member.getRoles().stream().map(Role::getId).collect(Collectors.toSet());

        if (memberRoleIds.contains(starRoleId)) {
            return RoleProfile.fullAccess(readLimit("star"));
        }

        if (memberRoleIds.contains(adminRoleId)) {
            return RoleProfile.fullAccess(readLimit("admin"));
        }

        if (memberRoleIds.contains(managerRoleId)) {
            return RoleProfile.fullAccess(readLimit("manager"));
        }

        if (hasDisallowedRole(memberRoleIds)) {
            return RoleProfile.none();
        }

        if (memberRoleIds.contains(srModRoleId)) {
            return RoleProfile.of(
                    standardStaffActions(),
                    readLimit("srmod")
            );
        }

        if (memberRoleIds.contains(modRoleId)) {
            return RoleProfile.of(
                    standardStaffActions(),
                    readLimit("mod")
            );
        }

        if (memberRoleIds.contains(jrModRoleId)) {
            return RoleProfile.of(
                    standardStaffActions(),
                    readLimit("jrmod")
            );
        }

        if (memberRoleIds.contains(srHelperRoleId)) {
            return RoleProfile.of(
                    Set.of(Action.TEMP_MUTE, Action.HISTORY),
                    readLimit("srhelper")
            );
        }

        return RoleProfile.none();
    }

    private Set<Action> standardStaffActions() {
        return Set.of(Action.TEMP_BAN, Action.TEMP_MUTE, Action.UNBAN, Action.UNMUTE, Action.HISTORY);
    }

    private enum Action {
        IP_BAN(true, false),
        IP_MUTE(true, false),
        BAN(true, false),
        MUTE(true, false),
        TEMP_BAN(true, true),
        TEMP_MUTE(true, true),
        UNBAN(false, false),
        UNMUTE(false, false),
        HISTORY(false, false),
        RELOAD(false, false);

        private final boolean requiresReason;
        private final boolean requiresTime;

        Action(boolean requiresReason, boolean requiresTime) {
            this.requiresReason = requiresReason;
            this.requiresTime = requiresTime;
        }

        static Action fromCommand(String command) {
            return switch (command) {
                case "ipban" -> IP_BAN;
                case "ipmute" -> IP_MUTE;
                case "ban" -> BAN;
                case "mute" -> MUTE;
                case "tempban" -> TEMP_BAN;
                case "tempmute" -> TEMP_MUTE;
                case "unban" -> UNBAN;
                case "unmute" -> UNMUTE;
                case "history" -> HISTORY;
                case "linkreload" -> RELOAD;
                default -> throw new IllegalArgumentException("Unsupported command: " + command);
            };
        }
    }

    private static final Set<String> HANDLED_COMMANDS = Set.of(
            "ipban", "ipmute", "ban", "mute",
            "tempban", "tempmute",
            "unban", "unmute", "history", "linkreload"
    );

    private static final class RoleProfile {
        private final Set<Action> allowedActions;
        private final Duration maxTempDuration;

        private RoleProfile(Set<Action> allowedActions, Duration maxTempDuration) {
            this.allowedActions = allowedActions;
            this.maxTempDuration = maxTempDuration;
        }

        static RoleProfile fullAccess(Duration maxTempDuration) {
            return new RoleProfile(Set.of(Action.values()), maxTempDuration);
        }

        static RoleProfile of(Set<Action> actions, Duration maxTempDuration) {
            return new RoleProfile(actions, maxTempDuration);
        }

        static RoleProfile none() {
            return new RoleProfile(Set.of(), Duration.ZERO);
        }
    }
}
