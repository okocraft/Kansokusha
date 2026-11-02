package net.okocraft.kansokusha.testmod;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runs UI and permission contracts through the registered native command and event paths. */
public final class NativeUiFixture {
    private final MinecraftServer server;
    private final List<Component> messages = new CopyOnWriteArrayList<>();
    private PacketProbePlayer actor;
    private int ticks;

    public static void verify(MinecraftServer server) {
        new NativeUiFixture(server).start();
    }

    private NativeUiFixture(MinecraftServer server) { this.server = server; }

    private void start() {
        var root = server.getCommands().getDispatcher().getRoot().getChild("kansokusha");
        if (root == null) throw new AssertionError("Native command was not registered");
        var console = server.createCommandSourceStack().withSource(new Output());
        if (root.canUse(console.withPermission(PermissionSet.NO_PERMISSIONS)))
            throw new AssertionError("A low-permission non-player can read Kansokusha");
        var admin = console.withPermission(PermissionSet.ALL_PERMISSIONS);
        if (!root.canUse(admin)) throw new AssertionError("Admin console cannot use Kansokusha");
        server.getCommands().performPrefixedCommand(admin, "kansokusha search");

        var profile = new GameProfile(UUID.fromString("fc8b1778-24c8-432b-a961-d98582565565"), "UiProbe");
        actor = new PacketProbePlayer(server.overworld(), profile);
        var identity = new NameAndId(profile);
        server.getPlayerList().op(identity);
        try {
            var source = actor.createCommandSourceStack().withSource(new Output());
            if (!server.getPlayerList().isOp(identity) || !root.canUse(source))
                throw new AssertionError("Operator fixture lacks ordinary command permission");
            server.getCommands().performPrefixedCommand(source, "kansokusha inspect on");
            var pos = new BlockPos(225, 80, 225);
            var click = new PlayerInteractEvent.RightClickBlock(actor, InteractionHand.OFF_HAND, pos,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            NeoForge.EVENT_BUS.post(click);
            if (!click.isCanceled() || click.getUseBlock() != TriState.FALSE || click.getUseItem() != TriState.FALSE)
                throw new AssertionError("Inspection failed to suppress offhand block and item use");
            var breaking = new BreakBlockEvent(server.overworld(), pos, Blocks.STONE.defaultBlockState(), actor);
            NeoForge.EVENT_BUS.post(breaking);
            if (!breaking.isCanceled()) throw new AssertionError("Inspection allowed block break");
            server.getCommands().performPrefixedCommand(source,
                "kansokusha event 00000000-0000-4000-8000-000000000000 raw");
        } finally {
            server.getPlayerList().deop(identity);
        }
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (++ticks == 260) {
            var admin = server.createCommandSourceStack().withSource(new Output())
                .withPermission(PermissionSet.ALL_PERMISSIONS);
            server.getCommands().performPrefixedCommand(admin,
                "kansokusha search position minecraft:overworld 208 80 208");
        }
        if (ticks != 280) return;
        try {
            if (actor.systemMessages().stream().noneMatch(message -> message.getString().contains("Inspection mode enabled")))
                throw new AssertionError("Inspection command did not acknowledge activation");
            if (actor.systemMessages().stream().noneMatch(message -> message.getString().contains("do not have permission")))
                throw new AssertionError("Operator debug view was not denied: "
                    + actor.systemMessages().stream().map(Component::getString).toList());
            if (messages.stream().noneMatch(message -> hasClick(message, net.minecraft.network.chat.ClickEvent.SuggestCommand.class)))
                throw new AssertionError("Search guide lost its clickable suggestions");
            if (messages.stream().noneMatch(message -> hasClick(message, net.minecraft.network.chat.ClickEvent.RunCommand.class)
                && hasHover(message))) throw new AssertionError("Search result lost click or hover detail");
            if (messages.stream().noneMatch(message -> hasTranslation(message, "block.minecraft.stone")))
                throw new AssertionError("Stored block target was not sent as a client-translatable name");
            Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result") + ".ui"), "success");
        } catch (Throwable failure) {
            try { Files.writeString(Path.of(System.getProperty("kansokusha.fixture.result") + ".ui"), failure.toString()); }
            catch (Exception ignored) { }
            throw new AssertionError("Kansokusha UI fixture failed", failure);
        } finally {
            NeoForge.EVENT_BUS.unregister(this);
        }
    }

    private static boolean hasClick(Component message, Class<?> kind) {
        if (kind.isInstance(message.getStyle().getClickEvent())) return true;
        return message.getSiblings().stream().anyMatch(child -> hasClick(child, kind));
    }

    private static boolean hasHover(Component message) {
        if (message.getStyle().getHoverEvent() != null) return true;
        return message.getSiblings().stream().anyMatch(NativeUiFixture::hasHover);
    }

    private static boolean hasTranslation(Component message, String key) {
        if (message.getContents() instanceof TranslatableContents translation && translation.getKey().equals(key)) return true;
        return message.getSiblings().stream().anyMatch(child -> hasTranslation(child, key));
    }

    private final class Output implements CommandSource {
        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
