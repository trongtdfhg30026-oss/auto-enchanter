package dev.gxlg.autoenchanter;

import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoEnchanter implements ClientModInitializer {
	public static final String MOD_ID = "auto-enchanter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("Hello from Auto Enchanter!");

		ClientCommandRegistrationCallback.EVENT.register((l, d) -> l
				.register(ClientCommandManager.literal("autoenchanter")
						.then(ClientCommandManager.literal("cancel").executes(Worker::cancelCommand))
						.then(ClientCommandManager.literal("loop")
								.then(ClientCommandManager.literal("on").executes(c -> Worker.loopCommand(c, true)))
								.then(ClientCommandManager.literal("off").executes(c -> Worker.loopCommand(c, false)))
						)
				)
		);
	}
}