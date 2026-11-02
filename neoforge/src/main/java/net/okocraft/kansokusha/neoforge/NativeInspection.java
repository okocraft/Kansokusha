package net.okocraft.kansokusha.neoforge;

import dev.siroshun.mcmsgdef.DefaultMessageDefiner;
import dev.siroshun.mcmsgdef.MessageKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.okocraft.kansokusha.common.command.EventCommandSupport;
import net.okocraft.kansokusha.common.command.SearchCommandMessages;
import net.okocraft.kansokusha.common.command.SearchCommandSupport;
import net.okocraft.kansokusha.common.command.SearchNavigation;
import net.okocraft.kansokusha.common.search.SearchPage;
import net.okocraft.kansokusha.common.search.SearchRequest;
import net.okocraft.kansokusha.common.search.query.SearchQuery;

import java.time.Clock;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

final class NativeInspection {
    private static final String PERMISSION = "kansokusha.command.inspect";
    private static final DefaultMessageDefiner MESSAGES = DefaultMessageDefiner.create();
    private static final MessageKey ENABLED_MESSAGE = MESSAGES.define("kansokusha.command.inspect.enabled",
        "<gray>Inspection mode <green>enabled</green>. Left-click a block to view its history; right-click an interactable block to inspect it, or another block to inspect beyond its clicked face.<newline>Run <aqua>/kansokusha inspect off</aqua> to resume normal block interactions.</gray>");
    private static final MessageKey DISABLED_MESSAGE = MESSAGES.define("kansokusha.command.inspect.disabled",
        "<gray>Inspection mode <red>disabled</red>. Normal block interactions are restored.</gray>");
    private static final MessageKey PLAYER_ONLY = MESSAGES.define("kansokusha.command.inspect.player-only",
        "<red>This command requires a player. Run it in-game.</red>");
    private static final MessageKey HELP = MESSAGES.define("kansokusha.command.help.inspect",
        "<aqua>/kansokusha inspect [on/off]</aqua><dark_gray> - </dark_gray><gray>Inspect history at a block: left-click the block; right-click interactable blocks themselves or the adjacent position of other blocks. Use off to finish.</gray>");
    private static final MessageKey.Arg1<String> HISTORY = MESSAGES.define("kansokusha.inspect.history",
        "<gold>History at <aqua><position></aqua></gold>").with(value -> Argument.string("position", value));
    private static final MessageKey.Arg1<String> CONTAINER_HISTORY = MESSAGES.define("kansokusha.inspect.container-history",
        "<gold>Container contents history at <aqua><position></aqua></gold>").with(value -> Argument.string("position", value));
    private static final MessageKey.Arg1<String> NO_HISTORY = MESSAGES.define("kansokusha.inspect.no-history",
        "<gray>No visible history was found at <aqua><position></aqua>.</gray>").with(value -> Argument.string("position", value));
    private static final MessageKey VIEW_FULL = MESSAGES.define("kansokusha.inspect.view-full",
        "<gold>[View full history]</gold>");
    private static final Set<UUID> ENABLED = new HashSet<>();
    private static final Set<Key> CONTAINER_ACTIONS = Set.of(
        Key.key("kansokusha:container_transaction"), Key.key("kansokusha:container_pickup"),
        Key.key("kansokusha:container_transfer"));

    static void register() {
        NeoForge.EVENT_BUS.register(new Interactions());
    }

    static Map<String, String> defaultMessages() { return MESSAGES.getCollectedMessages(); }
    static Component help() { return HELP.asComponent(); }

    private static int playerOnly(CommandSourceStack source) {
        NativeCommands.send(source).accept(PLAYER_ONLY.asComponent());
        return 0;
    }

    static int toggle(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return playerOnly(source);
        return set(source, !ENABLED.contains(player.getUUID()));
    }

    static int set(CommandSourceStack source, boolean enabled) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return playerOnly(source);
        if (!NativePermissions.has(source, PERMISSION)) return 0;
        if (enabled) ENABLED.add(player.getUUID());
        else ENABLED.remove(player.getUUID());
        NativeCommands.send(source).accept((enabled ? ENABLED_MESSAGE : DISABLED_MESSAGE).asComponent());
        return 1;
    }

    static int page(CommandSourceStack source, String raw) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return playerOnly(source);
        if (!NativePermissions.has(source, PERMISSION)) return 0;
        try {
            var parts = raw.split(" ");
            if (parts.length != 5 && parts.length != 6 && parts.length != 9) throw new IllegalArgumentException();
            var target = new SearchQuery.Position(Key.key(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            Optional<SearchRequest.Cursor> cursor = parts[4].equals("first") ? Optional.empty()
                : Optional.of(SearchCommandSupport.parseCursor(parts[4]));
            var container = parts.length > 5;
            if (container && !parts[5].equals("container")) throw new IllegalArgumentException();
            Set<SearchQuery.Position> positions = new HashSet<>();
            positions.add(target);
            if (parts.length == 9) {
                var other = new SearchQuery.Position(target.world(), Integer.parseInt(parts[6]),
                    Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
                if (other.y() != target.y() || Math.abs((long) other.x() - target.x())
                    + Math.abs((long) other.z() - target.z()) != 1) throw new IllegalArgumentException();
                positions.add(other);
            }
            search(player, target, cursor, container, Set.copyOf(positions));
            return 1;
        } catch (IllegalArgumentException invalid) {
            NativeCommands.send(source).accept(SearchCommandMessages.PARSE_ERROR.apply(
                net.okocraft.kansokusha.common.search.query.SearchQueryMessages.INVALID_CURSOR.asComponent()));
            return 0;
        }
    }

    private static boolean enabled(ServerPlayer player) {
        if (!ENABLED.contains(player.getUUID())) return false;
        if (NativePermissions.has(player.createCommandSourceStack(), PERMISSION)) return true;
        ENABLED.remove(player.getUUID());
        return false;
    }

    private static SearchQuery.Position position(ServerPlayer player, BlockPos pos) {
        return new SearchQuery.Position(Key.key(player.level().dimension().identifier().toString()),
            pos.getX(), pos.getY(), pos.getZ());
    }

    private static void inspect(ServerPlayer player, BlockPos pos, boolean container) {
        var target = position(player, pos);
        Set<SearchQuery.Position> positions = Set.of(target);
        if (container && player.level().getBlockState(pos).getBlock() instanceof ChestBlock) {
            var state = player.level().getBlockState(pos);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                positions = Set.of(target, position(player, ChestBlock.getConnectedBlockPos(pos, state)));
            }
        }
        var first = positions.stream().min(Comparator.comparingInt(SearchQuery.Position::x)
            .thenComparingInt(SearchQuery.Position::z)).orElseThrow();
        search(player, first, Optional.empty(), container, positions);
    }

    private static void search(ServerPlayer player, SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor,
                               boolean container, Set<SearchQuery.Position> positions) {
        var runtime = KansokushaNeoForge.runtime();
        if (runtime == null) return;
        var source = player.createCommandSourceStack();
        if (!NativePermissions.has(source, PERMISSION)) return;
        var query = new SearchQuery(new SearchQuery.Conditions(Set.of(), container ? CONTAINER_ACTIONS : Set.of(),
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), positions, Set.of()),
            SearchQuery.Conditions.empty(), SearchQuery.Order.NEWEST, OptionalInt.empty());
        runtime.searchMetadata().thenCompose(metadata -> runtime.search(new SearchRequest(query,
            new SearchRequest.Constraints(SearchCommandSupport.allowedEventTypes(
                node -> NativePermissions.has(source, node), metadata)), cursor, SearchCommandSupport.PLAYER_DEFAULT_LIMIT)))
            .whenComplete((page, failure) -> player.level().getServer().execute(() -> {
                var current = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
                if (current == null || !NativePermissions.has(current.createCommandSourceStack(), PERMISSION)) return;
                if (failure != null) {
                    NativeCommands.send(current.createCommandSourceStack()).accept(SearchCommandMessages.SEARCH_FAILED.asComponent());
                    return;
                }
                show(current, target, cursor, container, positions, page);
            }));
    }

    private static void show(ServerPlayer player, SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor,
                             boolean container, Set<SearchQuery.Position> positions, SearchPage page) {
        var source = player.createCommandSourceStack();
        var send = NativeCommands.send(source);
        SearchNavigation.remember(player.getUUID(), pageCommand(target, cursor, container, positions));
        if (page.events().stream().noneMatch(event -> NativePermissions.has(source,
            SearchCommandSupport.eventPermission(event.eventType())))) {
            send.accept(NO_HISTORY.apply(coordinates(target)));
            return;
        }
        send.accept((container ? CONTAINER_HISTORY : HISTORY).apply(coordinates(target)));
        var renderer = NativeEventPresenter.renderer(Clock.systemUTC(), KansokushaNeoForge.searchTimeZone())
            .withLocations((location, showWorld) -> NativeTeleport.format(location, showWorld,
                NativePermissions.has(source, "kansokusha.command.teleport")))
            .withDebug(NativePermissions.has(source, EventCommandSupport.DEBUG_PERMISSION));
        boolean details = NativePermissions.has(source, EventCommandSupport.PERMISSION);
        for (var event : page.events()) {
            if (NativePermissions.has(source, SearchCommandSupport.eventPermission(event.eventType())))
                send.accept(SearchCommandMessages.RESULT.apply(renderer.row(event, details, false)));
        }
        Component navigation = Component.empty();
        if (page.previousCursor().isPresent()) navigation = navigation.append(SearchCommandMessages.PREVIOUS.asComponent()
            .clickEvent(ClickEvent.runCommand(pageCommand(target, page.previousCursor(), container, positions))));
        if (page.nextCursor().isPresent()) navigation = navigation.append(Component.space())
            .append(SearchCommandMessages.NEXT.asComponent().clickEvent(ClickEvent.runCommand(
                pageCommand(target, page.nextCursor(), container, positions))));
        if (!navigation.equals(Component.empty())) send.accept(navigation);
        if (page.nextCursor().isPresent() && NativePermissions.has(source, SearchCommandSupport.PERMISSION)) {
            var full = positions.stream().sorted(Comparator.comparingInt(SearchQuery.Position::x)
                .thenComparingInt(SearchQuery.Position::z))
                .map(NativeInspection::coordinates).map(value -> "position " + value)
                .collect(java.util.stream.Collectors.joining(" "));
            send.accept(VIEW_FULL.asComponent().clickEvent(ClickEvent.runCommand(
                "/kansokusha search " + full + (container
                    ? " action container_transaction action container_pickup action container_transfer" : ""))));
        }
    }

    private static String coordinates(SearchQuery.Position position) {
        return position.world().asString() + " " + position.x() + " " + position.y() + " " + position.z();
    }

    private static String pageCommand(SearchQuery.Position target, Optional<SearchRequest.Cursor> cursor,
                                      boolean container, Set<SearchQuery.Position> positions) {
        var other = container ? positions.stream().filter(position -> !position.equals(target))
            .map(position -> " " + position.x() + " " + position.y() + " " + position.z())
            .findFirst().orElse("") : "";
        return "/kansokusha inspect page " + coordinates(target) + " "
            + cursor.map(SearchCommandSupport::serializeCursor).orElse("first")
            + (container ? " container" + other : "");
    }

    static void clear() { ENABLED.clear(); }

    static final class Interactions {
        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void rightClick(PlayerInteractEvent.RightClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !enabled(player)) return;
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            var pos = event.getPos();
            var state = player.level().getBlockState(pos);
            var blockEntity = player.level().getBlockEntity(pos);
            boolean container = blockEntity instanceof net.minecraft.world.Container
                || blockEntity != null && state.getMenuProvider(player.level(), pos) != null;
            boolean interactive = container || state.getBlock() instanceof SignBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.NoteBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.JukeboxBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CakeBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BellBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.ComposterBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                || state.is(net.minecraft.tags.BlockTags.DOORS) || state.is(net.minecraft.tags.BlockTags.TRAPDOORS)
                || state.is(net.minecraft.tags.BlockTags.BUTTONS) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES);
            inspect(player, interactive ? pos : pos.relative(event.getFace()), container);
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void leftClick(PlayerInteractEvent.LeftClickBlock event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !enabled(player)) return;
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            event.setCanceled(true);
            if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) inspect(player, event.getPos(), false);
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
        public void breakBlock(BreakBlockEvent event) {
            if (event.getPlayer() instanceof ServerPlayer player && enabled(player)) event.setCanceled(true);
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            ENABLED.remove(event.getEntity().getUUID());
            SearchNavigation.forget(event.getEntity().getUUID());
        }
    }

    private NativeInspection() { }
}
