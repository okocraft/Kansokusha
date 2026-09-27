package net.okocraft.kansokusha.paper.builtin;

import net.minecraft.nbt.CompoundTag;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

class PaperWorkstationAuditListenerTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        PaperBlockEventTestSupport.bootstrapMinecraft();
    }

    @Test
    void testContainerTransactionOnlyAcceptsStorageInventoryTypes() {
        for (var type : new InventoryType[]{
            InventoryType.CHEST,
            InventoryType.BARREL,
            InventoryType.SHULKER_BOX,
            InventoryType.HOPPER,
            InventoryType.FURNACE,
            InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER,
            InventoryType.BREWING,
            InventoryType.ENDER_CHEST,
            InventoryType.DISPENSER,
            InventoryType.DROPPER,
            InventoryType.CRAFTER
        }) {
            var inventory = inventory(type, null);
            Assertions.assertTrue(
                PaperPlayerContainerTransactionListener.isStorageContainer(inventory),
                type.name()
            );
        }

        for (var type : new InventoryType[]{
            InventoryType.WORKBENCH,
            InventoryType.CRAFTING,
            InventoryType.ANVIL,
            InventoryType.SMITHING,
            InventoryType.ENCHANTING,
            InventoryType.LOOM,
            InventoryType.CARTOGRAPHY,
            InventoryType.GRINDSTONE,
            InventoryType.STONECUTTER,
            InventoryType.BEACON,
            InventoryType.LECTERN,
            InventoryType.MERCHANT
        }) {
            var inventory = inventory(type, null);
            Assertions.assertFalse(
                PaperPlayerContainerTransactionListener.isStorageContainer(inventory),
                type.name()
            );
        }

        var player = Mockito.mock(Player.class);
        Assertions.assertFalse(
            PaperPlayerContainerTransactionListener.isStorageContainer(
                inventory(InventoryType.CHEST, player)
            )
        );
        Assertions.assertTrue(
            PaperPlayerContainerTransactionListener.isStorageContainer(
                inventory(InventoryType.ENDER_CHEST, player)
            )
        );
    }

    @Test
    void testAnvilPayloadRecordsInputsResultAndCosts() throws Exception {
        var payload = PaperPayloadNbtCodec.decode(
            PaperWorkstationPayloadCodec.encodeAnvil(
                ItemStack.of(Material.IRON_SWORD, 1),
                ItemStack.of(Material.IRON_INGOT, 2),
                ItemStack.of(Material.IRON_SWORD, 1),
                "Repaired",
                2,
                7,
                "LEFT",
                "PICKUP_ALL"
            )
        );

        Assertions.assertEquals("Repaired", payload.getString("rename_text").orElseThrow());
        Assertions.assertEquals(2, payload.getIntOr("repair_item_count", -1));
        Assertions.assertEquals(7, payload.getIntOr("repair_cost", -1));
        Assertions.assertEquals("left", payload.getString("click").orElseThrow());
        Assertions.assertEquals("pickup_all", payload.getString("action").orElseThrow());
        Assertions.assertEquals(2, payload.getListOrEmpty("input_items").size());
        Assertions.assertEquals(
            Material.IRON_SWORD,
            PaperItemStackPayloadCodec.decode(payload.getCompoundOrEmpty("result_item")).getType()
        );
    }

    @Test
    void testEnchantPayloadRecordsChosenEnchantments() throws Exception {
        var sharpness = org.bukkit.enchantments.Enchantment.SHARPNESS;
        var payload = PaperPayloadNbtCodec.decode(
            PaperWorkstationPayloadCodec.encodeEnchant(
                ItemStack.of(Material.DIAMOND_SWORD, 1),
                30,
                3,
                2,
                Map.of(sharpness, 4)
            )
        );

        Assertions.assertEquals(30, payload.getIntOr("required_level", -1));
        Assertions.assertEquals(3, payload.getIntOr("consumed_levels", -1));
        Assertions.assertEquals(2, payload.getIntOr("button", -1));
        var enchantments = payload.getListOrEmpty("enchantments");
        Assertions.assertEquals(1, enchantments.size());
        var enchantment = (CompoundTag) enchantments.getFirst();
        Assertions.assertEquals(
            sharpness.getKey().toString(),
            enchantment.getString("type").orElseThrow()
        );
        Assertions.assertEquals(4, enchantment.getIntOr("level", -1));
    }

    @Test
    void testAnvilConfirmationRequiresFirstInputConsumption() {
        var before = new ItemStack[]{
            ItemStack.of(Material.IRON_SWORD, 1),
            ItemStack.of(Material.IRON_INGOT, 2)
        };

        Assertions.assertTrue(
            PaperPlayerWorkstationAuditListener.anvilApplied(
                before,
                new ItemStack[]{null, ItemStack.of(Material.IRON_INGOT, 1)}
            )
        );
        Assertions.assertFalse(
            PaperPlayerWorkstationAuditListener.anvilApplied(before, before)
        );
    }

    @Test
    void testSmithConfirmationRequiresAllInputsToBeConsumed() {
        var before = new ItemStack[]{
            ItemStack.of(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2),
            ItemStack.of(Material.DIAMOND_SWORD, 1),
            ItemStack.of(Material.NETHERITE_INGOT, 3)
        };

        Assertions.assertTrue(
            PaperPlayerWorkstationAuditListener.smithApplied(
                before,
                new ItemStack[]{
                    ItemStack.of(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1),
                    null,
                    ItemStack.of(Material.NETHERITE_INGOT, 2)
                }
            )
        );
        Assertions.assertFalse(
            PaperPlayerWorkstationAuditListener.smithApplied(before, before)
        );
    }

    @Test
    void testEnchantConfirmationRequiresActualItemMutation() {
        Assertions.assertTrue(
            PaperPlayerWorkstationAuditListener.enchantApplied(
                new ItemStack[]{ItemStack.of(Material.BOOK, 1), ItemStack.of(Material.LAPIS_LAZULI, 3)},
                new ItemStack[]{ItemStack.of(Material.ENCHANTED_BOOK, 1), ItemStack.of(Material.LAPIS_LAZULI, 2)}
            )
        );
        Assertions.assertFalse(
            PaperPlayerWorkstationAuditListener.enchantApplied(
                new ItemStack[]{ItemStack.of(Material.DIAMOND_SWORD, 1), ItemStack.of(Material.LAPIS_LAZULI, 3)},
                new ItemStack[]{ItemStack.of(Material.DIAMOND_SWORD, 1), ItemStack.of(Material.LAPIS_LAZULI, 3)}
            )
        );
    }

    private static Inventory inventory(
        InventoryType type,
        org.bukkit.inventory.InventoryHolder holder
    ) {
        var inventory = Mockito.mock(Inventory.class);
        Mockito.when(inventory.getType()).thenReturn(type);
        Mockito.when(inventory.getHolder()).thenReturn(holder);
        return inventory;
    }
}
