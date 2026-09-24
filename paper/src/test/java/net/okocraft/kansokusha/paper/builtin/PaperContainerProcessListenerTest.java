package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import net.okocraft.kansokusha.api.event.EventSubmission;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

class PaperContainerProcessListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testFourContainerProcessesNormalizeAndSnapshotBoundaries() throws Exception {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();

        var furnaceBlock = block(world, 10, Material.FURNACE);
        var furnaceInventory = Mockito.mock(FurnaceInventory.class);
        configureInventory(furnaceInventory, InventoryType.FURNACE, 3, furnaceBlock);
        attachInventory(furnaceBlock, furnaceInventory, Material.FURNACE);
        var furnaceSource = ItemStack.of(Material.RAW_IRON, 2);
        var furnaceFuel = ItemStack.of(Material.COAL, 1);
        Mockito.when(furnaceInventory.getFuel()).thenReturn(furnaceFuel);
        var furnaceResult = new ItemStack[]{ItemStack.of(Material.IRON_INGOT, 1)};
        var furnace = Mockito.mock(FurnaceSmeltEvent.class);
        Mockito.when(furnace.getBlock()).thenReturn(furnaceBlock);
        Mockito.when(furnace.getSource()).thenReturn(furnaceSource);
        Mockito.when(furnace.getResult()).thenAnswer(ignored -> furnaceResult[0]);
        Mockito.when(furnace.isCancelled()).thenReturn(false);

        listener.captureFurnace(furnace);
        listener.captureCook(furnace);
        furnaceSource.setAmount(7);
        furnaceFuel.setAmount(4);
        furnaceResult[0] = ItemStack.of(Material.GOLD_INGOT, 1);
        listener.finalizeFurnace(furnace);
        listener.finalizeCook(furnace);

        var brewBlock = block(world, 20, Material.BREWING_STAND);
        var brewer = Mockito.mock(BrewerInventory.class);
        configureInventory(brewer, InventoryType.BREWING, 5, brewBlock);
        var potion = ItemStack.of(Material.POTION, 1);
        var ingredient = ItemStack.of(Material.NETHER_WART, 1);
        var brewFuel = ItemStack.of(Material.BLAZE_POWDER, 1);
        Mockito.when(brewer.getItem(0)).thenReturn(potion);
        Mockito.when(brewer.getItem(1)).thenReturn(null);
        Mockito.when(brewer.getItem(2)).thenReturn(null);
        Mockito.when(brewer.getIngredient()).thenReturn(ingredient);
        Mockito.when(brewer.getFuel()).thenReturn(brewFuel);
        var brewResults = new ArrayList<>(
            List.of(
                ItemStack.of(Material.POTION, 1),
                ItemStack.empty(),
                ItemStack.empty()
            )
        );
        var brew = Mockito.mock(BrewEvent.class);
        Mockito.when(brew.getBlock()).thenReturn(brewBlock);
        Mockito.when(brew.getContents()).thenReturn(brewer);
        Mockito.when(brew.getResults()).thenReturn(brewResults);
        Mockito.when(brew.isCancelled()).thenReturn(false);

        listener.captureBrew(brew);
        potion.setAmount(2);
        ingredient.setAmount(2);
        brewResults.set(0, ItemStack.of(Material.SPLASH_POTION, 1));
        listener.finalizeBrew(brew);

        var campfireBlock = block(world, 30, Material.CAMPFIRE);
        Mockito.when(campfireBlock.getState()).thenReturn(Mockito.mock(BlockState.class));
        var campfireSource = ItemStack.of(Material.COD, 1);
        var campfireResult = new ItemStack[]{ItemStack.of(Material.COOKED_COD, 1)};
        var cook = Mockito.mock(BlockCookEvent.class);
        Mockito.when(cook.getBlock()).thenReturn(campfireBlock);
        Mockito.when(cook.getSource()).thenReturn(campfireSource);
        Mockito.when(cook.getResult()).thenAnswer(ignored -> campfireResult[0]);
        Mockito.when(cook.isCancelled()).thenReturn(false);

        listener.captureCook(cook);
        campfireSource.setAmount(2);
        campfireResult[0] = ItemStack.of(Material.COOKED_SALMON, 1);
        listener.finalizeCook(cook);

        var crafterBlock = block(world, 40, Material.CRAFTER);
        var crafterInventory = Mockito.mock(Inventory.class);
        configureInventory(crafterInventory, InventoryType.CHEST, 9, crafterBlock);
        attachInventory(crafterBlock, crafterInventory, Material.CRAFTER);
        var crafterInput = ItemStack.of(Material.IRON_INGOT, 3);
        Mockito.when(crafterInventory.getContents()).thenReturn(
            new ItemStack[]{
                crafterInput, null, null, null, null, null, null, null, null
            }
        );
        var crafterResult = new ItemStack[]{ItemStack.of(Material.IRON_BLOCK, 1)};
        var crafter = Mockito.mock(CrafterCraftEvent.class);
        Mockito.when(crafter.getBlock()).thenReturn(crafterBlock);
        Mockito.when(crafter.getResult()).thenAnswer(ignored -> crafterResult[0]);
        Mockito.when(crafter.isCancelled()).thenReturn(false);

        listener.captureCrafter(crafter);
        crafterInput.setAmount(1);
        crafterResult[0] = ItemStack.of(Material.ANVIL, 1);
        listener.finalizeCrafter(crafter);

        Assertions.assertEquals(4, api.submissions.size());
        var byX = submissionsByX(api);
        assertProcess(byX.get(10), "furnace_smelt", Material.RAW_IRON, 2, Material.GOLD_INGOT);
        var furnacePayload = PaperPayloadNbtCodec.decode(byX.get(10).payload());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                furnacePayload.getCompoundOrEmpty("fuel")
            ).getAmount()
        );

        assertProcess(byX.get(20), "brew", Material.POTION, 1, Material.SPLASH_POTION);
        var brewPayload = PaperPayloadNbtCodec.decode(byX.get(20).payload());
        Assertions.assertEquals(
            1,
            PaperItemStackPayloadCodec.decode(
                brewPayload.getCompoundOrEmpty("ingredient")
            ).getAmount()
        );

        assertProcess(byX.get(30), "campfire_cook", Material.COD, 1, Material.COOKED_SALMON);
        assertProcess(byX.get(40), "crafter_craft", Material.IRON_INGOT, 3, Material.ANVIL);
        Assertions.assertEquals(0, listener.inFlightCount());
    }

    @Test
    void testCancelledProcessesDoNotSubmitAndPlayerCraftingIsNotHandled() {
        var api = new PaperBlockEventTestSupport.RecordingApi();
        var listener = listener(api);
        var world = PaperBlockEventTestSupport.world();

        var furnaceBlock = block(world, 1, Material.FURNACE);
        var furnace = Mockito.mock(FurnaceSmeltEvent.class);
        Mockito.when(furnace.getBlock()).thenReturn(furnaceBlock);
        Mockito.when(furnaceBlock.getState()).thenReturn(Mockito.mock(BlockState.class));
        Mockito.when(furnace.getSource()).thenReturn(ItemStack.of(Material.RAW_IRON, 1));
        Mockito.when(furnace.getResult()).thenReturn(ItemStack.of(Material.IRON_INGOT, 1));
        Mockito.when(furnace.isCancelled()).thenReturn(true);
        listener.captureFurnace(furnace);
        listener.finalizeFurnace(furnace);

        var brewBlock = block(world, 2, Material.BREWING_STAND);
        var brewer = Mockito.mock(BrewerInventory.class);
        configureInventory(brewer, InventoryType.BREWING, 5, brewBlock);
        Mockito.when(brewer.getItem(0)).thenReturn(ItemStack.of(Material.POTION, 1));
        var brew = Mockito.mock(BrewEvent.class);
        Mockito.when(brew.getBlock()).thenReturn(brewBlock);
        Mockito.when(brew.getContents()).thenReturn(brewer);
        Mockito.when(brew.getResults()).thenReturn(
            new ArrayList<>(List.of(ItemStack.of(Material.POTION, 1)))
        );
        Mockito.when(brew.isCancelled()).thenReturn(true);
        listener.captureBrew(brew);
        listener.finalizeBrew(brew);

        var campfireBlock = block(world, 3, Material.CAMPFIRE);
        Mockito.when(campfireBlock.getState()).thenReturn(Mockito.mock(BlockState.class));
        var cook = Mockito.mock(BlockCookEvent.class);
        Mockito.when(cook.getBlock()).thenReturn(campfireBlock);
        Mockito.when(cook.getSource()).thenReturn(ItemStack.of(Material.COD, 1));
        Mockito.when(cook.getResult()).thenReturn(ItemStack.of(Material.COOKED_COD, 1));
        Mockito.when(cook.isCancelled()).thenReturn(true);
        listener.captureCook(cook);
        listener.finalizeCook(cook);

        var crafterBlock = block(world, 4, Material.CRAFTER);
        var crafterInventory = Mockito.mock(Inventory.class);
        configureInventory(crafterInventory, InventoryType.CHEST, 9, crafterBlock);
        attachInventory(crafterBlock, crafterInventory, Material.CRAFTER);
        Mockito.when(crafterInventory.getContents()).thenReturn(new ItemStack[9]);
        var crafter = Mockito.mock(CrafterCraftEvent.class);
        Mockito.when(crafter.getBlock()).thenReturn(crafterBlock);
        Mockito.when(crafter.getResult()).thenReturn(ItemStack.of(Material.IRON_BLOCK, 1));
        Mockito.when(crafter.isCancelled()).thenReturn(true);
        listener.captureCrafter(crafter);
        listener.finalizeCrafter(crafter);

        Assertions.assertTrue(api.submissions.isEmpty());
        Assertions.assertEquals(0, listener.inFlightCount());

        var handledTypes = Arrays.stream(PaperContainerProcessListener.class.getDeclaredMethods())
            .filter(method -> method.getAnnotation(EventHandler.class) != null)
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .toList();
        Assertions.assertFalse(handledTypes.contains(CraftItemEvent.class));
        Assertions.assertTrue(handledTypes.contains(FurnaceSmeltEvent.class));
        Assertions.assertTrue(handledTypes.contains(BrewEvent.class));
        Assertions.assertTrue(handledTypes.contains(BlockCookEvent.class));
        Assertions.assertTrue(handledTypes.contains(CrafterCraftEvent.class));
    }

    private static PaperContainerProcessListener listener(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        return PaperContainerProcessListener.register(
            api,
            PaperBlockEventTestSupport.SERVER_KEY,
            Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );
    }

    private static Block block(World world, int x, Material type) {
        var block = Mockito.mock(Block.class);
        Mockito.when(block.getWorld()).thenReturn(world);
        Mockito.when(block.getX()).thenReturn(x);
        Mockito.when(block.getY()).thenReturn(70);
        Mockito.when(block.getZ()).thenReturn(-x);
        Mockito.when(block.getType()).thenReturn(type);
        return block;
    }

    private static void configureInventory(
        Inventory inventory,
        InventoryType type,
        int size,
        Block block
    ) {
        Mockito.when(inventory.getType()).thenReturn(type);
        Mockito.when(inventory.getSize()).thenReturn(size);
        Mockito.when(inventory.getHolder()).thenReturn(null);
        Mockito.when(inventory.getLocation()).thenReturn(
            new Location(
                block.getWorld(),
                block.getX() + 0.5,
                block.getY(),
                block.getZ() + 0.5
            )
        );
    }

    private static void attachInventory(Block block, Inventory inventory, Material type) {
        var state = Mockito.mock(
            BlockState.class,
            Mockito.withSettings().extraInterfaces(InventoryHolder.class)
        );
        Mockito.when(state.getType()).thenReturn(type);
        Mockito.when(((InventoryHolder) state).getInventory()).thenReturn(inventory);
        Mockito.when(block.getState()).thenReturn(state);
    }

    private static HashMap<Integer, EventSubmission> submissionsByX(
        PaperBlockEventTestSupport.RecordingApi api
    ) {
        var result = new HashMap<Integer, EventSubmission>();
        for (var submission : api.submissions) {
            Assertions.assertNull(result.put(submission.position().x(), submission));
            Assertions.assertEquals(PaperContainerProcessListener.EVENT_TYPE, submission.eventType());
            Assertions.assertEquals(OCCURRED_AT, submission.occurredAt());
            Assertions.assertNull(submission.subject());
        }
        return result;
    }

    private static void assertProcess(
        EventSubmission submission,
        String kind,
        Material initialInputType,
        int initialInputAmount,
        Material finalResultType
    ) throws Exception {
        Assertions.assertNotNull(submission);
        var payload = PaperPayloadNbtCodec.decode(submission.payload());
        Assertions.assertEquals(kind, string(payload, "process_kind"));
        var inputs = payload.getListOrEmpty("input_items");
        var input = PaperItemStackPayloadCodec.decode((CompoundTag) inputs.get(0));
        Assertions.assertEquals(initialInputType, input.getType());
        Assertions.assertEquals(initialInputAmount, input.getAmount());
        var results = payload.getListOrEmpty("final_result_items");
        var result = PaperItemStackPayloadCodec.decode((CompoundTag) results.get(0));
        Assertions.assertEquals(finalResultType, result.getType());
    }

    private static String string(CompoundTag tag, String key) {
        return tag.getString(key).orElseThrow();
    }
}
