package net.okocraft.kansokusha.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.okocraft.kansokusha.common.command.CommandHelp;
import net.okocraft.kansokusha.common.command.CommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandMessages;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.EventFieldMessages;
import net.okocraft.kansokusha.common.command.EventPresentationMessages;
import net.okocraft.kansokusha.common.command.EventRenderer;
import net.okocraft.kansokusha.common.command.HealthCommandMessages;
import net.okocraft.kansokusha.common.command.HealthCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.command.SearchGuide;
import net.okocraft.kansokusha.common.command.SearchNavigation;
import net.okocraft.kansokusha.common.runtime.KansokushaRuntime;
import net.okocraft.kansokusha.common.search.query.SearchQuery;
import net.okocraft.kansokusha.common.search.query.SearchQueryMessages;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

final class NativeCommands {
    static List<Map<String, String>> defaultMessages() {
        return List.of(CommandMessages.defaultMessages(), SearchGuide.defaultMessages(),
            EventPresentationMessages.defaultMessages(), SearchCommandMessages.defaultMessages(),
            SearchQueryMessages.defaultMessages(), EventCommandMessages.defaultMessages(),
            EventFieldMessages.defaultMessages(), HealthCommandMessages.defaultMessages(),
            NativeInspection.defaultMessages());
    }

    static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("kansokusha")
            .requires(source -> NativePermissions.has(source, "kansokusha.command"))
            .executes(context -> help(context.getSource()))
            .then(Commands.literal("help").executes(context -> help(context.getSource())))
            .then(Commands.literal("version").requires(source -> NativePermissions.has(source, "kansokusha.command.version"))
                .executes(context -> {
                    var version = ModList.get().getModContainerById("kansokusha")
                        .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
                    send(context.getSource()).accept(Component.text("Kansokusha " + version));
                    return 1;
                }))
            .then(argumentCommand("search", SearchCommandSupport.PERMISSION))
            .then(argumentCommand("event", EventCommandSupport.PERMISSION))
            .then(argumentCommand("teleport", "kansokusha.command.teleport"))
            .then(Commands.literal("inspect").requires(source -> NativePermissions.has(source, "kansokusha.command.inspect"))
                .executes(context -> NativeInspection.toggle(context.getSource()))
                .then(Commands.literal("on").executes(context -> NativeInspection.set(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> NativeInspection.set(context.getSource(), false)))
                .then(Commands.literal("page").then(Commands.argument("target-and-cursor", StringArgumentType.greedyString())
                    .executes(context -> NativeInspection.page(context.getSource(), StringArgumentType.getString(context, "target-and-cursor"))))))
            .then(Commands.literal("i").requires(source -> NativePermissions.has(source, "kansokusha.command.inspect"))
                .executes(context -> NativeInspection.toggle(context.getSource()))
                .then(Commands.literal("on").executes(context -> NativeInspection.set(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> NativeInspection.set(context.getSource(), false)))
                .then(Commands.literal("page").then(Commands.argument("target-and-cursor", StringArgumentType.greedyString())
                    .executes(context -> NativeInspection.page(context.getSource(), StringArgumentType.getString(context, "target-and-cursor"))))))
            .then(Commands.literal("admin").then(Commands.literal("health")
                .requires(source -> NativePermissions.has(source, HealthCommandSupport.PERMISSION))
                .executes(context -> health(context.getSource(), false))
                .then(Commands.literal("stored").executes(context -> health(context.getSource(), true)))));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> argumentCommand(String name, String permission) {
        return Commands.literal(name).requires(source -> NativePermissions.has(source, permission))
            .executes(context -> execute(context.getSource(), name, ""))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(context -> execute(context.getSource(), name, StringArgumentType.getString(context, "args")))
                .suggests((context, builder) -> {
                    if (!name.equals("search") || KansokushaNeoForge.runtime() == null) return builder.buildFuture();
                    var source = context.getSource();
                    var clock = Clock.systemUTC();
                    return SearchCommandSupport.suggest(KansokushaNeoForge.runtime(),
                        node -> NativePermissions.has(source, node), source.getEntity() instanceof ServerPlayer,
                        builder.getRemaining(), clock, KansokushaNeoForge.searchTimeZone())
                        .thenApply(result -> {
                            var target = builder.createOffset(builder.getStart() + result.replacementStart());
                            result.values().forEach(target::suggest);
                            return target.build();
                        });
                }));
    }

    private static int execute(CommandSourceStack source, String name, String args) {
        var runtime = KansokushaNeoForge.runtime();
        if (runtime == null) return 0;
        return switch (name) {
            case "search" -> args.isBlank() ? guide(source) : SearchCommandSupport.execute(runtime, new SearchCommandSupport.SearchSource(
                node -> NativePermissions.has(source, node), send(source), source.getEntity() instanceof ServerPlayer,
                source.getEntity() instanceof ServerPlayer player ? new SearchQuery.Position(
                    net.kyori.adventure.key.Key.key(player.level().dimension().identifier().toString()),
                    player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()) : null,
                command -> SearchNavigation.remember(navigationKey(source), command)),
                args, Clock.systemUTC(), KansokushaNeoForge.searchTimeZone(),
                renderer(source)) ? 1 : 0;
            case "event" -> event(runtime, source, args);
            case "teleport" -> NativeTeleport.execute(source, args);
            default -> 0;
        };
    }

    private static int guide(CommandSourceStack source) {
        SearchGuide.send(node -> NativePermissions.has(source, node), send(source), source.getEntity() instanceof ServerPlayer);
        return 1;
    }

    private static int event(KansokushaRuntime runtime, CommandSourceStack source, String args) {
        if (args.isBlank()) {
            send(source).accept(CommandMessages.HELP_EVENT.asComponent());
            return 0;
        }
        var parts = args.strip().split("\\s+", 3);
        var view = parts.length > 1 ? parts[1] : "";
        int page = 1;
        if (parts.length > 2) {
            try {
                page = Integer.parseInt(parts[2]);
            } catch (NumberFormatException invalid) {
                send(source).accept(EventPresentationMessages.INVALID_VIEW.asComponent());
                return 0;
            }
        }
        var renderer = renderer(source)
            .withReturnCommand(SearchNavigation.last(navigationKey(source)));
        return EventCommandSupport.execute(runtime, parts[0], node -> NativePermissions.has(source, node),
            send(source), renderer, view, page) ? 1 : 0;
    }

    private static Object navigationKey(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "neoforge-console";
    }

    private static EventRenderer renderer(CommandSourceStack source) {
        return NativeEventPresenter.renderer(Clock.systemUTC(), KansokushaNeoForge.searchTimeZone())
            .withLocations((location, showWorld) -> NativeTeleport.format(location, showWorld,
                source.getEntity() instanceof ServerPlayer
                    && NativePermissions.has(source, "kansokusha.command.teleport")));
    }

    private static int health(CommandSourceStack source, boolean stored) {
        KansokushaRuntime runtime = KansokushaNeoForge.runtime();
        if (runtime == null) return 0;
        if (stored) HealthCommandSupport.executeStored(runtime, send(source));
        else HealthCommandSupport.execute(runtime, send(source));
        return 1;
    }

    private static int help(CommandSourceStack source) {
        CommandHelp.send(node -> NativePermissions.has(source, node), send(source));
        if (NativePermissions.has(source, "kansokusha.command.inspect")) {
            send(source).accept(NativeInspection.help());
        }
        return 1;
    }

    static Consumer<Component> send(CommandSourceStack source) {
        return message -> source.getServer().execute(() -> {
            var locale = source.getEntity() instanceof ServerPlayer player
                && player.clientInformation().language().startsWith("ja") ? Locale.JAPANESE : Locale.ENGLISH;
            var json = GsonComponentSerializer.gson().serializeToTree(GlobalTranslator.render(message, locale));
            var nativeMessage = ComponentSerialization.CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, source.getServer().registryAccess()), json).getOrThrow();
            source.sendSystemMessage(nativeMessage);
        });
    }

    private NativeCommands() { }
}
