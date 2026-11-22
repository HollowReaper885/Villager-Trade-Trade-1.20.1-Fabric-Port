package com.ebtrader.fabric;

import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

public class VillagerBookHandler {
	private static final String MANAGED_TAG = "ebtrader_managed";
	private static final int MAX_TAUGHT_TRADES = 5;
	private static final int PRICE_BASE = 3;
	private static final int PRICE_PER_LEVEL = 2;
	private static final int MAX_PRICE = 64;
	private static final int TRADE_MAX_USES = 12;
	private static final double REROLL_MIN_MULT = 0.5;
	private static final double REROLL_MAX_MULT = 1.25;
	private static final Random RANDOM = new Random();

	public static void register() {
		UseEntityCallback.EVENT.register(VillagerBookHandler::onUseEntity);
	}

	private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, @Nullable EntityHitResult hitResult) {
		if (world.isClientSide) {
			return InteractionResult.PASS;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		if (!(entity instanceof Villager villager)) {
			return InteractionResult.PASS;
		}
		if (villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
			return InteractionResult.PASS;
		}

		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(Items.ENCHANTED_BOOK)) {
			return InteractionResult.PASS;
		}

		// For enchanted books this reads the "StoredEnchantments" NBT list.
		Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
		if (enchants.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (enchants.size() > 1) {
			if (player instanceof ServerPlayer serverPlayer) {
				EnchantPickerMenu.open(serverPlayer, villager, enchants);
			}
			return InteractionResult.SUCCESS;
		}

		Map.Entry<Enchantment, Integer> only = enchants.entrySet().iterator().next();
		if (!teachOrReroll(player, villager, only.getKey(), only.getValue())) {
			return InteractionResult.FAIL;
		}
		consumeOne(player, stack);
		return InteractionResult.SUCCESS;
	}

	static boolean teachOrReroll(Player player, Villager villager, Enchantment enchant, int level) {
		String enchantId = idOf(enchant);
		MerchantOffers offers = villager.getOffers();
		int existingIndex = findManagedOfferIndex(offers, enchantId);
		int price = calculatePrice(enchantId, level);

		if (existingIndex >= 0) {
			int previousPrice = offers.get(existingIndex).getBaseCostA().getCount();
			double mult = REROLL_MIN_MULT + RANDOM.nextDouble() * (REROLL_MAX_MULT - REROLL_MIN_MULT);
			int rerolledPrice = Math.max(1, Math.min(MAX_PRICE, (int) Math.round(price * mult)));
			rerolledPrice = Math.min(rerolledPrice, previousPrice); // never increase the price
			offers.set(existingIndex, buildOffer(enchant, level, rerolledPrice));
			sendMessage(player, Component.literal("Reroll! The villager's trade now sells for " + rerolledPrice + " emeralds and is fully restocked.")
					.withStyle(ChatFormatting.GOLD));
			return true;
		}

		if (countManagedOffers(offers) >= MAX_TAUGHT_TRADES) {
			sendMessage(player, Component.literal("This villager already knows enough trades (" + MAX_TAUGHT_TRADES + ").")
					.withStyle(ChatFormatting.RED));
			return false;
		}

		offers.add(buildOffer(enchant, level, price));
		sendMessage(player, Component.literal("The villager learns to trade that book! It'll sell one for " + price + " emeralds.")
				.withStyle(ChatFormatting.GREEN));
		return true;
	}

	static boolean villagerKnows(Villager villager, Enchantment enchant) {
		return findManagedOfferIndex(villager.getOffers(), idOf(enchant)) >= 0;
	}

	static boolean villagerAtLimit(Villager villager) {
		return countManagedOffers(villager.getOffers()) >= MAX_TAUGHT_TRADES;
	}

	static void sendMessage(Player player, Component message) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(message);
		}
	}

	private static MerchantOffer buildOffer(Enchantment enchant, int level, int price) {
		ItemStack sellItem = new ItemStack(Items.ENCHANTED_BOOK);
		EnchantedBookItem.addEnchantment(sellItem, new EnchantmentInstance(enchant, level));
		sellItem.getOrCreateTag().putBoolean(MANAGED_TAG, true);
		return new MerchantOffer(new ItemStack(Items.EMERALD, price), sellItem, TRADE_MAX_USES, 1, 0.05F);
	}

	private static int findManagedOfferIndex(MerchantOffers offers, String enchantId) {
		for (int i = 0; i < offers.size(); i++) {
			ItemStack result = offers.get(i).getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) {
				continue;
			}
			for (Enchantment e : EnchantmentHelper.getEnchantments(result).keySet()) {
				if (idOf(e).equals(enchantId)) {
					return i;
				}
			}
		}
		return -1;
	}

	private static long countManagedOffers(MerchantOffers offers) {
		long count = 0;
		for (MerchantOffer offer : offers) {
			if (isManaged(offer)) {
				count++;
			}
		}
		return count;
	}

	private static boolean isManaged(MerchantOffer offer) {
		ItemStack sell = offer.getResult();
		return sell.is(Items.ENCHANTED_BOOK) && sell.hasTag() && sell.getTag().getBoolean(MANAGED_TAG);
	}

	static String idOf(Enchantment enchant) {
		ResourceLocation key = BuiltInRegistries.ENCHANTMENT.getKey(enchant);
		return key == null ? "unknown" : key.toString();
	}

	static int calculatePrice(String enchantId, int level) {
		int price = PRICE_BASE + Math.max(0, level - 1) * PRICE_PER_LEVEL;
		return Math.max(1, Math.min(MAX_PRICE, price));
	}

	private static void consumeOne(Player player, ItemStack stack) {
		if (!player.getAbilities().instabuild) {
			stack.shrink(1);
		}
	}
}
