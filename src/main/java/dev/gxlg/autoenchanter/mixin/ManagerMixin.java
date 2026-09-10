package dev.gxlg.autoenchanter.mixin;

import dev.gxlg.autoenchanter.Worker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("UnresolvedMixinReference")
@Mixin(ClientPlayerInteractionManager.class)
public class ManagerMixin {

    @Shadow
    private MinecraftClient client;

    @Inject(at = @At("HEAD"), method = "clickSlot", cancellable = true)
    private void clickSlot(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo info) {
        if (Worker.getState() == Worker.State.SELECT) {
            if (slotId > 2 && (
                    Worker.getSelected().size() < 1 ||
                    Worker.getSelected().contains(slotId) ||
                    player.currentScreenHandler.getSlot(slotId).getStack().getItem() == Items.ENCHANTED_BOOK ||
                    player.currentScreenHandler.getSlot(slotId).getStack().getItem() == player.currentScreenHandler.getSlot(Worker.getSelected().get(0)).getStack().getItem()
            )) {
                Worker.toggleSelection(slotId);
            }
            info.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "tick")
    private void tick(CallbackInfo info) {
        if (client.currentScreen == null || !(client.currentScreen instanceof AnvilScreen)) {
            Worker.closeScreen();
        }
        Worker.tick();
    }
}
