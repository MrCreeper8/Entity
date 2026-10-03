package dev.entity.client.autonomy;

import dev.entity.client.autonomy.policy.HomeEconomySession.ContainerIdentity;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.Capability;
import dev.entity.client.autonomy.policy.PersonalSuppliesPolicy.StackFact;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Real physical facts for outbound allocation only; never a mining or pickup filter. */
public final class MinecraftPersonalSuppliesAdapter {
    private final MinecraftClient client;
    private final RegistryWrapper.WrapperLookup testRegistries;
    private final FuelRegistry testFuel;

    public MinecraftPersonalSuppliesAdapter(MinecraftClient client) {
        this(client, null, null);
    }

    MinecraftPersonalSuppliesAdapter(MinecraftClient client, RegistryWrapper.WrapperLookup registries,
            FuelRegistry fuel) {
        this.client = Objects.requireNonNull(client, "client");
        this.testRegistries = registries;
        this.testFuel = fuel;
    }

    private boolean available() {
        return client.player != null && (testRegistries != null || client.getNetworkHandler() != null);
    }

    private RegistryWrapper.WrapperLookup registries() {
        return testRegistries != null ? testRegistries : client.getNetworkHandler().getRegistryManager();
    }

    private FuelRegistry fuel() {
        return testFuel != null ? testFuel : client.getNetworkHandler().getFuelRegistry();
    }

    StackFact observedFact(String stackId, ItemStack stack, boolean equipped) {
        return fact(stackId, stack, equipped, registries(), fuel());
    }

    String observedComponents(ItemStack stack) {
        return componentsKey(stack, registries());
    }

    /** All carried slots, including selected hand, armor and offhand, exactly once. */
    public List<StackFact> playerStacks() {
        if (!available()) return List.of();
        return playerStacks(client.player.getInventory(), registries(), fuel());
    }

    /** Read-only equipment identity; ordinary wear is the sole normalized component. */
    public Map<String, String> workingKitComponents() {
        if (!available()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !stack.isDamageable()) continue;
            ItemStack unworn = stack.copy();
            unworn.setDamage(0);
            result.put(playerStackId(slot), componentsKey(unworn, registries()));
        }
        return Map.copyOf(result);
    }

    public Optional<StackFact> playerStack(int inventorySlot) {
        if (!available()) return Optional.empty();
        PlayerInventory inventory = client.player.getInventory();
        if (inventorySlot < 0 || inventorySlot >= inventory.size()) return Optional.empty();
        ItemStack stack = inventory.getStack(inventorySlot);
        return stack.isEmpty() ? Optional.empty() : Optional.of(fact(playerStackId(inventorySlot), stack,
                equippedStack(inventory, inventorySlot, stack),
                registries(), fuel()));
    }

    /**
     * Typed vanilla item payload for a server-side exact-slot handoff. The Paper
     * receiver deserializes these bytes and compares actual item components plus
     * the separately frozen slot/count; it must not compare a lossy JSON rendering.
     */
    public Optional<byte[]> playerStackBytes(int inventorySlot) {
        if (!available() || inventorySlot < 0 || inventorySlot >= client.player.getInventory().size()) {
            return Optional.empty();
        }
        ItemStack stack = client.player.getInventory().getStack(inventorySlot);
        if (stack.isEmpty()) return Optional.empty();
        if (stack.getComponents().getTypes().stream().anyMatch(type -> type.shouldSkipSerialization())) {
            throw new IllegalArgumentException("stack has a non-persistent component; exact handoff unavailable");
        }
        NbtCompound item = (NbtCompound) ItemStack.CODEC.encodeStart(registries().getOps(NbtOps.INSTANCE), stack)
                .getOrThrow();
        item.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(item, bytes);
            return Optional.of(bytes.toByteArray());
        } catch (IOException impossibleMemoryStream) {
            throw new IllegalStateException("cannot encode exact item handoff", impossibleMemoryStream);
        }
    }

    /**
     * Caller must obtain identity and syncId from WorkstationController's acknowledged
     * exact pinned screen, not infer Home ownership from an arbitrary open container.
     * Damaged and custom stacks remain visible so cleanup can explain why it keeps them.
     */
    public List<StackFact> homeChestStacks(ContainerIdentity acknowledgedIdentity, int acknowledgedSyncId) {
        if (!available()
                || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)
                || !acknowledgedScreen(handler, acknowledgedIdentity, acknowledgedSyncId)) return List.of();
        return homeChestStacks(handler, client.player.getInventory(), acknowledgedIdentity,
                registries(), fuel());
    }

    public static boolean acknowledgedScreen(GenericContainerScreenHandler handler,
            ContainerIdentity identity, int syncId) {
        return identity != null && handler.syncId == syncId
                && handler.getRows() * 9 == identity.slotCount();
    }

    static List<StackFact> playerStacks(PlayerInventory inventory,
            RegistryWrapper.WrapperLookup registries, FuelRegistry fuel) {
        List<StackFact> result = new ArrayList<>();
        for (int index = 0; index < inventory.size(); index++) {
            ItemStack stack = inventory.getStack(index);
            if (!stack.isEmpty()) result.add(fact(playerStackId(index), stack,
                    equippedStack(inventory, index, stack), registries, fuel));
        }
        return List.copyOf(result);
    }

    static List<StackFact> homeChestStacks(GenericContainerScreenHandler handler, PlayerInventory inventory,
            ContainerIdentity identity, RegistryWrapper.WrapperLookup registries, FuelRegistry fuel) {
        if (!acknowledgedScreen(handler, identity, handler.syncId)) return List.of();
        List<StackFact> result = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory != inventory && !slot.getStack().isEmpty()) {
                result.add(fact(chestStackId(identity, handler.syncId, slot.id), slot.getStack(),
                        false, registries, fuel));
            }
        }
        return List.copyOf(result);
    }

    public static String playerStackId(int index) {
        if (index < 0 || index > 40) throw new IllegalArgumentException("invalid player inventory index");
        return "player:" + index;
    }

    public static int playerInventorySlot(String stackId) {
        if (stackId == null || !stackId.startsWith("player:")) return -1;
        try {
            int index = Integer.parseInt(stackId.substring(7));
            return index >= 0 && index <= 40 && playerStackId(index).equals(stackId) ? index : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    public static String chestStackId(ContainerIdentity identity, int syncId, int handlerSlot) {
        Objects.requireNonNull(identity, "identity");
        if (syncId < 0 || handlerSlot < 0 || handlerSlot >= identity.slotCount()) {
            throw new IllegalArgumentException("invalid acknowledged chest slot");
        }
        // Record fields are exact, immutable and ordered; no component/geometry hash is used.
        return "home:" + identity + ":sync:" + syncId + ":slot:" + handlerSlot;
    }

    public static StackFact fact(String stackId, ItemStack stack, boolean equipped,
            RegistryWrapper.WrapperLookup registries, FuelRegistry fuel) {
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("empty physical stack");
        boolean custom = stack.getComponentChanges().entrySet().stream()
                .anyMatch(entry -> entry.getKey() != DataComponentTypes.DAMAGE);
        var food = stack.get(DataComponentTypes.FOOD);
        return new StackFact(stackId, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                componentsKey(stack, registries), stack.isDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamage()) : Integer.MAX_VALUE,
                capabilities(stack), custom, equipped, Objects.requireNonNull(fuel, "fuel").isFuel(stack),
                food == null ? 0 : Math.max(0, food.nutrition()));
    }

    /** Complete canonical component values, not hashCode/toString or a lossy name/enchantment subset. */
    public static String componentsKey(ItemStack stack, RegistryWrapper.WrapperLookup registries) {
        if (stack.getComponents().getTypes().stream().anyMatch(type -> type.shouldSkipSerialization())) {
            // A transient component cannot silently disappear from destructive authorization.
            throw new IllegalArgumentException("stack has a non-persistent component; exact cleanup unavailable");
        }
        NbtElement encoded = ComponentMap.CODEC.encodeStart(
                Objects.requireNonNull(registries, "registries").getOps(NbtOps.INSTANCE),
                stack.getComponents()).getOrThrow();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeCanonical(encoded, new DataOutputStream(bytes));
            return "nbt-components-v1:" + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossibleMemoryStream) {
            throw new IllegalStateException("cannot encode exact stack components", impossibleMemoryStream);
        }
    }

    private static void writeCanonical(NbtElement value, DataOutputStream output) throws IOException {
        // JsonOps collapses e.g. NBT byte1 and int1. Keep every binary NBT type/value,
        // canonicalize only unordered compound keys and preserve all sequence order.
        output.writeByte(value.getType());
        if (value instanceof NbtCompound compound) {
            output.writeInt(compound.getSize());
            for (String key : compound.getKeys().stream().sorted().toList()) {
                output.writeUTF(key);
                writeCanonical(compound.get(key), output);
            }
        } else if (value instanceof NbtList list) {
            output.writeInt(list.size());
            for (NbtElement child : list) writeCanonical(child, output);
        } else value.write(output);
    }

    private static Map<String, Capability> capabilities(ItemStack stack) {
        Map<String, Capability> result = new LinkedHashMap<>();
        var tool = stack.get(DataComponentTypes.TOOL);
        if (tool != null) {
            var stone = Blocks.STONE.getDefaultState();
            double speed = stack.getMiningSpeedMultiplier(stone);
            if (speed > 1.0 && stack.isSuitableFor(stone)) {
                int level = stack.isSuitableFor(Blocks.OBSIDIAN.getDefaultState()) ? 4
                        : stack.isSuitableFor(Blocks.DIAMOND_ORE.getDefaultState()) ? 3
                        : stack.isSuitableFor(Blocks.IRON_ORE.getDefaultState()) ? 2 : 1;
                result.put("pickaxe", new Capability(level, speed));
            }
            double axeSpeed = stack.getMiningSpeedMultiplier(Blocks.OAK_LOG.getDefaultState());
            if (axeSpeed > 1.0) result.put("axe", new Capability(0, axeSpeed));
            // Dirt and hay measure shipped tool rules; actual family tags identify
            // shovel path-making and hoe tilling without material-name guesses.
            if (stack.isIn(ItemTags.SHOVELS)) result.put("shovel", new Capability(0,
                    Math.max(1, stack.getMiningSpeedMultiplier(Blocks.DIRT.getDefaultState()))));
            if (stack.isIn(ItemTags.HOES)) result.put("hoe", new Capability(0,
                    Math.max(1, stack.getMiningSpeedMultiplier(Blocks.HAY_BLOCK.getDefaultState()))));
        }
        // Home's weapon category is a dedicated weapon, not a second claim on the axe.
        if (stack.isIn(ItemTags.SWORDS)) {
            var attributes = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                    AttributeModifiersComponent.DEFAULT);
            var damageOnly = new AttributeModifiersComponent(attributes.modifiers().stream()
                    .filter(entry -> entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)).toList());
            double damage = damageOnly.applyOperations(1.0, EquipmentSlot.MAINHAND);
            if (damage > 0) result.put("weapon", new Capability(0, damage));
        }
        if (stack.contains(DataComponentTypes.BLOCKS_ATTACKS)) result.put("shield", new Capability(0, 1.0));
        EquipmentSlot armorSlot = protectiveArmorSlot(stack);
        if (armorSlot != null) {
            result.put("armor_" + switch (armorSlot) {
                case HEAD -> "head";
                case CHEST -> "chest";
                case LEGS -> "legs";
                case FEET -> "feet";
                default -> throw new IllegalStateException("not an armor slot");
            }, new Capability(0, armorProtection(stack, armorSlot)));
        }
        return Map.copyOf(result);
    }

    /** Actual equippable slot and armor attribute, not item-name suffix guesses. */
    static EquipmentSlot protectiveArmorSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null || armorInventorySlot(equippable.slot()) < 0) return null;
        return armorProtection(stack, equippable.slot()) > 0 ? equippable.slot() : null;
    }

    static int armorInventorySlot(EquipmentSlot slot) {
        if (slot == null) return -1;
        return switch (slot) {
            case HEAD -> 39;
            case CHEST -> 38;
            case LEGS -> 37;
            case FEET -> 36;
            default -> -1;
        };
    }

    private static double armorProtection(ItemStack stack, EquipmentSlot slot) {
        var attributes = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                AttributeModifiersComponent.DEFAULT);
        return new AttributeModifiersComponent(attributes.modifiers().stream()
                .filter(entry -> entry.attribute().equals(EntityAttributes.ARMOR)).toList())
                .applyOperations(0.0, slot);
    }

    private static boolean equippedStack(PlayerInventory inventory, int index, ItemStack stack) {
        EquipmentSlot armor = protectiveArmorSlot(stack);
        return armor != null ? index == armorInventorySlot(armor)
                : index == inventory.getSelectedSlot() || index >= 36;
    }
}
