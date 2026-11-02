package net.okocraft.kansokusha.testmod;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** A server player whose real click handler runs without writing network packets. */
final class PacketProbePlayer extends ServerPlayer {
    private final java.util.List<Component> systemMessages = new java.util.ArrayList<>();

    PacketProbePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        var network = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, ChannelFutureListener listener) { }
            @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { }
        };
        new EmbeddedChannel(network);
        connection = new ServerGamePacketListenerImpl(level.getServer(), network, this,
            CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, ChannelFutureListener listener) { }
        };
    }

    @Override public void sendSystemMessage(Component message) { systemMessages.add(message); }

    java.util.List<Component> systemMessages() { return systemMessages; }
}
