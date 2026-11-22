package com.ebtrader.fabric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

/** A read-only chest GUI listing each enchantment on a multi-enchant book; clicking one teaches it. */
public class EnchantPickerMenu extends ChestMenu {
	private static final int MAX_OPTIONS = 54;
	private static final double MAX_DISTANCE_SQR = 64.0;

	private final Villager villager;
	private final List<Enchantment> options;

	private EnchantPickerMenu(MenuType<?> type, int containerId, Inventory playerInventory, Container container,
							  int rows, Villager villager, List<Enchantment> options) {
		super(type, containerId, playerInventory, container, rows);
		this.villager = villager;
		this.options = options;
	}

	static void open(ServerPlayer player, Villager villager, Map<Enchantment, Integer> enchants) {
		List<Enchantment> sorted = new ArrayList<>(enchants.keySet());
		sorted.sort(Comparator.comparing(VillagerBookHandler::idOf));
		List<Enchantment> options = sorted.size() > MAX_OPTIONS ? new ArrayList<>(sorted.subList(0, MAX_OPTIONS)) : sorted;

		int rows = (options.size() + 8) / 9;
		MenuType<ChestMenu> type = menuTypeFor(rows);
		SimpleContainer container = new SimpleContainer(rows * 9);
		for (int i = 0; i < options.size(); i++) {
			Enchantment enchant = options.get(i);
			container.setItem(i, icon(villager, enchant, enchants.get(enchant)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, p) -> new EnchantPickerMenu(type, containerId, inventory, container, rows, villager, options),
				Component.literal("Pick an enchantment to teach")));
	}

	private static MenuType<ChestMenu> menuTypeFor(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	private static ItemStack icon(Villager villager, Enchantment enchant, int level) {
		ItemStack icon = new ItemStack(Items.ENCHANTED_BOOK);
		EnchantedBookItem.addEnchantment(icon, new EnchantmentInstance(enchant, level));

		int price = VillagerBookHandler.calculatePrice(VillagerBookHandler.idOf(enchant), level);
		List<Component> lore = new ArrayList<>();
		lore.add(line("Click to teach the villager this enchantment.", ChatFormatting.GRAY));
		lore.add(line("Base price: " + price + " emeralds", ChatFormatting.GRAY));
		if (VillagerBookHandler.villagerKnows(villager, enchant)) {
			lore.add(line("The villager already knows this - it will be rerolled.", ChatFormatting.GOLD));
		} else if (VillagerBookHandler.villagerAtLimit(villager)) {
			lore.add(line("The villager can't learn any more new types.", ChatFormatting.RED));
		} else {
			lore.add(line("This will be a new trade.", ChatFormatting.GREEN));
		}

		// 1.20.1 has no item components: lore lives in the "display" NBT as JSON strings.
		ListTag loreTag = new ListTag();
		for (Component c : lore) {
			loreTag.add(StringTag.valueOf(Component.Serializer.toJson(c)));
		}
		icon.getOrCreateTagElement("display").put("Lore", loreTag);
		return icon;
	}

	private static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(color).withStyle(style -> style.withItalic(false));
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		if (player instanceof ServerPlayer serverPlayer
				&& slotId >= 0 && slotId < options.size()
				&& (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			pick(serverPlayer, options.get(slotId));
		}
		// Never run vanilla click handling: the picker is read-only.
		broadcastFullState();
	}

	private void pick(ServerPlayer player, Enchantment enchant) {
		if (villager.isRemoved() || !villager.isAlive()) {
			fail(player, "That villager is no longer available.");
			return;
		}
		if (villager.level() != player.level() || villager.distanceToSqr(player) > MAX_DISTANCE_SQR) {
			fail(player, "That villager is too far away.");
			return;
		}

		ItemStack held = player.getMainHandItem();
		Map<Enchantment, Integer> stored = held.is(Items.ENCHANTED_BOOK)
				? EnchantmentHelper.getEnchantments(held) : Map.of();
		if (stored.size() < 2 || !stored.containsKey(enchant)) {
			fail(player, "The book in your hand changed - try again.");
			return;
		}

		int level = stored.get(enchant);
		if (!VillagerBookHandler.teachOrReroll(player, villager, enchant, level)) {
			return;
		}

		if (!player.getAbilities().instabuild) {
			Map<Enchantment, Integer> remaining = new LinkedHashMap<>(stored);
			remaining.remove(enchant);
			held.removeTagKey(EnchantedBookItem.TAG_STORED_ENCHANTMENTS);
			remaining.forEach((e, l) -> EnchantedBookItem.addEnchantment(held, new EnchantmentInstance(e, l)));
			VillagerBookHandler.sendMessage(player,
					Component.literal("Your book was returned without that enchantment.").withStyle(ChatFormatting.GRAY));
		}
		player.closeContainer();
	}

	private void fail(ServerPlayer player, String message) {
		VillagerBookHandler.sendMessage(player, Component.literal(message).withStyle(ChatFormatting.RED));
		player.closeContainer();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}
}
