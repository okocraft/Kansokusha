package net.okocraft.kansokusha.velocity.builtin;

import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

class VelocityBackendRegistryChangePayloadCodecTest {

    @Test
    void testUnresolvedServerInfoRoundTripsWithoutResolution() throws Exception {
        var info = new ServerInfo(
            "Survival-1",
            InetSocketAddress.createUnresolved("backend.internal", 25566)
        );
        var key = Key.key("kansokusha", "velocity-server/survival-1");

        var decoded = VelocityBackendRegistryChangePayloadCodec.decode(
            VelocityBackendRegistryChangePayloadCodec.encode(
                VelocityBackendRegistryChangePayloadCodec.Action.REGISTER,
                key,
                info
            )
        );

        Assertions.assertEquals(
            VelocityBackendRegistryChangePayloadCodec.Action.REGISTER,
            decoded.action()
        );
        Assertions.assertEquals(key, decoded.serverKey());
        Assertions.assertEquals("Survival-1", decoded.serverInfo().getName());
        Assertions.assertEquals("backend.internal", decoded.serverInfo().getAddress().getHostString());
        Assertions.assertEquals(25566, decoded.serverInfo().getAddress().getPort());
        Assertions.assertTrue(decoded.serverInfo().getAddress().isUnresolved());
    }

    @Test
    void testResolvedServerInfoPreservesResolvedAddressBytesAndHost() throws Exception {
        var resolved = InetAddress.getByAddress(
            "backend.example",
            new byte[] {127, 0, 0, 42}
        );
        var info = new ServerInfo("game", new InetSocketAddress(resolved, 25567));
        var key = Key.key("kansokusha", "velocity-server/game");

        var decoded = VelocityBackendRegistryChangePayloadCodec.decode(
            VelocityBackendRegistryChangePayloadCodec.encode(
                VelocityBackendRegistryChangePayloadCodec.Action.UNREGISTER,
                key,
                info
            )
        );

        Assertions.assertEquals(
            VelocityBackendRegistryChangePayloadCodec.Action.UNREGISTER,
            decoded.action()
        );
        Assertions.assertEquals(key, decoded.serverKey());
        Assertions.assertEquals(info.getName(), decoded.serverInfo().getName());
        Assertions.assertEquals(
            info.getAddress().getHostString(),
            decoded.serverInfo().getAddress().getHostString()
        );
        Assertions.assertArrayEquals(
            info.getAddress().getAddress().getAddress(),
            decoded.serverInfo().getAddress().getAddress().getAddress()
        );
        Assertions.assertEquals(info.getAddress().getPort(), decoded.serverInfo().getAddress().getPort());
        Assertions.assertFalse(decoded.serverInfo().getAddress().isUnresolved());
    }
}
