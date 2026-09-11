package dev.gxlg.autoenchanter;

import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoEnchanter implements ClientModInitializer {
	public static final String MOD_ID = "auto-enchanter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// NEW: Ctrl+U toggles the whole system on/off. Bound to plain "U" - the Ctrl
	// requirement is checked manually in the tick handler below, since vanilla
	// KeyBinding only tracks a single key, not modifier combos.
	private static KeyBinding TOGGLE_KEY;
	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

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

		TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.auto-enchanter.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_U,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				if (isControlDown(client)) {
					Worker.toggleSystem(client.player);
				}
			}
		});
	}

	/** Raw GLFW check for either Ctrl key being held, avoiding any Screen-instance dependency. */
	private static boolean isControlDown(MinecraftClient client) {
		return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}
}

