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

    // ==== NEW: fully-automatic mode ====
    // When on (default), no manual "Select items" step is needed: the mod scans the
    // hotbar itself every cycle - one non-book item is treated as the target tool,
    // every enchanted book found is treated as fodder - then enchants automatically
    // and loops forever. Turn off with /autoenchanter loop off to go back to the
    // original manual Select -> Calculate -> Start workflow.
    private static boolean loopEnabled = true;
    private static int scanTimer = 0;

    // NEW: only this exact item is ever picked up as the enchant target - every
    // other non-book item on the hotbar is left completely untouched.
    private static final net.minecraft.item.Item TARGET_ITEM = Items.DIAMOND_PICKAXE;

    // ==== NEW: anvil auto-replace bookkeeping ====
    // The anvil itself is expected to be held in the off-hand at all times.
    public static BlockPos lastAnvilPos = null;
    private static int recoverTimer = 0;
    private static int recoverAttempts = 0;

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

        } else if (state == State.EXEC || state == State.RECOVER_PLACE || state == State.RECOVER_OPEN || state == State.AUTO_SCAN) {
            // The anvil screen was re-opened while we were still in the middle of an
            // enchanting run (e.g. the anvil broke and we just replaced it, or the
            // player simply re-opened it manually). Resume automatically instead of
            // requiring the user to press anything again.
            showText("Resuming...", Colors.GREEN);
            buttonSelect.visible = false;
            buttonCalculate.visible = false;
            buttonCancel.visible = true;
            buttonStart.visible = false;
            if (state == State.EXEC) start();

        } else if (readyToEnchant) {
            ready();

        } else if (loopEnabled) {
            // Brand new anvil session, fully-automatic mode: skip the manual
            // Select/Calculate step entirely and start scanning the hotbar.
            buttonSelect.visible = false;
            buttonCalculate.visible = false;
            buttonStart.visible = false;
            buttonCancel.visible = true;
            showText("Scanning hotbar...", Colors.BLUE);
            state = State.AUTO_SCAN;
            scanTimer = 0;

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

        // With looping enabled we never wait for the manual "Start enchanting"
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

    // /autoenchanter loop <on|off> - toggles fully-automatic hotbar-scan mode
    public static int loopCommand(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        loopEnabled = enabled;
        context.getSource().sendFeedback(Text.of("Auto mode is now " + (enabled ? "ON" : "OFF")));
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
        recoverTimer = 0;
        recoverAttempts = 0;
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
                || state == State.RECOVER_PLACE || state == State.RECOVER_OPEN
                || state == State.AUTO_SCAN) return;
        cancel();
    }

    public static void tick() {
        if (state == State.UNSET || state == State.SELECT) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerInteractionManager manager = client.interactionManager;
        ClientPlayerEntity player = client.player;
        if (player == null || manager == null) return;

        // NEW: handle the "anvil broke mid-enchant" auto-replace routine
        if (state == State.RECOVER_PLACE || state == State.RECOVER_OPEN) {
            handleRecovery(client, manager, player);
            return;
        }

        // NEW: fully-automatic hotbar scanning (no manual Select needed)
        if (state == State.AUTO_SCAN) {
            handleAutoScan(client, player);
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
                if (loopEnabled) {
                    beginAutoScan("Couldn't find a match, waiting...");
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
                    // NEW: the tool is now fully enchanted - throw it out into the
                    // world instead of moving it into the inventory.
                    dropFinishedItem(handler, manager, player);
                } else {
                    manager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
                }

                timer = -10;
                opid++;
                sopid = 0;
                if (opid == operations.size()) {
                    if (loopEnabled) {
                        beginAutoScan("Done, scanning for the next tool...");
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
     * Picks up the finished (fully enchanted) item from the anvil's result slot
     * and throws it straight into the world, so it can fall onto a hopper/
     * collection system, and the hotbar slot is freed for the next tool.
     */
    private static void dropFinishedItem(ScreenHandler handler, ClientPlayerInteractionManager manager, ClientPlayerEntity player) {
        manager.clickSlot(handler.syncId, 2, 0, SlotActionType.PICKUP, player);
        if (!handler.getCursorStack().isEmpty()) {
            manager.clickSlot(handler.syncId, -999, 0, SlotActionType.PICKUP, player);
        }
    }

    // ==================== NEW: fully-automatic hotbar scanning ====================

    /**
     * Resets the working state and goes back to scanning the hotbar for a target
     * tool + enchant books, used both for the very first cycle and for looping
     * back after a batch finishes (or fails to find a valid combination).
     */
    private static void beginAutoScan(String message) {
        search = null;
        current = null;
        operations = null;
        readyToEnchant = false;
        opid = 0;
        sopid = 0;
        selected.clear();
        state = State.AUTO_SCAN;
        scanTimer = 0;
        if (message != null) showText(message, Colors.BLUE);
    }

    /**
     * Every ~0.5s, looks at the 9 hotbar slots: any enchanted book is treated as
     * fodder, the first non-book/non-empty item found is treated as the target
     * tool. Once both are present, hands the slots over to calculate() exactly
     * like a manual selection would.
     */
    private static void handleAutoScan(MinecraftClient client, ClientPlayerEntity player) {
        if (scanTimer < 10) {
            scanTimer++;
            return;
        }
        scanTimer = 0;

        if (!(client.currentScreen instanceof AnvilScreen)) return;

        ScreenHandler handler = player.currentScreenHandler;
        int hotbarStart = handler.slots.size() - 9;

        int targetSlot = -1;
        List<Integer> bookSlots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int slot = hotbarStart + i;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.ENCHANTED_BOOK) {
                bookSlots.add(slot);
            } else if (stack.getItem() == TARGET_ITEM && targetSlot == -1) {
                targetSlot = slot;
            }
            // Anything else on the hotbar (not the diamond pickaxe, not a book) is
            // ignored entirely - never picked up as target or used as fodder.
        }

        if (targetSlot == -1 || bookSlots.isEmpty()) {
            showText("Waiting for a diamond pickaxe + enchant books on the hotbar...", Colors.YELLOW);
            return;
        }

        selected.clear();
        selected.add(targetSlot);
        selected.addAll(bookSlots);

        state = State.UNSET;
        calculate();
        if (state == State.UNSET) {
            // calculate() rejected the current combo (conflicts/wasted/etc) - keep scanning.
            state = State.AUTO_SCAN;
        }
    }

    // ==================== NEW: anvil auto-replace (held in off-hand) ====================

    /**
     * Called right when the anvil screen unexpectedly disappears while we were
     * mid-enchant. If the last known anvil block is now air, we assume it broke
     * and start the auto-replace routine (using the anvil held in the off-hand);
     * otherwise we assume the player simply closed the screen and do nothing.
     */
    private static void beginRecovery(MinecraftClient client, ClientPlayerEntity player) {
        if (lastAnvilPos == null || client.world == null) return;
        if (!client.world.getBlockState(lastAnvilPos).isAir()) return;

        ItemStack offhand = player.getOffHandStack();
        if (offhand.isEmpty() || !(offhand.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof AnvilBlock)) {
            showText("No anvil in your off-hand!", Colors.RED);
            cancel();
            return;
        }

        state = State.RECOVER_PLACE;
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

        if (state == State.RECOVER_PLACE) {
            if (client.world == null || lastAnvilPos == null) {
                cancel();
                return;
            }
            BlockPos below = lastAnvilPos.down();
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(below).add(0, 0.5, 0),
                    Direction.UP, below, false);
            // Placed using the off-hand, since that's where the anvil is held.
            manager.interactBlock(player, Hand.OFF_HAND, hit);
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

    public enum State {UNSET, SELECT, CALCULATE, EXEC, RECOVER_PLACE, RECOVER_OPEN, AUTO_SCAN}

    public static List<Integer> getSelected() {
        return selected;
    }

    public static void toggleSelection(int slot) {
        if (selected.contains(slot)) selected.remove((Object) slot);
        else selected.add(slot);
    }
}
