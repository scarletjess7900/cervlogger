package net.cervlogger.command;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.cervlogger.CervLogger;
import net.cervlogger.config.ConfigHandler;
import net.cervlogger.database.ConsumerThread;
import net.cervlogger.database.Database;
import net.cervlogger.database.Lookup;
import net.cervlogger.database.Rollback;
import net.cervlogger.inspector.Inspector;
import net.cervlogger.util.Chat;
import net.cervlogger.util.TimeUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The /cl (and /cervlogger) command tree, replicating the CervLogger command set:
 *
 *   /cl help                       - command list
 *   /cl inspect | /cl i           - toggle the inspector
 *   /cl lookup <params> | /cl l   - lookup logged data
 *   /cl rollback <params> | /cl rb
 *   /cl restore <params> | /cl rs
 *   /cl near                      - lookup within 5 blocks
 *   /cl purge t:<time>            - delete old data
 *   /cl reconnect                 - reconnect to the database
 *   /cl status                    - version + queue + database info
 */
public final class CommandHandler {

    @FunctionalInterface
    private interface ParamsCommand {
        void run(CommandSourceStack source, String args);
    }

    /** Everything except /cl, /cl help and /cl inspect requires op (permission level 2). */
    private static final java.util.function.Predicate<CommandSourceStack> ADMIN =
            source -> source.hasPermission(2);

    private CommandHandler() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("cl"));
        dispatcher.register(buildRoot("cervlogger"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        // The root, help and the inspector are available to everyone;
        // every other subcommand is restricted to ops via ADMIN below.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);

        root.executes(ctx -> {
            sendHelp(ctx.getSource());
            return 1;
        });
        root.then(Commands.literal("help").executes(ctx -> {
            sendHelp(ctx.getSource());
            return 1;
        }));

        root.then(inspectNode("inspect"));
        root.then(inspectNode("i"));

        root.then(paramsNode("lookup", CommandHandler::lookup));
        root.then(paramsNode("l", CommandHandler::lookup));
        root.then(paramsNode("rollback", (source, args) -> rollback(source, args, false)));
        root.then(paramsNode("rb", (source, args) -> rollback(source, args, false)));
        root.then(paramsNode("restore", (source, args) -> rollback(source, args, true)));
        root.then(paramsNode("rs", (source, args) -> rollback(source, args, true)));
        root.then(paramsNode("purge", CommandHandler::purge));

        root.then(Commands.literal("near")
                .requires(ADMIN)
                .executes(ctx -> {
                    lookup(ctx.getSource(), "r:5");
                    return 1;
                })
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int radius = Math.min(IntegerArgumentType.getInteger(ctx, "radius"), ConfigHandler.maxRadius);
                            lookup(ctx.getSource(), "r:" + radius);
                            return 1;
                        })));
        root.then(paramsNode("structures", CommandHandler::structures));
        root.then(Commands.literal("reconnect").requires(ADMIN).executes(ctx -> {
            reconnect(ctx.getSource());
            return 1;
        }));
        root.then(Commands.literal("status").requires(ADMIN).executes(ctx -> {
            status(ctx.getSource());
            return 1;
        }));
        root.then(Commands.literal("version").requires(ADMIN).executes(ctx -> {
            status(ctx.getSource());
            return 1;
        }));
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> inspectNode(String name) {
        return Commands.literal(name).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                Chat.sendPrefixed(ctx.getSource(), Chat.text("You must be a player to use the inspector.", ChatFormatting.RED));
                return 0;
            }
            Inspector.toggle(player);
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> paramsNode(String name, ParamsCommand command) {
        return Commands.literal(name)
                .requires(ADMIN)
                .executes(ctx -> {
                    command.run(ctx.getSource(), "");
                    return 1;
                })
                .then(Commands.argument("params", StringArgumentType.greedyString()).executes(ctx -> {
                    command.run(ctx.getSource(), StringArgumentType.getString(ctx, "params"));
                    return 1;
                }));
    }

    private static void sendHelp(CommandSourceStack source) {
        Chat.send(source, Chat.text("---- ", ChatFormatting.DARK_GRAY)
                .append(Chat.text("CervLogger ", ChatFormatting.DARK_AQUA))
                .append(Chat.text("v" + CervLogger.VERSION + " ", ChatFormatting.WHITE))
                .append(Chat.text("----", ChatFormatting.DARK_GRAY)));
        sendHelpLine(source, "/cl help", "Display this list of commands.");
        sendHelpLine(source, "/cl inspect", "Toggle the inspector.");
        if (!source.hasPermission(2)) {
            // Regular players only have access to the inspector.
            return;
        }
        sendHelpLine(source, "/cl lookup <params>", "Lookup block/container/other data.");
        sendHelpLine(source, "/cl rollback <params>", "Rollback block data.");
        sendHelpLine(source, "/cl restore <params>", "Restore rolled back data.");
        sendHelpLine(source, "/cl near [radius]", "Lookup nearby (default radius 5).");
        sendHelpLine(source, "/cl structures [r:<radius>]", "List loaded contraptions/physics structures.");
        sendHelpLine(source, "/cl purge t:<time>", "Delete old data.");
        sendHelpLine(source, "/cl reconnect", "Reconnect to the database.");
        sendHelpLine(source, "/cl status", "View the mod status.");
        Chat.send(source, Chat.text("Params: ", ChatFormatting.DARK_AQUA)
                .append(Chat.text("u:<user> t:<time> r:<radius|#global> a:<action> b:<blocks> e:<exclude> w:<world> p:<page>", ChatFormatting.GRAY)));
        Chat.send(source, Chat.text("Actions: ", ChatFormatting.DARK_AQUA)
                .append(Chat.text("block, +block, -block, click, explosion, burn, container, +container, -container, kill, sign, chat, command, session", ChatFormatting.GRAY)));
    }

    private static void sendHelpLine(CommandSourceStack source, String command, String description) {
        Chat.send(source, Chat.text(command, ChatFormatting.DARK_AQUA)
                .append(Chat.text(" - " + description, ChatFormatting.WHITE)));
    }

    private static void lookup(CommandSourceStack source, String args) {
        if (!Database.isReady()) {
            Chat.sendPrefixed(source, Chat.text("Database is not ready.", ChatFormatting.RED));
            return;
        }
        final Params params;
        try {
            params = Params.parse(source, args);
        }
        catch (IllegalArgumentException e) {
            Chat.sendPrefixed(source, Chat.text(e.getMessage(), ChatFormatting.RED));
            return;
        }
        if (args.isBlank()) {
            Chat.sendPrefixed(source, Chat.text("Please specify lookup parameters, e.g. \"/cl lookup u:Steve t:1h r:20\".", ChatFormatting.WHITE));
            return;
        }
        final int pageSize = ConfigHandler.lookupPageSize;
        Thread thread = new Thread(() -> {
            try {
                int total = Lookup.count(params, null);
                List<Lookup.Row> rows = Lookup.lookup(params, null, pageSize, (params.page - 1) * pageSize, false);
                int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
                source.getServer().execute(() -> {
                    if (total == 0) {
                        Chat.sendPrefixed(source, Chat.text("No results found.", ChatFormatting.WHITE));
                        return;
                    }
                    Chat.send(source, Chat.text("---- ", ChatFormatting.DARK_GRAY)
                            .append(Chat.text("CervLogger Lookup Results ", ChatFormatting.DARK_AQUA))
                            .append(Chat.text("----", ChatFormatting.DARK_GRAY)));
                    for (Lookup.Row row : rows) {
                        Chat.send(source, formatRow(params, row));
                    }
                    Chat.send(source, Chat.text("Page " + params.page + "/" + totalPages, ChatFormatting.GRAY)
                            .append(Chat.text(" (" + total + " result(s)). Use p:<page> to view more.", ChatFormatting.GRAY)));
                });
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Lookup failed", e);
                source.getServer().execute(() -> Chat.sendPrefixed(source,
                        Chat.text("Lookup failed: " + e.getMessage(), ChatFormatting.RED)));
            }
        }, "CervLogger-Lookup");
        thread.setDaemon(true);
        thread.start();
    }

    private static MutableComponent formatRow(Params params, Lookup.Row row) {
        MutableComponent line = Chat.text(TimeUtils.formatAgo(row.time()) + " ago ", ChatFormatting.GRAY)
                .append(Chat.text("- ", ChatFormatting.WHITE))
                .append(Chat.text(row.user(), ChatFormatting.DARK_AQUA));
        switch (params.table) {
            case BLOCK -> {
                line = Inspector.formatBlockRow(row);
                line.append(coords(row));
            }
            case CONTAINER -> {
                line = Inspector.formatContainerRow(row);
                line.append(coords(row));
            }
            case KILL -> line.append(Chat.text(" killed ", ChatFormatting.WHITE))
                    .append(Chat.text(strip(row.type()), ChatFormatting.DARK_AQUA))
                    .append(coords(row));
            case SIGN -> line.append(Chat.text(" edited sign: ", ChatFormatting.WHITE))
                    .append(Chat.text("\"" + row.type() + "\"", ChatFormatting.GRAY))
                    .append(coords(row));
            case SESSION -> line.append(Chat.text(row.action() == 1 ? " logged in" : " logged out", ChatFormatting.WHITE))
                    .append(coords(row));
            case CHAT -> line.append(Chat.text(": ", ChatFormatting.WHITE))
                    .append(Chat.text(row.type(), ChatFormatting.GRAY));
            case COMMAND -> line.append(Chat.text(": ", ChatFormatting.WHITE))
                    .append(Chat.text(row.type(), ChatFormatting.GRAY));
        }
        return line;
    }

    private static MutableComponent coords(Lookup.Row row) {
        return Chat.text(" (x" + row.x() + "/y" + row.y() + "/z" + row.z() + "/" + strip(row.world()) + ")", ChatFormatting.GRAY);
    }

    private static String strip(String id) {
        if (id == null) {
            return "unknown";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /**
     * /cl structures [r:<radius>] - lists currently loaded moving structures:
     * Create contraption entities and (when they expose entities) Aeronautics/Sable
     * structures. Sable sub-levels are managed regions, not entities, and are not
     * enumerable without depending on the Sable API - use /sable for those.
     * Neither Create nor Sable stores which player assembled a structure, so
     * attribution comes from CervLogger's own logs, e.g.
     * "/cl lookup a:click b:physics_assembler t:1d" or "/cl lookup a:command u:<player>".
     */
    private static void structures(CommandSourceStack source, String args) {
        final Params params;
        try {
            params = Params.parse(source, args);
        }
        catch (IllegalArgumentException e) {
            Chat.sendPrefixed(source, Chat.text(e.getMessage(), ChatFormatting.RED));
            return;
        }
        int radius = params.hasRadius() ? params.radius : -1;
        Chat.send(source, Chat.text("---- ", ChatFormatting.DARK_GRAY)
                .append(Chat.text("Loaded moving structures ", ChatFormatting.DARK_AQUA))
                .append(Chat.text("----", ChatFormatting.DARK_GRAY)));
        int found = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            if (radius >= 0 && level != source.getLevel()) {
                continue;
            }
            for (Entity entity : level.getAllEntities()) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                String namespace = id.getNamespace();
                boolean isContraption = namespace.equals("create") && id.getPath().contains("contraption");
                boolean isPhysics = namespace.equals("sable") || namespace.equals("aeronautics")
                        || namespace.equals("create_aeronautics") || id.getPath().contains("physics");
                if (!isContraption && !isPhysics) {
                    continue;
                }
                if (radius >= 0 && entity.position().distanceTo(source.getPosition()) > radius) {
                    continue;
                }
                found++;
                Chat.send(source, Chat.text("#" + entity.getId() + " ", ChatFormatting.GRAY)
                        .append(Chat.text(id.toString(), ChatFormatting.DARK_AQUA))
                        .append(Chat.text(" (x" + entity.getBlockX() + "/y" + entity.getBlockY() + "/z" + entity.getBlockZ()
                                + "/" + strip(level.dimension().location().toString()) + ")", ChatFormatting.GRAY)));
            }
        }
        if (found == 0) {
            Chat.send(source, Chat.text("No loaded contraptions or physics structures found"
                    + (radius >= 0 ? " within r:" + radius : "") + ".", ChatFormatting.WHITE));
        }
        Chat.send(source, Chat.text("Sable sub-levels are not entities - query them with /sable. "
                + "For attribution use /cl lookup a:click b:physics_assembler.", ChatFormatting.GRAY));
    }

    private static void rollback(CommandSourceStack source, String args, boolean restore) {
        if (!Database.isReady()) {
            Chat.sendPrefixed(source, Chat.text("Database is not ready.", ChatFormatting.RED));
            return;
        }
        final Params params;
        try {
            params = Params.parse(source, args);
        }
        catch (IllegalArgumentException e) {
            Chat.sendPrefixed(source, Chat.text(e.getMessage(), ChatFormatting.RED));
            return;
        }
        if (params.since <= 0) {
            Chat.sendPrefixed(source, Chat.text("Please specify the amount of time to " + (restore ? "restore" : "rollback") + ", e.g. t:1h.", ChatFormatting.WHITE));
            return;
        }
        if (params.users.isEmpty() && params.radius == Integer.MIN_VALUE) {
            Chat.sendPrefixed(source, Chat.text("Please specify a user (u:<user>) and/or a radius (r:<radius>).", ChatFormatting.WHITE));
            return;
        }
        if (params.table == Params.LogTable.CHAT || params.table == Params.LogTable.COMMAND
                || params.table == Params.LogTable.SESSION || params.table == Params.LogTable.SIGN) {
            Chat.sendPrefixed(source, Chat.text("That action cannot be rolled back.", ChatFormatting.WHITE));
            return;
        }
        if (params.actions.isEmpty() && params.table == Params.LogTable.BLOCK) {
            params.actions.add(0);
            params.actions.add(1);
            params.actions.add(3);
            params.actions.add(4);
        }
        Chat.sendPrefixed(source, Chat.text((restore ? "Restore" : "Rollback") + " started...", ChatFormatting.WHITE));
        Rollback.run(source, params, restore);
    }

    private static void purge(CommandSourceStack source, String args) {
        if (!Database.isReady()) {
            Chat.sendPrefixed(source, Chat.text("Database is not ready.", ChatFormatting.RED));
            return;
        }
        final Params params;
        try {
            params = Params.parse(source, args);
        }
        catch (IllegalArgumentException e) {
            Chat.sendPrefixed(source, Chat.text(e.getMessage(), ChatFormatting.RED));
            return;
        }
        if (params.since <= 0) {
            Chat.sendPrefixed(source, Chat.text("Please specify the age of data to purge, e.g. \"/cl purge t:30d\" removes data older than 30 days.", ChatFormatting.WHITE));
            return;
        }
        final long threshold = params.since;
        Chat.sendPrefixed(source, Chat.text("Data purge started. This may take some time...", ChatFormatting.WHITE));
        Thread thread = new Thread(() -> {
            long removed = 0;
            try {
                synchronized (Database.lock()) {
                    var conn = Database.connection();
                    for (String table : new String[] { "co_block", "co_container", "co_entity_kill", "co_sign", "co_chat", "co_command", "co_session" }) {
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE time < ?")) {
                            ps.setLong(1, threshold);
                            removed += ps.executeUpdate();
                        }
                    }
                    if (!Database.isMySql()) {
                        // VACUUM is SQLite-only; InnoDB reclaims space on its own.
                        try (Statement st = conn.createStatement()) {
                            st.execute("VACUUM");
                        }
                    }
                }
                final long total = removed;
                source.getServer().execute(() -> Chat.sendPrefixed(source,
                        Chat.text("Data purge successful. Deleted " + total + " row(s).", ChatFormatting.WHITE)));
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Purge failed", e);
                source.getServer().execute(() -> Chat.sendPrefixed(source,
                        Chat.text("Purge failed: " + e.getMessage(), ChatFormatting.RED)));
            }
        }, "CervLogger-Purge");
        thread.setDaemon(true);
        thread.start();
    }

    private static void reconnect(CommandSourceStack source) {
        synchronized (Database.lock()) {
            try {
                if (Database.isConnected()) {
                    Chat.sendPrefixed(source, Chat.text("Database is already connected.", ChatFormatting.YELLOW));
                    return;
                }
                Database.ensureConnection();
                Chat.sendPrefixed(source, Chat.text("Reconnected to " + Database.description() + ".", ChatFormatting.GREEN));
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Manual reconnect failed", e);
                Chat.sendPrefixed(source, Chat.text("Failed to reconnect to database.", ChatFormatting.RED));
            }
        }
    }

    private static void status(CommandSourceStack source) {
        Chat.send(source, Chat.text("---- ", ChatFormatting.DARK_GRAY)
                .append(Chat.text("CervLogger ", ChatFormatting.DARK_AQUA))
                .append(Chat.text("----", ChatFormatting.DARK_GRAY)));
        Chat.send(source, Chat.text("Version: ", ChatFormatting.DARK_AQUA)
                .append(Chat.text(CervLogger.VERSION + " (NeoForge 1.21.1)", ChatFormatting.WHITE)));
        Chat.send(source, Chat.text("Database: ", ChatFormatting.DARK_AQUA)
                .append(Chat.text(Database.description() + (Database.isReady() ? " (connected)" : " (NOT CONNECTED)"), ChatFormatting.WHITE)));
        if (!Database.isMySql()) {
            long sizeMb = 0;
            try {
                sizeMb = java.nio.file.Files.size(Database.databaseFile()) / (1024 * 1024);
            }
            catch (Exception ignored) {
            }
            Chat.send(source, Chat.text("Database size: ", ChatFormatting.DARK_AQUA)
                    .append(Chat.text(sizeMb + " MB", ChatFormatting.WHITE)));
        }
        Chat.send(source, Chat.text("Consumer queue: ", ChatFormatting.DARK_AQUA)
                .append(Chat.text(ConsumerThread.size() + " item(s)", ChatFormatting.WHITE)));
    }
}
