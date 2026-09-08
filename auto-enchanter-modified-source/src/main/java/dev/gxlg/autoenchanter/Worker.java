package dev.gxlg.autoenchanter;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.AnvilBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dev.gxlg.autoenchanter.DataStructures.*;

public class Worker {

    private static TextWidget textDisplay;
    private static ButtonWidget buttonSelect, buttonCalculate, buttonCancel, buttonStart;
    private static State state = State.UNSET;


    private static final List<Integer> selected = new ArrayList<>();

    private static int enchantCost = 0;
    private static boolean readyToEnchant = false;
    private static Utils.ShapePool search = null;
    private static IterItem<Shape> current = null;
    private static List<AnvilItem> operations = null;

    // ==== NEW: remembers the slots used for the last successful selection so ====
    // ==== the whole select -> calculate -> start cycle can be repeated forever ====
    private static final List<Integer> rememberedSlots = new ArrayList<>();
    private static boolean loopEnabled = true;
    private static int loopTimer = 0;

    // ==== NEW: hotbar slots used for automation (1-indexed as shown on screen) ====
    // hotbar slot "2" -> holds spare anvils, used to auto-replace a broken anvil
    private static final int ANVIL_HOTBAR_SLOT = 1;
    // hotbar slot "9" -> finished (fully enchanted) items get dropped here
    private static final int OUTPUT_HOTBAR_SLOT = 8;

    // ==== NEW: anvil auto-replace bookkeeping ====
    public static BlockPos lastAnvilPos = null;
    private static int recoverTimer = 0;
    private static int recoverAttempts = 0;
    private static Integer restoreHotbarSlot = null;

    public static void setup(TextWidget text, ButtonWidget select, ButtonWidget calculate, ButtonWidget start, ButtonWidget cancel) {
        textDisplay = text;
        buttonSelect = select;
        buttonCalculate = calculate;
        buttonStart = start;
        buttonCancel = cancel;

        if (state == State.CALCULATE) {
            showText("Calculating...", Colors.BLUE);
            buttonSelect.visible = false;
            buttonCalculate.visible = false;
            buttonCancel.visible = true;
            buttonStart.visible = false;

        } else if (state == State.EXEC || state == State.RECOVER_SWITCH || state == State.RECOVER_PLACE
                || state == State.RECOVER_OPEN || state == State.LOOP_WAIT) {
            // The anvil screen was re-opened while we were still in the middle of an
            // enchanting run (e.g. the anvil broke and we just replaced it, or the
            // player simply re-opened it manually). Resume automatically instead of
            // requiring the user to press "Start enchanting" again.
            showText("Resuming...", Colors.GREEN);
            buttonSelect.visible = false;
            buttonCalculate.visible = false;
            buttonCancel.visible = true;
            buttonStart.visible = false;
            if (state == State.EXEC) start();

        } else if (readyToEnchant) {
            ready();
        } else {
            cancel();
        }
    }

    public static void ready() {
        showText("Can enchant for " + enchantCost + " lvls", Colors.GREEN);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = true;
        state = State.UNSET;

        // NEW: with looping enabled we never wait for the manual "Start enchanting"
        // click - as soon as a valid combination is found, run it immediately.
        if (loopEnabled) {
            start();
        }
    }

    public static void select() {
        showText("Select items to use", Colors.YELLOW);
        buttonSelect.visible = false;
        buttonCalculate.visible = true;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.SELECT;
    }

    public static int cancelCommand(CommandContext<FabricClientCommandSource> context) {
        cancel();
        context.getSource().sendFeedback(Text.of("Cancelled everything"));
        return 0;
    }

    // NEW: /autoenchanter loop <on|off>
    public static int loopCommand(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        loopEnabled = enabled;
        context.getSource().sendFeedback(Text.of("Auto-loop is now " + (enabled ? "ON" : "OFF")));
        return 0;
    }

    public static void cancel() {
        if (search != null) {
            search.cancel();
            search = null;
        }
        current = null;
        readyToEnchant = false;
        operations = null;
        selected.clear();
        rememberedSlots.clear();
        recoverTimer = 0;
        recoverAttempts = 0;
        restoreHotbarSlot = null;
        hideText();
        buttonSelect.visible = true;
        buttonCalculate.visible = false;
        buttonCancel.visible = false;
        buttonStart.visible = false;
        state = State.UNSET;
    }

    public static void calculate() {
        if (selected.size() < 2) {
            showText("Not enough items selected", Colors.RED);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null) return;

        List<Enchant> collection = new ArrayList<>();
        Enchant mainItem = null;

        for (int slot : selected) {
            ItemStack stack = handler.getSlot(slot).getStack();
            Enchant item = Enchant.from(stack);
            collection.add(item);
            if (mainItem == null) mainItem = item;
        }

        boolean conflicts = false;

        Set<RegistryEntry<Enchantment>> ignoredEncs = new HashSet<>();
        for (int i = 0; i < collection.size() - 1; i++) {
            Enchant b = collection.get(i);
            if (b.enchantments().size() == 0) continue;

            boolean anyToItem = false;
            for (int j = i + 1; j < collection.size(); j++) {
                Enchant c = collection.get(j);
                for (RegistryEntry<Enchantment> d : b.enchantments().keySet()) {
                    boolean anyCompat = false;
                    for (RegistryEntry<Enchantment> e : c.enchantments().keySet()) {
                        if (d == e || MultiVersion.canCombine(d, e)) anyCompat = true;
                        if (d != e && !MultiVersion.canCombine(d, e)) {
                            if (b == mainItem) {
                                ignoredEncs.add(e);
                            }
                        }
                    }
                    if (!anyCompat) {
                        conflicts = true;
                        break;
                    }

                    if (d.value().isAcceptableItem(mainItem.item().getDefaultStack()) || mainItem.item() == Items.ENCHANTED_BOOK)
                        anyToItem = true;
                }
            }
            if (!anyToItem) {
                conflicts = true;
                break;
            }
        }

        if (conflicts) {
            showText("Some items are incompatible or useless", Colors.RED);
            return;
        }

        Map<RegistryEntry<Enchantment>, Map<Integer, List<Enchant>>> map = new HashMap<>();
        for (Enchant item : collection) {
            for (Map.Entry<RegistryEntry<Enchantment>, EMap> e : item.enchantments().entrySet()) {
                RegistryEntry<Enchantment> enc = e.getKey();
                int lvl = e.getValue().lvl();

                if (!map.containsKey(enc)) map.put(enc, new HashMap<>());
                Map<Integer, List<Enchant>> listMap = map.get(enc);
                if (!listMap.containsKey(lvl)) listMap.put(lvl, new ArrayList<>());
                listMap.get(lvl).add(item);
            }
        }

        Map<RegistryEntry<Enchantment>, Map<Integer, List<Enchant>>> trueMap = new HashMap<>();
        Map<RegistryEntry<Enchantment>, Integer> wasted = new HashMap<>();
        Map<RegistryEntry<Enchantment>, Integer> maxMap = new HashMap<>();
        for (Map.Entry<RegistryEntry<Enchantment>, Map<Integer, List<Enchant>>> e : map.entrySet()) {
            List<Enchant> en = e.getValue().values().stream().findFirst().orElseThrow();
            if (e.getValue().size() == 1 && en.size() == 1) {
                maxMap.put(e.getKey(), e.getValue().keySet().stream().toList().getFirst());
                continue;
            } else {
                trueMap.put(e.getKey(), e.getValue());
            }

            List<Integer> stack = new ArrayList<>();
            for (int i : e.getValue().entrySet().stream().flatMap(x -> x.getValue().stream().map(z -> x.getKey())).sorted().toList()) {
                stack.add(i);
                while (stack.size() > 1 && stack.getLast().equals(stack.get(stack.size() - 2)) && stack.getLast() < e.getKey().value().getMaxLevel()) {
                    int j = stack.removeLast();
                    stack.removeLast();
                    stack.add(j + 1);
                }
            }
            if (stack.size() > 1 && !ignoredEncs.contains(e.getKey())) {
                if (stack.getLast() == e.getKey().value().getMaxLevel()) {
                    wasted.put(e.getKey(), stack.subList(0, stack.size() - 1).stream().mapToInt(i -> i).min().orElse(0));
                } else {
                    showText(e.getKey().value() + " has wasted items", Colors.RED);
                    return;
                }
            }
            maxMap.put(e.getKey(), stack.getLast());
        }

        if (!ignoredEncs.isEmpty()) {
            for (Enchant enchant : collection) {
                if (ignoredEncs.containsAll(enchant.enchantments().keySet())) {
                    showText("Some items' every enchantment is ignored", Colors.RED);
                    return;
                }
            }
        }

        if (!wasted.isEmpty()) {
            for (Enchant enchant : collection) {
                if (enchant.enchantments().entrySet().stream().allMatch(e -> wasted.containsKey(e.getKey()) && e.getValue().lvl() <= wasted.get(e.getKey()))) {
                    showText("Some items' every enchantment is wasted", Colors.RED);
                    return;
                }
            }
        }

        List<Enchant> allItems = new ArrayList<>(collection.subList(1, collection.size()));
        allItems.sort(Comparator.comparingInt(e -> e.enchantments().values().stream().mapToInt(j -> j.lvl() * j.cost(e.item())).sum()));

        // NEW: remember the slots used - only once we know the selection is valid.
        rememberedSlots.clear();
        rememberedSlots.addAll(selected);

        search = new Utils.ShapePool(collection.size(), allItems, trueMap, mainItem, ignoredEncs, maxMap);
        showText("Calculating...", Colors.BLUE);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.CALCULATE;
        opid = 0;
        sopid = 0;
        timer = 0;
    }

    public static void start() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || operations == null) return;

        List<EnchantedItem> outputs = operations.subList(opid, operations.size()).stream().map(AnvilItem::result).toList();
        List<EnchantedItem> inputs = operations.subList(opid, operations.size()).stream().flatMap(e -> Stream.of(e.target(), e.sacrifice())).filter(i -> outputs.stream().noneMatch(j -> j == i)).toList();
        Set<Integer> found = new HashSet<>();
        for (EnchantedItem inp : inputs) {
            int slot = IntStream.range(3, handler.getStacks().size()).boxed().filter(i -> !found.contains(i) && inp.matches(handler.getSlot(i).getStack())).findFirst().orElse(-1);
            if (slot == -1) {
                showText("Not all items for enchanting are present", Colors.RED);
                return;
            }
            found.add(slot);
        }

        showText("Enchanting, please don't use the mouse...", Colors.GREEN);
        buttonSelect.visible = false;
        buttonCalculate.visible = false;
        buttonCancel.visible = true;
        buttonStart.visible = false;
        state = State.EXEC;
    }

    public static void closeScreen() {
        if (state == State.UNSET || state == State.CALCULATE || state == State.EXEC
                || state == State.RECOVER_SWITCH || state == State.RECOVER_PLACE || state == State.RECOVER_OPEN
                || state == State.LOOP_WAIT) return;
        cancel();
    }

    public static void tick() {
        if (state == State.UNSET || state == State.SELECT) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerInteractionManager manager = client.interactionManager;
        ClientPlayerEntity player = client.player;
        if (player == null || manager == null) return;

        // NEW: handle the "anvil broke mid-enchant" auto-replace routine
        if (state == State.RECOVER_SWITCH || state == State.RECOVER_PLACE || state == State.RECOVER_OPEN) {
            handleRecovery(client, manager, player);
            return;
        }

        // NEW: handle waiting between two loop cycles for the slots to be refilled
        if (state == State.LOOP_WAIT) {
            handleLoopWait(client, player);
            return;
        }

        ScreenHandler handler = player.currentScreenHandler;

        if (state == State.CALCULATE) {
            if (search.working()) {
                current = search.peek();
                return;
            }

            FilledShape m = search.getBestShape();
            enchantCost = search.getMinCost();

            if (m == null) {
                // NEW: with looping enabled, don't give up forever - just go back to
                // waiting for the (remembered) slots to contain a valid combination.
                if (loopEnabled && !rememberedSlots.isEmpty()) {
                    showText("Couldn't find a tree, waiting...", Colors.RED);
                    beginLoopCycle(false);
                } else {
                    cancel();
                    showText("Couldn't find a tree", Colors.RED);
                    if (!(client.currentScreen instanceof AnvilScreen)) {
                        player.sendMessage(Text.literal("Couldn't find a tree").formatted(Formatting.RED), false);
                    }
                }
                return;
            }

            readyToEnchant = true;
            operations = m.execute();

            ready();
            if (!(client.currentScreen instanceof AnvilScreen)) {
                player.sendMessage(Text.literal("Can enchant for " + enchantCost + " lvls").formatted(Formatting.GREEN), false);
            }

        } else if (state == State.EXEC) {
            if (!(client.currentScreen instanceof AnvilScreen)) {
                sopid = 0;
                // NEW: the anvil screen just disappeared while we were enchanting -
                // this usually means the anvil broke. Try to fix it automatically.
                beginRecovery(client, player);
                return;
            }

            if (timer < 5) {
                timer++;
                return;
            }
            timer = 0;

            AnvilItem current = operations.get(opid);
            int cost = current.result().cost() - current.sacrifice().cost() - current.target().cost();
            if (!player.isInCreativeMode() && player.experienceLevel < cost) {
                showText("Not enough levels for the current combo, needed: " + cost, Colors.RED);
                return;
            }

            if (sopid == 0) {
                EnchantedItem item = current.target();
                if (item.matches(handler.getSlot(0).getStack())) {
                    sopid = 1;
                    return;
                }
                int slot = IntStream.range(3, handler.getStacks().size()).boxed().filter(i -> item.matches(handler.getSlot(i).getStack())).findFirst().orElse(-1);
                if (slot == -1) return;
                manager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);

            } else if (sopid == 1) {
                EnchantedItem item = current.sacrifice();
                if (item.matches(handler.getSlot(1).getStack())) {
                    sopid = 2;
                    return;
                }
                int slot = IntStream.range(3, handler.getStacks().size()).boxed().filter(i -> item.matches(handler.getSlot(i).getStack())).findFirst().orElse(-1);
                if (slot == -1) return;
                manager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);

            } else if (sopid == 2) {
                EnchantedItem item = current.result();
                if (!item.matches(handler.getSlot(2).getStack())) {
                    return;
                }

                boolean isFinalItem = (opid == operations.size() - 1);
                if (isFinalItem) {
                    // NEW: the fully enchanted item is done - drop it into hotbar slot 9
                    // instead of letting it land anywhere in the inventory.
                    dropFinishedItem(handler, manager, player);
                } else {
                    manager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                }

                timer = -10;
                opid++;
                sopid = 0;
                if (opid == operations.size()) {
                    // NEW: loop back to the beginning instead of stopping.
                    if (loopEnabled && !rememberedSlots.isEmpty()) {
                        showText("Done, looping...", Colors.GREEN);
                        beginLoopCycle(true);
                    } else {
                        cancel();
                        showText("Done", Colors.GREEN);
                    }
                }
            }
        }
    }

    private static int opid = 0;
    private static int sopid = 0;
    private static int timer = 0;

    // ==================== NEW: finished item hand-off ====================

    /**
     * Moves the finished item (currently in the anvil's result slot) into a fixed
     * hotbar slot (slot "9") so completed items always end up in the same place.
     * If that slot already has something else in it, that old item is dropped on
     * the ground to make room.
     */
    private static void dropFinishedItem(ScreenHandler handler, ClientPlayerInteractionManager manager, ClientPlayerEntity player) {
        int hotbarStart = handler.slots.size() - 9;
        int targetSlot = hotbarStart + OUTPUT_HOTBAR_SLOT;

        // Pick the finished item up onto the cursor.
        manager.clickSlot(handler.syncId, 2, 0, SlotActionType.PICKUP, player);
        // Place it in the target hotbar slot, swapping out whatever was there.
        manager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, player);
        // If something got swapped onto the cursor, throw it on the ground.
        if (!handler.getCursorStack().isEmpty()) {
            manager.clickSlot(handler.syncId, -999, 0, SlotActionType.PICKUP, player);
        }
    }

    // ==================== NEW: looping between cycles ====================

    /**
     * Called once a batch is fully processed (or the calculation failed) while
     * looping is enabled. Goes back to waiting for the remembered slots to hold
     * a fresh, valid set of items, then recalculates and restarts automatically.
     */
    private static void beginLoopCycle(boolean afterSuccess) {
        search = null;
        current = null;
        operations = null;
        readyToEnchant = false;
        opid = 0;
        sopid = 0;
        selected.clear();
        selected.addAll(rememberedSlots);
        state = State.LOOP_WAIT;
        loopTimer = 0;
        if (afterSuccess) showText("Waiting for the next batch...", Colors.BLUE);
    }

    private static void handleLoopWait(MinecraftClient client, ClientPlayerEntity player) {
        if (loopTimer < 20) {
            loopTimer++;
            return;
        }
        loopTimer = 0;

        if (!(client.currentScreen instanceof AnvilScreen)) return; // wait for the anvil GUI

        ScreenHandler handler = player.currentScreenHandler;
        boolean allPresent = rememberedSlots.stream().allMatch(s -> s < handler.getStacks().size() && !handler.getSlot(s).getStack().isEmpty());
        if (!allPresent) return; // keep waiting until the slots are refilled

        state = State.UNSET;
        calculate();
        if (state == State.UNSET) {
            // calculate() bailed out early (still incompatible, not enough items, etc.)
            state = State.LOOP_WAIT;
        }
    }

    // ==================== NEW: anvil auto-replace ====================

    /**
     * Called right when the anvil screen unexpectedly disappears while we were
     * mid-enchant. If the last known anvil block is now air, we assume it broke
     * and start the auto-replace routine; otherwise we assume the player simply
     * closed the screen and do nothing (same as before).
     */
    private static void beginRecovery(MinecraftClient client, ClientPlayerEntity player) {
        if (lastAnvilPos == null || client.world == null) return;
        if (!client.world.getBlockState(lastAnvilPos).isAir()) return;

        restoreHotbarSlot = player.getInventory().selectedSlot;
        state = State.RECOVER_SWITCH;
        recoverTimer = 0;
        recoverAttempts = 0;
        showText("Anvil broke, replacing it...", Colors.RED);
    }

    private static void handleRecovery(MinecraftClient client, ClientPlayerInteractionManager manager, ClientPlayerEntity player) {
        if (recoverTimer < 10) {
            recoverTimer++;
            return;
        }
        recoverTimer = 0;

        if (state == State.RECOVER_SWITCH) {
            player.getInventory().selectedSlot = ANVIL_HOTBAR_SLOT;
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(ANVIL_HOTBAR_SLOT));
            }
            ItemStack held = player.getInventory().getStack(ANVIL_HOTBAR_SLOT);
            if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof AnvilBlock)) {
                showText("No anvil in hotbar slot 2!", Colors.RED);
                cancel();
                return;
            }
            state = State.RECOVER_PLACE;
            return;
        }

        if (state == State.RECOVER_PLACE) {
            if (client.world == null || lastAnvilPos == null) {
                cancel();
                return;
            }
            BlockPos below = lastAnvilPos.down();
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(below).add(0, 0.5, 0),
                    Direction.UP, below, false);
            manager.interactBlock(player, Hand.MAIN_HAND, hit);
            state = State.RECOVER_OPEN;
            return;
        }

        if (state == State.RECOVER_OPEN) {
            if (client.world == null || lastAnvilPos == null) {
                cancel();
                return;
            }
            if (!(client.world.getBlockState(lastAnvilPos).getBlock() instanceof AnvilBlock)) {
                // Placement did not go through yet (or failed) - try again a few times.
                recoverAttempts++;
                if (recoverAttempts > 5) {
                    showText("Couldn't replace the broken anvil", Colors.RED);
                    cancel();
                } else {
                    state = State.RECOVER_PLACE;
                }
                return;
            }

            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(lastAnvilPos), Direction.UP, lastAnvilPos, false);
            manager.interactBlock(player, Hand.MAIN_HAND, hit);

            recoverAttempts = 0;
            if (restoreHotbarSlot != null) {
                player.getInventory().selectedSlot = restoreHotbarSlot;
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(restoreHotbarSlot));
                }
                restoreHotbarSlot = null;
            }
            // Go back to EXEC - once the screen actually re-opens, setup() will call
            // start() automatically to resume exactly where we left off.
            state = State.EXEC;
        }
    }

    private static void showText(String text, int color) {
        textDisplay.setMessage(Text.literal(text).setStyle(Style.EMPTY.withColor(color)));
        textDisplay.visible = true;
    }

    private static void hideText() {
        textDisplay.visible = false;
    }

    public static Shape getShape() {
        if (state != State.CALCULATE || current == null) return null;
        return current.current();
    }

    public static double getProgress() {
        if (state != State.CALCULATE) return -1;
        if (current == null) return 0;
        return (double) current.index() / current.total();
    }

    public static State getState() {
        return state;
    }

    public enum State {UNSET, SELECT, CALCULATE, EXEC, RECOVER_SWITCH, RECOVER_PLACE, RECOVER_OPEN, LOOP_WAIT}

    public static List<Integer> getSelected() {
        if (state != State.SELECT) return Collections.emptyList();
        return selected;
    }

    public static void toggleSelection(int slot) {
        if (selected.contains(slot)) selected.remove((Object) slot);
        else selected.add(slot);
    }
}
