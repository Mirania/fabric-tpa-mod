package com.tpaandhomes.fabrictpa;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.tpaandhomes.mods.ConfigUtils;
import com.tpaandhomes.mods.HomeUtils;
import com.tpaandhomes.mods.TeleportUtils;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.command.argument.EntityArgumentType.getPlayer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class FabricTPA implements ModInitializer {
    private static final Logger logger = LogManager.getLogger("tpa-and-homes");
    private static final String CONFIG_NAME = "FabricTPA.properties";

    private static final String HOMES_NAME = "homes_and_tpa.json";

    private final ArrayList<TPARequest> activeTPA = new ArrayList<>();
    private final HashMap<UUID, Long> recentRequests = new HashMap<>();
    private ConfigUtils config;

    private HomeUtils homes;

    @Nullable
    private static CompletableFuture<Suggestions> filterSuggestionsByInput(final SuggestionsBuilder builder, final List<String> values) {
        final String start = builder.getRemaining().toLowerCase();
        values.stream().filter(s -> s.toLowerCase().startsWith(start)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> getTPAInitSuggestions(final CommandContext<ServerCommandSource> context, final SuggestionsBuilder builder) {
        final ServerCommandSource scs = context.getSource();

        final List<String> activeTargets = Stream.concat(
                this.activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName()),
                this.activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName())
        ).toList();
        final List<String> others = Arrays.stream(scs.getServer().getPlayerNames())
                .filter(s -> !s.equals(scs.getName()) && !activeTargets.contains(s))
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, others);
    }

    private CompletableFuture<Suggestions> getTPATargetSuggestions(final CommandContext<ServerCommandSource> context, final SuggestionsBuilder builder) {
        final List<String> activeTargets = this.activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getTPASenderSuggestions(final CommandContext<ServerCommandSource> context, final SuggestionsBuilder builder) {
        final List<String> activeTargets = this.activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName()).collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    static class CooldownModeConfigValue extends ConfigUtils.IConfigValue<TPACooldownMode> {
        public CooldownModeConfigValue(@NotNull final String name, final TPACooldownMode defaultValue, @Nullable final ConfigUtils.Command command) {
            super(name, defaultValue, null, command, (context, builder) -> {
                final List<String> tcmValues = Arrays.stream(TPACooldownMode.values()).map(String::valueOf).collect(Collectors.toList());
                return filterSuggestionsByInput(builder, tcmValues);
            });
        }

        @Override
        public TPACooldownMode getFromProps(final Properties props) {
            return TPACooldownMode.valueOf(props.getProperty(this.name));
        }

        @Override
        public ArgumentType<?> getArgumentType() {
            return StringArgumentType.string();
        }

        @Override
        public TPACooldownMode parseArgumentValue(final CommandContext<ServerCommandSource> ctx) {
            return TPACooldownMode.valueOf(StringArgumentType.getString(ctx, this.name));
        }
    }

    @Override
    public void onInitialize() {
        logger.info("Initializing tpa-and-homes...");

        this.config = new ConfigUtils(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME).toFile(), logger, Arrays.asList(new ConfigUtils.IConfigValue[] {
                new ConfigUtils.IntegerConfigValue("timeout", 60, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Timeout is %s seconds", "Timeout set to %s seconds")),
                new ConfigUtils.IntegerConfigValue("stand-still", 5, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Stand-Still time is %s seconds", "Stand-Still time set to %s seconds")),
                new ConfigUtils.IntegerConfigValue("cooldown", 5, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("Cooldown is %s seconds", "Cooldown set to %s seconds")),
                new ConfigUtils.BooleanConfigValue("bossbar", true,
                        new ConfigUtils.Command("Boss-Bar on: %s", "Boss-Bar is now: %s")),
                new CooldownModeConfigValue("cooldown-mode", TPACooldownMode.WhoTeleported,
                        new ConfigUtils.Command("Cooldown Mode is %s", "Cooldown Mode set to %s")),
                new ConfigUtils.IntegerConfigValue("homes", 6, new ConfigUtils.IntegerConfigValue.IntLimits(0),
                        new ConfigUtils.Command("%s homes per player", "Homes per player set to %s")),
        }));

        this.homes = new HomeUtils(FabricLoader.getInstance().getConfigDir().resolve(HOMES_NAME), logger);

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("tpa")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> this.tpaInit(ctx, getPlayer(ctx, "target")))));

            /*dispatcher.register(literal("tpahere")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> this.tpaHere(ctx, getPlayer(ctx, "target")))));*/

            dispatcher.register(literal("tpaaccept")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> this.tpaAccept(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> this.tpaAccept(ctx, null)));

            /*dispatcher.register(literal("tpadeny")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> this.tpaDeny(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> this.tpaDeny(ctx, null)));

            dispatcher.register(literal("tpacancel")
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPASenderSuggestions)
                            .executes(ctx -> this.tpaCancel(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> this.tpaCancel(ctx, null)));*/

            dispatcher.register(literal("home")
                    .then(literal("set")
                            .then(argument("name", StringArgumentType.string())
                                .executes(ctx -> this.homeSet(ctx, StringArgumentType.getString(ctx, "name"), false))))
                    .then(literal("delete")
                            .then(argument("name", StringArgumentType.string()).suggests((src, builder) -> this.getHomeSuggestions(src, builder, false))
                                    .executes(ctx -> this.homeDelete(ctx, StringArgumentType.getString(ctx, "name"), false))))
                    .then(literal("list")
                            .executes(ctx -> this.homeList(ctx, false)))
                    .then(literal("tp")
                            .then(argument("name", StringArgumentType.string()).suggests((src, builder) -> this.getHomeSuggestions(src, builder, false))
                                .executes(ctx -> this.homeTp(ctx, StringArgumentType.getString(ctx, "name"), false)))));

            dispatcher.register(literal("warp")
                    .then(literal("set")
                            .requires(source -> source.hasPermissionLevel(4))
                            .then(argument("name", StringArgumentType.string())
                                    .executes(ctx -> this.homeSet(ctx, StringArgumentType.getString(ctx, "name"), true))))
                    .then(literal("delete")
                            .requires(source -> source.hasPermissionLevel(4))
                            .then(argument("name", StringArgumentType.string()).suggests((src, builder) -> this.getHomeSuggestions(src, builder, true))
                                    .executes(ctx -> this.homeDelete(ctx, StringArgumentType.getString(ctx, "name"), true))))
                    .then(literal("list")
                            .executes(ctx -> this.homeList(ctx, true)))
                    .then(literal("tp")
                            .then(argument("name", StringArgumentType.string()).suggests((src, builder) -> this.getHomeSuggestions(src, builder, true))
                                    .executes(ctx -> this.homeTp(ctx, StringArgumentType.getString(ctx, "name"), true)))));
        });

    }

    public int homeSet(final CommandContext<ServerCommandSource> ctx, final String name, final boolean isGlobal) {
        final ServerPlayerEntity player = ctx.getSource().getPlayer();
        final List<HomeUtils.HomeLocation> homeList = this.homes.getHomes(player, isGlobal);
        final int maxHomes = (int) this.config.getValue("homes");

        homeList.removeIf(home -> home.name.toLowerCase().equals(name.toLowerCase()));

        if (!isGlobal && homeList.size() >= maxHomes) {
            player.sendMessage(Text.literal("Can't set more homes! The limit is %d.".formatted(maxHomes)).formatted(Formatting.RED), false);
            return 1;
        }

        this.homes.setNewHome(player, name, isGlobal);
        this.homes.save();
        player.sendMessage(Text.literal("Set a new %s called '".formatted(isGlobal ? "global warp" : "home")).formatted(Formatting.DARK_GREEN)
                        .append(Text.literal(name).formatted(Formatting.GOLD))
                        .append(Text.literal("'!").formatted(Formatting.DARK_GREEN)),
                false);

        return 1;
    }

    public int homeDelete(final CommandContext<ServerCommandSource> ctx, final String name, final boolean isGlobal) {
        final ServerPlayerEntity player = ctx.getSource().getPlayer();
        final List<HomeUtils.HomeLocation> homeList = this.homes.getHomes(player, isGlobal);

        homeList.removeIf(home -> home.name.toLowerCase().equals(name.toLowerCase()));
        this.homes.save();

        player.sendMessage(Text.literal("Deleted the %s named '".formatted(isGlobal ? "global warp" : "home")).formatted(Formatting.DARK_GREEN)
                        .append(Text.literal(name).formatted(Formatting.GOLD))
                        .append(Text.literal("'!").formatted(Formatting.DARK_GREEN)),
                false);
        return 1;
    }

    public int homeList(final CommandContext<ServerCommandSource> ctx, final boolean isGlobal) {
        final ServerPlayerEntity player = ctx.getSource().getPlayer();
        final List<HomeUtils.HomeLocation> homes = this.homes.getHomes(player, isGlobal);
        final int maxHomes = (int) this.config.getValue("homes");

        if (homes.isEmpty()) {
            player.sendMessage(Text.literal(isGlobal ? "There are no global warps right now." : "You have no homes set. You are homeless.").formatted(Formatting.RED), false);
            return 1;
        }

        player.sendMessage(Text.literal(isGlobal ?
                "▶ The global warps. You can click them to tp there. ◀" :
                "▶ Your homes. You can click them to tp there. ◀").formatted(Formatting.DARK_GREEN), false);

        for (int i = 0; i < homes.size(); i++) {
            final HomeUtils.HomeLocation home = homes.get(i);

            final String dimensionName = home.dimension.equals("overworld") ? "Overworld" :
                    home.dimension.equals("the_nether") ? "Nether" :
                            home.dimension.equals("the_end") ? "The End" :
                                    "Aether";

            final MutableText homeTitle = isGlobal ?
                    Text.literal("Warp '") :
                    Text.literal("Home #%d/%d '".formatted(i + 1, maxHomes));

            final MutableText tooltip = Text.literal("Click to teleport to '")
                    .append(Text.literal(home.name).formatted(Formatting.GOLD))
                    .append("'!");

            player.sendMessage(homeTitle.formatted(Formatting.DARK_GREEN)
                            .append(Text.literal(home.name).formatted(Formatting.GOLD))
                            .append(Text.literal("' • ").formatted(Formatting.DARK_GREEN))
                            .append(Text.literal(dimensionName).formatted(Formatting.GOLD))
                            .append(Text.literal(" (x=%.0f, y=%.0f, z=%.0f)".formatted(home.x, home.y, home.z)).formatted(Formatting.DARK_GREEN))
                            .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/%s tp %s".formatted(isGlobal ? "warp" : "home", home.name)))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip))),
                    false);
        }

        return 1;
    }

    public CompletableFuture<Suggestions> getHomeSuggestions(final CommandContext<ServerCommandSource> ctx, final SuggestionsBuilder builder, final boolean isGlobal) {
        final ServerPlayerEntity player = ctx.getSource().getPlayer();
        final String input = builder.getRemaining().toLowerCase();

        this.homes.getHomes(player, isGlobal).stream()
                .map(home -> home.name)
                .filter(name -> name.toLowerCase().startsWith(input))
                .forEach(builder::suggest);

        return builder.buildFuture();
    }

    public int homeTp(final CommandContext<ServerCommandSource> ctx, final String name, final boolean isGlobal) {
        final ServerPlayerEntity player = ctx.getSource().getPlayer();
        final Optional<HomeUtils.HomeLocation> target = this.homes.getHomes(player, isGlobal).stream()
                .filter(home -> home.name.toLowerCase().equals(name.toLowerCase()))
                .findAny();

        if (target.isEmpty()) {
            player.sendMessage(Text.literal("There is no %s with the name '%s'!".formatted(isGlobal ? "global warp" : "home", name)).formatted(Formatting.RED), false);
            return 1;
        }

        ServerWorld targetWorld = player.getServerWorld();
        for (final var world : player.getServer().getWorlds()) {
            if (world.getRegistryKey().getValue().getPath().equals(target.get().dimension)) {
                targetWorld = world;
                break;
            }
        }

        player.teleport(targetWorld, target.get().x, target.get().y, target.get().z, target.get().yaw, target.get().pitch);
        return 1;
    }

    public int tpaInit(final CommandContext<ServerCommandSource> ctx, final ServerPlayerEntity tTo) {
        final ServerPlayerEntity tFrom = ctx.getSource().getPlayer();

        if (tFrom.equals(tTo)) {
            tFrom.sendMessage(Text.literal("You cannot request to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (this.checkCooldown(tFrom)) return 1;

        final TPARequest tr = new TPARequest(tFrom, tTo, false, (int) this.config.getValue("timeout") * 1000);
        if (this.activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tFrom.sendMessage(Text.literal("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            this.activeTPA.remove(tr);
            tFrom.sendMessage(Text.literal("Your teleport request to " + tTo.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
            tTo.sendMessage(Text.literal("Teleport request from " + tFrom.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
        });
        this.activeTPA.add(tr);

        tFrom.sendMessage(
                Text.literal("You have requested to teleport to ").formatted(Formatting.BLUE)
                        .append(Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal("\nTo cancel type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + this.config.getValue("timeout") + " seconds.").formatted(Formatting.BLUE)),
                false);

        tTo.sendMessage(
                Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" has requested to teleport to you!").formatted(Formatting.BLUE))
                        .append(Text.literal("\nTo accept type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nTo deny type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + this.config.getValue("timeout") + " seconds.").formatted(Formatting.BLUE)),
                false);
        return 1;
    }

    public int tpaHere(final CommandContext<ServerCommandSource> ctx, final ServerPlayerEntity tFrom) throws CommandSyntaxException {
        final ServerPlayerEntity tTo = ctx.getSource().getPlayer();

        if (tTo.equals(tFrom)) {
            tTo.sendMessage(Text.literal("You cannot request for you to teleport to yourself!").formatted(Formatting.RED), false);
            return 1;
        }

        if (this.checkCooldown(tFrom)) return 1;

        final TPARequest tr = new TPARequest(tFrom, tTo, true, (int) this.config.getValue("timeout") * 1000);
        if (this.activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tTo.sendMessage(Text.literal("There is already an ongoing request like this!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            this.activeTPA.remove(tr);
            tTo.sendMessage(Text.literal("Your teleport request for " + tFrom.getEntityName() + " to you has timed out!").formatted(Formatting.RED), false);
            tFrom.sendMessage(Text.literal("Teleport request for you to " + tTo.getEntityName() + " has timed out!").formatted(Formatting.RED), false);
        });
        this.activeTPA.add(tr);

        tTo.sendMessage(
                Text.literal("You have requested for ").formatted(Formatting.BLUE)
                        .append(Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal(" to teleport to you!\nTo cancel type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpacancel [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + this.config.getValue("timeout") + " seconds.").formatted(Formatting.BLUE)),
                false);

        tFrom.sendMessage(
                Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" has requested for you to teleport to them!").formatted(Formatting.BLUE))
                        .append(Text.literal("\nTo accept type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpaaccept [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nTo deny type ").formatted(Formatting.BLUE))
                        .append(Text.literal("/tpadeny [<player>]").styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\nThis request will timeout in " + this.config.getValue("timeout") + " seconds.").formatted(Formatting.BLUE)),
                false);
        return 1;
    }

    private boolean checkCooldown(final ServerPlayerEntity tFrom) {
        if (this.recentRequests.containsKey(tFrom.getUuid())) {
            final long diff = Instant.now().getEpochSecond() - this.recentRequests.get(tFrom.getUuid());
            if (diff < (int) this.config.getValue("cooldown")) {
                tFrom.sendMessage(Text.literal("You cannot make a request for ").append(String.valueOf((int) this.config.getValue("cooldown") - diff))
                        .append(" more seconds!").formatted(Formatting.RED), false);
                return true;
            }
        }
        return false;
    }

    private enum TPAAction {
        ACCEPT, DENY, CANCEL
    }

    private TPARequest getTPARequest(final ServerPlayerEntity rFrom, final ServerPlayerEntity rTo, final TPAAction action) {
        final Optional<TPARequest> otr = this.activeTPA.stream()
                .filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom) && tpaRequest.rTo.equals(rTo)).findFirst();

        if (otr.isEmpty()) {
            if (action == TPAAction.CANCEL) {
                rFrom.sendMessage(Text.literal("No ongoing request!").formatted(Formatting.RED), false);
            } else {
                rTo.sendMessage(Text.literal("No ongoing request!").formatted(Formatting.RED), false);
            }
            return null;
        }

        return otr.get();
    }

    public int tpaAccept(final CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();

        if (rFrom == null) {
            final TPARequest[] candidates;
            candidates = this.activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                final MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify whose request to accept.\n").formatted(Formatting.BLUE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        final TPARequest tr = this.getTPARequest(rFrom, rTo, TPAAction.ACCEPT);
        if (tr == null) return 1;
        TeleportUtils.genericTeleport((boolean) this.config.getValue("bossbar"), (int) this.config.getValue("stand-still"), rFrom, () -> {
            if (tr.tFrom.isRemoved() || tr.tTo.isRemoved()) tr.refreshPlayers();
            tr.tFrom.teleport(tr.tTo.getServerWorld(), tr.tTo.getX(), tr.tTo.getY(), tr.tTo.getZ(), tr.tTo.getYaw(), tr.tTo.getPitch());
            switch ((TPACooldownMode) this.config.getValue("cooldown-mode")) {
                case BothUsers -> {
                    this.recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                    this.recentRequests.put(tr.tTo.getUuid(), Instant.now().getEpochSecond());
                }
                case WhoInitiated -> this.recentRequests.put(tr.rFrom.getUuid(), Instant.now().getEpochSecond());
                case WhoTeleported -> this.recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
            }
        });

        tr.cancelTimeout();
        this.activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("You have accepted the teleport request!"), false);
        tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has accepted the teleportation request!").formatted(Formatting.BLUE)), false);
        return 1;
    }


    public int tpaDeny(final CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom) throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();

        if (rFrom == null) {
            final TPARequest[] candidates;
            candidates = this.activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                final MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify whose request to deny.\n").formatted(Formatting.BLUE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpadeny " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        final TPARequest tr = this.getTPARequest(rFrom, rTo, TPAAction.DENY);
        if (tr == null) return 1;
        tr.cancelTimeout();
        this.activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("You have cancelled the teleport request!"), false);
        tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
        return 1;
    }

    public int tpaCancel(final CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rTo) throws CommandSyntaxException {
        final ServerPlayerEntity rFrom = ctx.getSource().getPlayer();

        if (rTo == null) {
            final TPARequest[] candidates;
            candidates = this.activeTPA.stream().filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                final MutableText text = Text.literal("You currently have multiple active teleport requests! Please specify which request to cancel.\n").formatted(Formatting.BLUE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rTo.getEntityName()).forEach(name ->
                        text.append(Text.literal(name).styled(s ->
                                s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + name))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + name)))
                                        .withColor(Formatting.GOLD))).append(" "));
                rFrom.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rFrom.sendMessage(Text.literal("You currently don't have any teleport requests!").formatted(Formatting.RED), false);
                return 1;
            }
            rTo = candidates[0].rTo;
        }

        System.out.printf("%s -> %s\n", rFrom.getEntityName(), rTo.getEntityName());
        final TPARequest tr = this.getTPARequest(rFrom, rTo, TPAAction.CANCEL);
        if (tr == null) return 1;
        tr.cancelTimeout();
        this.activeTPA.remove(tr);
        tr.rFrom.sendMessage(Text.literal("You have cancelled the teleport request!").formatted(Formatting.RED), false);
        tr.rTo.sendMessage(Text.literal(tr.rFrom.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" has cancelled the teleportation request!").formatted(Formatting.RED)), false);
        return 1;
    }


    enum TPACooldownMode {
        WhoTeleported, WhoInitiated, BothUsers
    }

    static class TPARequest {
        ServerPlayerEntity tFrom;
        ServerPlayerEntity tTo;

        ServerPlayerEntity rFrom;
        ServerPlayerEntity rTo;

        boolean tpaHere;
        long timeout;

        Timer timer;

        public TPARequest(final ServerPlayerEntity tFrom, final ServerPlayerEntity tTo, final boolean tpaHere, final int timeoutMS) {
            this.tFrom = tFrom;
            this.tTo = tTo;
            this.tpaHere = tpaHere;
            this.timeout = timeoutMS;
            this.rFrom = tpaHere ? tTo : tFrom;
            this.rTo = tpaHere ? tFrom : tTo;
        }

        void setTimeoutCallback(final Timeout callback) {
            this.timer = new Timer();
            this.timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    callback.onTimeout();
                }
            }, this.timeout);
        }

        void cancelTimeout() {
            this.timer.cancel();
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || this.getClass() != o.getClass()) return false;
            final TPARequest that = (TPARequest) o;
            return this.tFrom.equals(that.tFrom) && this.tTo.equals(that.tTo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.tFrom, this.tTo);
        }

        @Override
        public String toString() {
            return "TPARequest{" + "tFrom=" + this.tFrom +
                    ", tTo=" + this.tTo +
                    ", rFrom=" + this.rFrom +
                    ", rTo=" + this.rTo +
                    ", tpaHere=" + this.tpaHere +
                    '}';
        }

        public void refreshPlayers() {
            this.tFrom = this.tFrom.server.getPlayerManager().getPlayer(this.tFrom.getUuid());
            this.tTo = this.tTo.server.getPlayerManager().getPlayer(this.tTo.getUuid());
            this.rFrom = this.tpaHere ? this.tTo : this.tFrom;
            this.rTo = this.tpaHere ? this.tFrom : this.tTo;
            assert this.tFrom != null && this.tTo != null;
        }
    }

    interface Timeout {
        void onTimeout();
    }
}
