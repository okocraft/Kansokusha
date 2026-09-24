package net.okocraft.kansokusha.paper.testsupport;

import io.papermc.paper.command.brigadier.PaperCommands;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlags;
import org.bukkit.craftbukkit.CraftRegistry;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * Initializes the Paper/Minecraft registries required by real item stacks in tests.
 * No world, network, or Bukkit server is started.
 */
public final class TestServer {

    private static boolean setUp;

    public static synchronized void setUp() {
        if (setUp) {
            return;
        }

        LoggerFactory.getLogger(TestServer.class).info("Setting the test server up.");

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        RegistryAccess.Frozen registries = loadRegistries();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
            .forEach(DataComponentInitializers.PendingComponents::apply);

        CraftRegistry.setMinecraftRegistry(registries);
        setUpArgumentTypes(registries);

        setUp = true;
    }

    private static RegistryAccess.Frozen loadRegistries() {
        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        packs.reload();
        packs.setSelected(packs.getAvailableIds(), false);

        ResourceManager resources = new MultiPackResourceManager(
            PackType.SERVER_DATA,
            packs.openAllSelected()
        );

        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> tags = TagLoader.loadTagsForExistingRegistries(
            resources,
            layers.getLayer(RegistryLayer.STATIC)
        );

        List<HolderLookup.RegistryLookup<?>> worldLookups = TagLoader.buildUpdatedLookups(
            layers.getAccessForLoading(RegistryLayer.WORLD),
            tags
        );
        RegistryAccess.Frozen worldRegistries = RegistryDataLoader.load(
            resources,
            worldLookups,
            RegistryDataLoader.WORLD_REGISTRIES,
            Runnable::run
        ).join();
        layers = layers.replaceFrom(RegistryLayer.WORLD, worldRegistries);

        List<HolderLookup.RegistryLookup<?>> dimensionLookups = Stream.concat(
            worldLookups.stream(),
            worldRegistries.listRegistries()
        ).toList();
        RegistryAccess.Frozen dimensionRegistries = RegistryDataLoader.load(
            resources,
            dimensionLookups,
            RegistryDataLoader.DIMENSION_REGISTRIES,
            Runnable::run
        ).join();
        layers = layers.replaceFrom(RegistryLayer.DIMENSIONS, dimensionRegistries);

        tags.forEach(Registry.PendingTags::apply);
        return layers.compositeAccess().freeze();
    }

    private static void setUpArgumentTypes(RegistryAccess.Frozen registries) {
        CommandBuildContext context = CommandBuildContext.simple(
            registries,
            FeatureFlags.REGISTRY.allFlags()
        );
        PaperCommands.INSTANCE.setDispatcher(
            new Commands(Commands.CommandSelection.ALL, context),
            context
        );
    }

    private TestServer() {
        throw new UnsupportedOperationException();
    }
}
