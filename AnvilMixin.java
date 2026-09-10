package dev.gxlg.autoenchanter.mixin;

import dev.gxlg.autoenchanter.Colors;
import dev.gxlg.autoenchanter.DataStructures;
import dev.gxlg.autoenchanter.Reflection;
import dev.gxlg.autoenchanter.Worker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("UnresolvedMixinReference")
@Mixin(AnvilScreen.class)
public abstract class AnvilMixin extends ForgingScreen<AnvilScreenHandler> {

    public AnvilMixin(AnvilScreenHandler handler, PlayerInventory playerInventory, Text title, Identifier texture) {
        super(handler, playerInventory, title, texture);
    }

    @Inject(at = @At("TAIL"), method = "setup")
    private void setup(CallbackInfo ci) {
        assert client != null;
        TextWidget text = new TextWidget(20, 20, width - 40, 20, Text.empty(), textRenderer);
        if (Reflection.version("<= 1.21.8")) {
            Object ignored = Reflection.wrap("@text method_48596/alignLeft");
        }
        this.addDrawableChild(text);

        ButtonWidget buttonSelect = new ButtonWidget.Builder(Text.of("Select items"), b -> Worker.select()).dimensions(20, 45, 100, 20).build();
        ButtonWidget buttonCalculate = new ButtonWidget.Builder(Text.of("Calculate"), b -> Worker.calculate()).dimensions(20, 45, 100, 20).build();
        ButtonWidget buttonStart = new ButtonWidget.Builder(Text.of("Start enchanting"), b -> Worker.start()).dimensions(20, 45, 100, 20).build();
        ButtonWidget buttonCancel = new ButtonWidget.Builder(Text.of("Cancel"), b -> Worker.cancel()).dimensions(20, 67, 100, 20).build();

        this.addDrawableChild(buttonSelect);
        this.addDrawableChild(buttonCalculate);
        this.addDrawableChild(buttonStart);
        this.addDrawableChild(buttonCancel);

        Worker.setup(text, buttonSelect, buttonCalculate, buttonStart, buttonCancel);
    }

    @Inject(at = @At("RETURN"), method = "drawInvalidRecipeArrow")
    private void drawInvalidRecipeArrow(DrawContext context, int xs, int ys, CallbackInfo info) {
        boolean first = true;
        for (int slot : Worker.getSelected()) {
            int x = xs + handler.slots.get(slot).x;
            int y = ys + handler.slots.get(slot).y;
            context.fill(x, y, x + 16, y + 16, first ? Colors.BLUE : Colors.GREEN);
            first = false;
        }

        double progress = Worker.getProgress();
        if (progress >= 0) {
            context.fill(20, 41, 120, 43, 0xFF909090);
            context.fill(20, 41, (int) Math.round(progress * 100 + 20), 43, Colors.GREEN);
        }

        DataStructures.Shape shape = Worker.getShape();
        if (shape != null) {
            shape.draw(context, textRenderer, 10, 105, 128, 128);
        }
    }
}