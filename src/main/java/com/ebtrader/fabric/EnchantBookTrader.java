package com.ebtrader.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnchantBookTrader implements ModInitializer {
	public static final String MOD_ID = "enchantbooktrader";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		VillagerBookHandler.register();
		LOGGER.info("EnchantBookTrader initialized.");
	}
}
