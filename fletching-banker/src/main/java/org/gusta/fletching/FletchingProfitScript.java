package org.gusta.fletching;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.paint.PaintContext;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

@ScriptManifest(name = "Fletching Profit", gameType = GameType.OS)
public class FletchingProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.2.6-watchdog";
    private static final Tile GRAND_EXCHANGE_TILE = new Tile(3164, 3487, 0);
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final int RESTOCK_TARGET_ACTIONS = 700;
    private static final int MIN_OUTPUTS_TO_SELL = 400;
    private static final int MIN_COINS_RESERVE = 5_000;
    private static final long RECIPE_REFRESH_MS = 4 * 60_000L;
    private static final double BUY_MARKUP = 1.10D;
    private static final double SELL_MARKDOWN = 0.98D;
    private static final double GE_TAX_RATE = 0.02D;
    private static final long WATCHDOG_GRACE_MS = 90_000L;
    private static final long WATCHDOG_LOOP_MS = 120_000L;
    private static final long WATCHDOG_INACTIVITY_MS = 480_000L;
    private static final int WATCHDOG_LOOP_SAMPLE_THRESHOLD = 18;
    private static final int WATCHDOG_HISTORY_LIMIT = 90;
    private static final String COINS = "Coins";
    private static final String BOW_STRING = "Bow string";
    private static final String[] CUTTING_TOOLS = {"Fletching knife", "Knife"};
    private static final int MAKE_INTERFACE_GROUP = 270;
    private static final int MAKE_PRIMARY_ITEM_CHILD = 15;

    private final Queue<GeAction> pendingGeActions = new ArrayDeque<>();
    private final List<GeAction> placedGeActions = new ArrayList<>();
    private final Map<String, Integer> lastInventoryUseIndexByName = new HashMap<>();
    private final Pricing pricing = new Pricing();
    private final Watchdog watchdog = new Watchdog();

    private Stats stats;
    private FletchingRecipe activeRecipe;
    private Quote activeQuote;
    private long nextRecipeRefreshAt;
    private long nextGeCollectAt;
    private long nextIdleLogAt;
    private long nextMakeWidgetDebugAt;
    private boolean stoppedForNoProfit;

    @Override
    public boolean onStart(String... args) {
        stats = new Stats();
        watchdog.reset();
        addTask(new FletchingTask());
        log("Fletching Profit " + SCRIPT_VERSION + " started");
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (event == null || event.getMessage() == null || stats == null) {
            return;
        }
        String message = event.getMessage();
        stats.lastChat = message;
        watchdog.note("CHAT " + message);
        String lower = message.toLowerCase();
        if (lower.contains("you do not have enough")
                || lower.contains("not enough")
                || lower.contains("you can't")
                || lower.contains("nothing interesting happens")) {
            log("Game message: " + message);
        }
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        if (paint == null || stats == null) {
            return;
        }
        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 8;
        int width = 330;
        int height = 228;
        paint.fill(new Rectangle(x, y, width, height), new Color(18, 22, 28, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("Fletching Profit " + SCRIPT_VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status, 42), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Recipe: " + (activeRecipe == null ? "-" : activeRecipe.label), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Level: " + fletchingLevel(ctx) + " | XP: " + stats.xpGained(ctx)
                + " (" + stats.xpPerHour(ctx) + "/h)", x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Profit/bow: " + (activeQuote == null ? "-" : activeQuote.profitPerAction + " gp")
                + " | est/h: " + (activeQuote == null ? "-" : activeQuote.profitPerHour + " gp"),
                x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Actions: " + stats.processedActions + " | GE queued: "
                + pendingGeActions.size() + " | placed: " + placedGeActions.size(),
                x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Last GE: " + shortText(stats.lastGeAction, 43), x + 12, line, new Color(245, 228, 160), 11);
        line += 16;
        paint.drawText("Last chat: " + shortText(stats.lastChat, 43), x + 12, line, new Color(195, 210, 230), 11);
    }

    @Override
    protected void onStop() {
        clearClientInteractionState();
        getLogger().info("Fletching Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        clearClientInteractionState();
    }

    private class FletchingTask implements ScriptTask {
        @Override
        public boolean shouldExecute() {
            return true;
        }

        @Override
        public void run() {
            APIContext ctx = getAPIContext();
            if (ctx == null) {
                Time.sleep(600, 900);
                return;
            }

            if (!watchdog.guard(ctx)) {
                return;
            }

            stats.startExperienceIfNeeded(ctx);

            if (!ensureAtGrandExchangeBeforeAnyAction(ctx)) {
                return;
            }

            if (!pendingGeActions.isEmpty() || !placedGeActions.isEmpty()) {
                handleGrandExchange(ctx);
                return;
            }

            if (ctx.grandExchange().isOpen()) {
                stats.setStatus("Closing GE before banking/fletching");
                ctx.grandExchange().close();
                Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
                return;
            }

            if (!selectRecipe(ctx)) {
                return;
            }

            if (hasProcessInventory(ctx, activeRecipe)) {
                processInventory(ctx, activeRecipe);
                return;
            }

            prepareInventoryOrRestock(ctx, activeRecipe);
        }
    }

    private boolean selectRecipe(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (activeRecipe != null && activeQuote != null && fletchingLevel(ctx) >= activeRecipe.level) {
            return true;
        }

        List<Quote> quotes = new ArrayList<>();
        int level = fletchingLevel(ctx);
        for (FletchingRecipe recipe : FletchingRecipe.all()) {
            if (level < recipe.level) {
                continue;
            }
            Quote quote = pricing.quote(ctx, recipe);
            if (quote.hasPrices()) {
                quotes.add(quote);
            }
        }

        Quote bestProfitable = quotes.stream()
                .filter(quote -> quote.profitPerAction > 0)
                .max(Comparator.comparingLong(quote -> quote.profitPerHour))
                .orElse(null);

        Quote best = bestProfitable != null
                ? bestProfitable
                : quotes.stream()
                .max(Comparator
                        .comparingLong((Quote quote) -> quote.profitPerAction)
                        .thenComparingLong(quote -> -quote.costPerAction))
                .orElse(null);

        if (best == null) {
            stoppedForNoProfit = true;
            activeRecipe = null;
            activeQuote = null;
            stats.setStatus("No priced Fletching recipe found for current level");
            logOccasionally("No priced Fletching recipe available at level " + level
                    + ". Waiting before checking prices again.");
            Time.sleep(2500, 4000);
            nextRecipeRefreshAt = now + RECIPE_REFRESH_MS;
            return false;
        }

        stoppedForNoProfit = !best.profitable();
        activeRecipe = best.recipe;
        activeQuote = best;
        nextRecipeRefreshAt = now + RECIPE_REFRESH_MS;
        log((best.profitable() ? "Selected profitable recipe: " : "Selected cheapest-loss recipe: ")
                + activeRecipe.label
                + " profit/bow=" + activeQuote.profitPerAction
                + " est/h=" + activeQuote.profitPerHour);
        return true;
    }

    private void prepareInventoryOrRestock(APIContext ctx, FletchingRecipe recipe) {
        if (!openBank(ctx, "preparing " + recipe.label)) {
            return;
        }

        if (depositInventoryIfNeeded(ctx, recipe)) {
            return;
        }

        if (shouldSellOutput(ctx, recipe)) {
            prepareOutputSale(ctx, recipe);
            return;
        }

        if (inventoryPartialForStringing(ctx, recipe)
                && hasStringingMaterialsAvailable(ctx, recipe)
                && prepareStringingInventory(ctx, recipe)) {
            return;
        }

        if (inventoryPartialForCutting(ctx, recipe)
                && hasLogsAvailable(ctx, recipe)
                && prepareCuttingInventory(ctx, recipe)) {
            return;
        }

        if (hasLogsAvailable(ctx, recipe) && prepareCuttingInventory(ctx, recipe)) {
            return;
        }

        if (hasStringingMaterialsAvailable(ctx, recipe) && prepareStringingInventory(ctx, recipe)) {
            return;
        }

        planRestock(ctx, recipe);
    }

    private boolean depositInventoryIfNeeded(APIContext ctx, FletchingRecipe recipe) {
        if (ctx.inventory().isEmpty()) {
            return false;
        }

        if (ctx.inventory().contains(recipe.output)) {
            stats.setStatus("Depositing finished bows");
            ctx.bank().depositAllExcept(CUTTING_TOOLS);
            Time.sleep(650, 1000);
            return true;
        }

        if (inventoryReadyForCutting(ctx, recipe)
                || inventoryReadyForStringing(ctx, recipe)
                || inventoryPartialForCutting(ctx, recipe)
                || inventoryPartialForStringing(ctx, recipe)) {
            return false;
        }

        if (inventoryOnlyContains(ctx, CUTTING_TOOLS)) {
            return false;
        }

        stats.setStatus("Depositing intermediate/extra items");
        ctx.bank().depositAllExcept(CUTTING_TOOLS);
        Time.sleep(650, 1000);
        return true;
    }

    private boolean prepareCuttingInventory(APIContext ctx, FletchingRecipe recipe) {
        if (!hasAnyCuttingTool(ctx) && ctx.inventory().getEmptySlotCount() <= 0) {
            stats.setStatus("Making room for a cutting tool");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return true;
        }

        if (!ensureCuttingTool(ctx)) {
            return true;
        }

        int bankLogs = ctx.bank().getCount(recipe.logs);
        int inventoryLogs = ctx.inventory().getCount(recipe.logs);
        if (bankLogs + inventoryLogs <= 0) {
            return false;
        }

        if (!inventoryOnlyContains(ctx, recipe.logs, CUTTING_TOOLS[0], CUTTING_TOOLS[1])) {
            stats.setStatus("Clearing inventory for cutting");
            ctx.bank().depositAllExcept(CUTTING_TOOLS);
            Time.sleep(600, 900);
            return true;
        }

        if (inventoryLogs > 0) {
            closeBank(ctx, "Ready to cut " + recipe.unstrung);
            return true;
        }

        int toWithdraw = Math.min(ctx.inventory().getEmptySlotCount(), bankLogs);
        if (toWithdraw <= 0) {
            return false;
        }

        stats.setStatus("Withdrawing " + toWithdraw + "x " + recipe.logs);
        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        if (ctx.bank().withdraw(toWithdraw, recipe.logs)
                || (toWithdraw == bankLogs && ctx.bank().withdrawAll(recipe.logs))) {
            Time.sleep(600, 1000, () -> ctx.inventory().contains(recipe.logs), 100);
        }
        closeBank(ctx, "Ready to cut " + recipe.unstrung);
        return true;
    }

    private boolean prepareStringingInventory(APIContext ctx, FletchingRecipe recipe) {
        int bankUnstrung = ctx.bank().getCount(recipe.unstrung);
        int bankStrings = ctx.bank().getCount(BOW_STRING);
        int invUnstrung = ctx.inventory().getCount(recipe.unstrung);
        int invStrings = ctx.inventory().getCount(BOW_STRING);
        int availableActions = Math.min(bankUnstrung + invUnstrung, bankStrings + invStrings);

        if (availableActions <= 0) {
            return false;
        }

        if (!inventoryOnlyContains(ctx, recipe.unstrung, BOW_STRING)) {
            stats.setStatus("Clearing inventory for stringing");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return true;
        }

        int target = Math.min(14, Math.min(bankUnstrung + invUnstrung, bankStrings + invStrings));
        if (invUnstrung > target || invStrings > target) {
            stats.setStatus("Normalising stringing inventory");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return true;
        }

        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        if (invUnstrung < target) {
            int toWithdraw = Math.min(target - invUnstrung, bankUnstrung);
            stats.setStatus("Withdrawing " + toWithdraw + "x " + recipe.unstrung);
            ctx.bank().withdraw(toWithdraw, recipe.unstrung);
            Time.sleep(500, 800);
            return true;
        }
        if (invStrings < target) {
            int toWithdraw = Math.min(target - invStrings, bankStrings);
            stats.setStatus("Withdrawing " + toWithdraw + "x " + BOW_STRING);
            ctx.bank().withdraw(toWithdraw, BOW_STRING);
            Time.sleep(500, 800);
            return true;
        }

        closeBank(ctx, "Ready to string " + recipe.label);
        return true;
    }

    private void planRestock(APIContext ctx, FletchingRecipe recipe) {
        if (activeQuote == null || !activeQuote.hasPrices()) {
            activeRecipe = null;
            nextRecipeRefreshAt = 0L;
            stats.setStatus("Recipe no longer priced; refreshing selection");
            return;
        }

        if (shouldSellOutput(ctx, recipe)) {
            prepareOutputSale(ctx, recipe);
            return;
        }

        long coins = ctx.inventory().getCount(true, COINS) + ctx.bank().getCount(COINS);
        RestockPlan restockPlan = createRestockPlan(ctx, recipe, Math.max(0L, coins - MIN_COINS_RESERVE));
        if (restockPlan.isEmpty()) {
            stats.setStatus("Not enough coins for " + recipe.label
                    + "; need products sold or more cash");
            logOccasionally("Not enough coins to restock " + recipe.label
                    + ". Coins=" + coins + " cost/action=" + activeQuote.costPerAction);
            Time.sleep(1800, 2800);
            return;
        }

        enqueueRestock(recipe, restockPlan);
        closeBank(ctx, "Going to GE for " + recipe.label + " restock");
    }

    private RestockPlan createRestockPlan(APIContext ctx, FletchingRecipe recipe, long availableCoins) {
        int targetActions = RESTOCK_TARGET_ACTIONS;
        RestockPlan plan = restockPlanForTarget(ctx, recipe, targetActions);
        while (plan.cost > availableCoins && targetActions > 0) {
            targetActions = Math.max(0, (int) Math.floor(targetActions * 0.8D));
            plan = restockPlanForTarget(ctx, recipe, targetActions);
        }
        return plan;
    }

    private RestockPlan restockPlanForTarget(APIContext ctx, FletchingRecipe recipe, int targetActions) {
        int logsAvailable = ctx.inventory().getCount(recipe.logs)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(recipe.logs) : 0);
        int unstrungAvailable = ctx.inventory().getCount(recipe.unstrung)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(recipe.unstrung) : 0);
        int stringsAvailable = ctx.inventory().getCount(BOW_STRING)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(BOW_STRING) : 0);
        int logsToBuy = Math.max(0, targetActions - logsAvailable - unstrungAvailable);
        int stringsToBuy = Math.max(0, targetActions - stringsAvailable);

        long cost = (long) logsToBuy * activeQuote.primaryBuyPrice
                + (long) stringsToBuy * activeQuote.secondaryBuyPrice;
        return new RestockPlan(logsToBuy, stringsToBuy, cost);
    }

    private void enqueueRestock(FletchingRecipe recipe, RestockPlan restockPlan) {
        if (restockPlan.primaryQuantity > 0) {
            int logsPrice = pricing.quickBuyPrice(recipe.logs, activeQuote.primaryBuyPrice);
            pendingGeActions.add(GeAction.buy(recipe.logs, restockPlan.primaryQuantity, logsPrice));
        }
        if (restockPlan.secondaryQuantity > 0) {
            int stringPrice = pricing.quickBuyPrice(BOW_STRING, activeQuote.secondaryBuyPrice);
            pendingGeActions.add(GeAction.buy(BOW_STRING, restockPlan.secondaryQuantity, stringPrice));
        }
        stats.lastGeAction = "Queued restock " + restockPlan.actionsText() + " for " + recipe.label;
        log(stats.lastGeAction);
    }

    private boolean shouldSellOutput(APIContext ctx, FletchingRecipe recipe) {
        int inventoryOutput = ctx.inventory().getCount(true, recipe.output);
        int bankOutput = ctx.bank().isOpen() ? ctx.bank().getCount(recipe.output) : 0;
        int totalOutput = inventoryOutput + bankOutput;
        if (totalOutput <= 0) {
            return false;
        }

        boolean hasRemainingMaterials = hasLogsAvailable(ctx, recipe) || hasStringingMaterialsAvailable(ctx, recipe);
        if (hasRemainingMaterials) {
            return false;
        }

        int coins = ctx.inventory().getCount(true, COINS)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(COINS) : 0);
        return totalOutput >= MIN_OUTPUTS_TO_SELL || coins < MIN_COINS_RESERVE || totalOutput > 0;
    }

    private void prepareOutputSale(APIContext ctx, FletchingRecipe recipe) {
        int inventoryOutput = ctx.inventory().getCount(true, recipe.output);
        if (inventoryOutput <= 0) {
            stats.setStatus("Withdrawing " + recipe.output + " as notes to sell");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            if (ctx.bank().withdrawAll(recipe.output)
                    || ctx.bank().withdraw(Math.max(1, ctx.bank().getCount(recipe.output)), recipe.output)) {
                Time.sleep(700, 1100, () -> ctx.inventory().contains(recipe.output), 100);
            }
            inventoryOutput = ctx.inventory().getCount(true, recipe.output);
        }

        if (inventoryOutput <= 0) {
            stats.setStatus("Wanted to sell output, but no " + recipe.output + " was found");
            return;
        }

        int price = activeQuote == null
                ? pricing.quickSellPrice(recipe.output, 1)
                : pricing.quickSellPrice(recipe.output, activeQuote.outputSellPrice);
        pendingGeActions.add(GeAction.sell(recipe.output, inventoryOutput, price));
        stats.lastGeAction = "Queued sale " + inventoryOutput + "x " + recipe.output;
        log(stats.lastGeAction);
        closeBank(ctx, "Going to GE to sell " + recipe.output);
    }

    private boolean ensureCuttingTool(APIContext ctx) {
        if (hasAnyCuttingTool(ctx)) {
            return true;
        }

        for (String tool : CUTTING_TOOLS) {
            if (ctx.bank().contains(tool) || ctx.bank().getItem(tool) != null) {
                stats.setStatus("Withdrawing " + tool);
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                if (ctx.bank().withdraw(1, tool) || ctx.bank().withdrawAny(1, tool)) {
                    Time.sleep(600, 900);
                    return true;
                }
            }
        }

        long coins = ctx.inventory().getCount(true, COINS) + ctx.bank().getCount(COINS);
        if (coins > 100) {
            pendingGeActions.add(GeAction.buy("Knife", 1, pricing.quickBuyPrice("Knife", 30)));
            closeBank(ctx, "Buying Knife for Fletching");
            return false;
        }

        stats.setStatus("Missing Knife/Fletching knife and not enough coins to buy one");
        Time.sleep(1600, 2400);
        return false;
    }

    private void processInventory(APIContext ctx, FletchingRecipe recipe) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting for current Fletching action");
            Time.sleep(600, 1000);
            return;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(300, 600, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
            return;
        }

        WorkStage stage = nextInventoryStage(ctx, recipe);
        if (stage == WorkStage.NONE) {
            return;
        }

        int beforeActions = inventoryActionCount(ctx, recipe, stage);
        String output = stageOutput(recipe, stage);
        int beforeOutput = ctx.inventory().getCount(true, output);
        if (beforeActions <= 0) {
            return;
        }

        stats.setStatus("Starting " + stageLabel(stage) + " batch for "
                + recipe.label + " (" + beforeActions + " actions)");
        boolean used = stage == WorkStage.CUT
                ? useToolOnInput(ctx, recipe)
                : useUnstrungOnString(ctx, recipe);

        if (!used) {
            stats.setStatus("Could not use inputs for " + recipe.label);
            Time.sleep(800, 1300);
            return;
        }

        Time.sleep(600, 1400,
                () -> makeInterfaceOpen(ctx)
                        || ctx.localPlayer().isAnimating()
                        || inventoryActionCount(ctx, recipe, stage) < beforeActions
                        || ctx.inventory().getCount(true, output) > beforeOutput,
                100);

        if (makeInterfaceOpen(ctx) && !clickMakeTarget(ctx, recipe, stage, beforeActions, beforeOutput)) {
            stats.setStatus("Creation menu open, but target not found: " + output);
            Time.sleep(1000, 1600);
            return;
        }

        Time.sleep(900, 2200,
                () -> ctx.localPlayer().isAnimating()
                        || inventoryActionCount(ctx, recipe, stage) < beforeActions
                        || ctx.inventory().getCount(true, output) > beforeOutput,
                100);

        waitForBatchToFinish(ctx, recipe, stage, beforeActions);
        int afterActions = inventoryActionCount(ctx, recipe, stage);
        int processed = Math.max(0, beforeActions - afterActions);
        if (processed > 0) {
            stats.processedActions += processed;
            stats.lastProcessedAt = System.currentTimeMillis();
        }
    }

    private void waitForBatchToFinish(APIContext ctx, FletchingRecipe recipe, WorkStage stage, int beforeActions) {
        long deadline = System.currentTimeMillis() + (stage == WorkStage.CUT ? 65_000L : 35_000L);
        int lastActionCount = beforeActions;
        long lastProgressAt = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            int actions = inventoryActionCount(ctx, recipe, stage);
            if (actions <= 0) {
                stats.setStatus("Batch complete: " + stageLabel(stage) + " " + recipe.label);
                return;
            }
            if (actions < lastActionCount) {
                lastActionCount = actions;
                lastProgressAt = System.currentTimeMillis();
                stats.setStatus(stageLabel(stage) + " " + recipe.label + " (" + actions + " left)");
            }
            if (!ctx.localPlayer().isAnimating() && System.currentTimeMillis() - lastProgressAt > 4_000L) {
                return;
            }
            Time.sleep(450, 750);
        }
    }

    private boolean useToolOnInput(APIContext ctx, FletchingRecipe recipe) {
        String tool = bestCuttingTool(ctx);
        if (tool == null) {
            return false;
        }
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 75) {
            return selectInventoryItemForUse(ctx, tool)
                    && useSelectedItemOnInventoryItem(ctx, recipe.logs);
        }
        return selectInventoryItemForUse(ctx, recipe.logs)
                && useSelectedItemOnInventoryItem(ctx, tool);
    }

    private boolean useUnstrungOnString(APIContext ctx, FletchingRecipe recipe) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 65) {
            return selectInventoryItemForUse(ctx, recipe.unstrung)
                    && useSelectedItemOnInventoryItem(ctx, BOW_STRING);
        }
        return selectInventoryItemForUse(ctx, BOW_STRING)
                && useSelectedItemOnInventoryItem(ctx, recipe.unstrung);
    }

    private boolean selectInventoryItemForUse(APIContext ctx, String itemName) {
        clearInventoryInteractionState(ctx);

        ItemWidget item = randomInventoryItem(ctx, itemName);
        if (item != null) {
            boolean interacted = item.interact("Use", itemName) || item.interact("Use");
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
            if (ctx.inventory().isItemSelected()) {
                return true;
            }
            if (ctx.menu().isOpen() && selectUseFromOpenMenu(ctx, itemName)) {
                return true;
            }

            Point point = inventoryItemPoint(item);
            boolean clicked = point != null && ctx.mouse().click(point, false);
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
            if (ctx.menu().isOpen()) {
                return selectUseFromOpenMenu(ctx, itemName);
            }
            if (clicked && ctx.inventory().isItemSelected()) {
                return true;
            }
            if (interacted && ctx.inventory().isItemSelected()) {
                return true;
            }
        }

        if (ctx.inventory().selectItem(itemName)) {
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        }
        return ctx.inventory().isItemSelected();
    }

    private boolean useSelectedItemOnInventoryItem(APIContext ctx, String itemName) {
        if (!ctx.inventory().isItemSelected()) {
            return false;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }

        ItemWidget item = randomInventoryItem(ctx, itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemPoint(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(350, 1000, () -> !ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return clickUseFromOpenMenu(ctx, itemName);
        }
        return clicked;
    }

    private boolean selectUseFromOpenMenu(APIContext ctx, String itemName) {
        boolean clicked = clickUseFromOpenMenu(ctx, itemName);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || !ctx.menu().isOpen(), 50);
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean clickUseFromOpenMenu(APIContext ctx, String itemName) {
        if (!ctx.menu().isOpen()) {
            return false;
        }

        boolean clicked = false;
        if (ctx.menu().contains("Use", itemName)) {
            clicked = ctx.menu().interact("Use", itemName, true);
        }
        if (!clicked && ctx.menu().contains("Use")) {
            clicked = ctx.menu().interact("Use", true);
        }
        Time.sleep(250, 700, () -> !ctx.menu().isOpen(), 50);
        return clicked;
    }

    private void closeOpenMenu(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(120, 260, () -> !ctx.menu().isOpen(), 50);
        }
    }

    private ItemWidget randomInventoryItem(APIContext ctx, String itemName) {
        List<ItemWidget> candidates = new ArrayList<>();
        for (ItemWidget item : ctx.inventory().getItems(itemName)) {
            if (item != null && item.isValid()) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        String key = normalizedName(itemName);
        Integer lastIndex = lastInventoryUseIndexByName.get(key);
        if (lastIndex != null && candidates.size() > 1) {
            candidates.removeIf(item -> item.getIndex() == lastIndex);
        }

        ItemWidget selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        lastInventoryUseIndexByName.put(key, selected.getIndex());
        return selected;
    }

    private Point inventoryItemPoint(ItemWidget item) {
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        int marginX = Math.min(5, Math.max(0, bounds.width / 4));
        int marginY = Math.min(5, Math.max(0, bounds.height / 4));
        int minX = bounds.x + marginX;
        int maxX = Math.max(minX, bounds.x + bounds.width - marginX - 1);
        int minY = bounds.y + marginY;
        int maxY = Math.max(minY, bounds.y + bounds.height - marginY - 1);
        return new Point(
                ThreadLocalRandom.current().nextInt(minX, maxX + 1),
                ThreadLocalRandom.current().nextInt(minY, maxY + 1)
        );
    }

    private boolean clickMakeTarget(
            APIContext ctx,
            FletchingRecipe recipe,
            WorkStage stage,
            int beforeActions,
            int beforeOutput
    ) {
        String output = stageOutput(recipe, stage);
        WidgetChild target = findMakeWidget(ctx, recipe, stage);
        if (target == null) {
            logMakeWidgetFailure(ctx, recipe, stage, output);
            return false;
        }

        stats.setStatus("Selecting creation target: " + output);
        clickMakeAllQuantity(ctx);
        if (clickDirectMakeWidget(ctx, recipe, stage, beforeActions, beforeOutput)) {
            return true;
        }

        for (String action : makeActions(target)) {
            if (target.interact(action, output)
                    || target.interact(action)
                    || ctx.menu().interact(action, output, target, true)
                    || ctx.menu().interact(action, target, true)) {
                if (waitForCreationStart(ctx, recipe, stage, beforeActions, beforeOutput)) {
                    return true;
                }
                closeOpenMenu(ctx);
            }
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            Point point = randomWidgetPoint(target);
            if (point != null && ctx.mouse().click(point, false)
                    && waitForCreationStart(ctx, recipe, stage, beforeActions, beforeOutput)) {
                return true;
            }
            closeOpenMenu(ctx);
            Time.sleep(250, 450);
        }

        boolean clicked = target.click() && waitForCreationStart(ctx, recipe, stage, beforeActions, beforeOutput);
        if (!clicked) {
            logMakeClickFailure(ctx, recipe, stage, target);
        }
        return clicked;
    }

    private boolean clickDirectMakeWidget(
            APIContext ctx,
            FletchingRecipe recipe,
            WorkStage stage,
            int beforeActions,
            int beforeOutput
    ) {
        if (stage != WorkStage.STRING) {
            return false;
        }

        WidgetChild direct = ctx.widgets().get(MAKE_INTERFACE_GROUP, MAKE_PRIMARY_ITEM_CHILD);
        if (!isVisibleWidget(direct)) {
            return false;
        }

        int expectedItemId = stageOutputItemId(recipe, stage);
        if (direct.getItemId() > 0 && direct.getItemId() != expectedItemId) {
            log("Widget 270.15 visible, but item id is " + direct.getItemId()
                    + " instead of expected " + expectedItemId + " for " + recipe.label);
        }

        stats.setStatus("Clicking make widget 270.15 for " + recipe.label);
        for (int attempt = 0; attempt < 4; attempt++) {
            Point point = randomWidgetPoint(direct);
            if (point != null && ctx.mouse().click(point, false)
                    && waitForCreationStart(ctx, recipe, stage, beforeActions, beforeOutput)) {
                return true;
            }
            closeOpenMenu(ctx);
            Time.sleep(200, 380);
        }

        logMakeClickFailure(ctx, recipe, stage, direct);
        return false;
    }

    private boolean waitForCreationStart(
            APIContext ctx,
            FletchingRecipe recipe,
            WorkStage stage,
            int beforeActions,
            int beforeOutput
    ) {
        String output = stageOutput(recipe, stage);
        Time.sleep(650, 1300,
                () -> !makeInterfaceOpen(ctx)
                        || ctx.localPlayer().isAnimating()
                        || inventoryActionCount(ctx, recipe, stage) < beforeActions
                        || ctx.inventory().getCount(true, output) > beforeOutput,
                50);
        return !makeInterfaceOpen(ctx)
                || ctx.localPlayer().isAnimating()
                || inventoryActionCount(ctx, recipe, stage) < beforeActions
                || ctx.inventory().getCount(true, output) > beforeOutput;
    }

    private boolean clickMakeAllQuantity(APIContext ctx) {
        WidgetChild all = findMakeQuantityWidget(ctx, "All");
        if (all == null) {
            return false;
        }
        Point point = randomWidgetPoint(all);
        if (point != null && ctx.mouse().click(point, false)) {
            Time.sleep(180, 350);
            return true;
        }
        if (all.interact("Select") || all.interact("All") || all.click()) {
            Time.sleep(180, 350);
            return true;
        }
        return false;
    }

    private WidgetChild findMakeQuantityWidget(APIContext ctx, String quantityText) {
        String expected = normalizedName(quantityText);
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (!isInMakePanel(ctx, widget)) {
                continue;
            }
            String text = normalizedName(visibleText(widget));
            if (expected.equals(text) || text.endsWith(expected)) {
                return widget;
            }
        }
        return null;
    }

    private void logMakeClickFailure(APIContext ctx, FletchingRecipe recipe, WorkStage stage, WidgetChild target) {
        long now = System.currentTimeMillis();
        if (now < nextMakeWidgetDebugAt) {
            return;
        }

        log("Make target click did not start " + stageLabel(stage)
                + " for " + recipe.label
                + ". targetId=" + target.getItemId()
                + " bounds=" + target.getBounds()
                + " actions=" + target.getActions()
                + " text='" + cleanWidgetText(visibleText(target)) + "'"
                + " makeOpen=" + makeInterfaceOpen(ctx)
                + " animating=" + ctx.localPlayer().isAnimating()
                + " actionsLeft=" + inventoryActionCount(ctx, recipe, stage));
        nextMakeWidgetDebugAt = now + 8_000L;
    }

    private List<String> makeActions(WidgetChild widget) {
        List<String> actions = new ArrayList<>();
        for (String preferred : new String[]{"Make All", "Make-All", "Make 10", "Make-10", "Make X", "Make-X", "Make"}) {
            if (widget.hasAction(preferred)) {
                actions.add(preferred);
            }
        }
        for (String action : widget.getActions()) {
            if (action != null && action.toLowerCase().contains("make") && !actions.contains(action)) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            actions.add("Make");
        }
        return actions;
    }

    private WidgetChild findMakeWidget(APIContext ctx, FletchingRecipe recipe, WorkStage stage) {
        String outputName = stageOutput(recipe, stage);
        int outputItemId = stageOutputItemId(recipe, stage);
        WidgetChild direct = ctx.widgets().get(MAKE_INTERFACE_GROUP, MAKE_PRIMARY_ITEM_CHILD);
        if (stage == WorkStage.STRING
                && isVisibleWidget(direct)) {
            return direct;
        }

        List<WidgetChild> makeItemWidgets = makeInterfaceItemWidgets(ctx);
        if (outputItemId > 0) {
            for (WidgetChild widget : makeItemWidgets) {
                if (widget.getItemId() == outputItemId) {
                    return widget;
                }
            }
        }

        WidgetChild named = ctx.widgets()
                .query()
                .itemName(outputName)
                .results()
                .first();
        if (isVisibleWidget(named) && isInMakePanel(ctx, named)) {
            return named;
        }

        String output = normalizedName(outputName);
        String label = normalizedName(recipe.plainLabel);
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (!looksLikeMakeWidget(widget)) {
                continue;
            }
            String combined = normalizedName(widget.getName())
                    + " " + normalizedName(widget.getText())
                    + " " + normalizedName(widget.getRawText());
            if (widget.getItemId() == outputItemId
                    || combined.contains(output)
                    || (!label.isBlank() && combined.contains(label))) {
                return widget;
            }
        }

        WidgetChild positional = findMakeWidgetByPosition(ctx, recipe, stage, makeItemWidgets);
        if (positional != null) {
            return positional;
        }

        List<WidgetChild> makeWidgets = new ArrayList<>();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (looksLikeMakeWidget(widget)) {
                makeWidgets.add(widget);
            }
        }
        return makeWidgets.size() == 1 ? makeWidgets.get(0) : null;
    }

    private WidgetChild findMakeWidgetByPosition(
            APIContext ctx,
            FletchingRecipe recipe,
            WorkStage stage,
            List<WidgetChild> itemWidgets
    ) {
        if (itemWidgets.isEmpty()) {
            return null;
        }

        if (stage == WorkStage.STRING && itemWidgets.size() == 1) {
            return itemWidgets.get(0);
        }

        int index = stage == WorkStage.CUT ? recipe.cutOptionIndex() : 0;
        if (index >= 0 && index < itemWidgets.size()) {
            return itemWidgets.get(index);
        }
        return null;
    }

    private List<WidgetChild> makeInterfaceItemWidgets(APIContext ctx) {
        Rectangle makePanel = makePanelBounds(ctx);
        List<WidgetChild> widgets = new ArrayList<>();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (widget.getItemId() <= 0) {
                continue;
            }
            Point point = widget.getCentralPoint();
            if (point != null && makePanel.contains(point)) {
                widgets.add(widget);
            }
        }
        widgets.sort(Comparator
                .comparingInt((WidgetChild widget) -> widget.getBounds().y)
                .thenComparingInt(widget -> widget.getBounds().x));
        return widgets;
    }

    private boolean isInMakePanel(APIContext ctx, WidgetChild widget) {
        Point point = widget == null ? null : widget.getCentralPoint();
        return point != null && makePanelBounds(ctx).contains(point);
    }

    private Rectangle makePanelBounds(APIContext ctx) {
        int canvasWidth = Math.max(1, ctx.client().getCanvasWidth());
        int canvasHeight = Math.max(1, ctx.client().getCanvasHeight());
        return new Rectangle(0, Math.max(0, canvasHeight - 330), Math.min(canvasWidth, 580), 330);
    }

    private void logMakeWidgetFailure(APIContext ctx, FletchingRecipe recipe, WorkStage stage, String output) {
        long now = System.currentTimeMillis();
        if (now < nextMakeWidgetDebugAt) {
            return;
        }

        List<String> parts = new ArrayList<>();
        for (WidgetChild widget : makeInterfaceItemWidgets(ctx)) {
            parts.add("id=" + widget.getItemId()
                    + " bounds=" + widget.getBounds()
                    + " actions=" + widget.getActions()
                    + " text='" + cleanWidgetText(widget.getText()) + "'");
        }

        log("Make target not found for " + stageLabel(stage)
                + " output=" + output
                + " expectedId=" + stageOutputItemId(recipe, stage)
                + " optionIndex=" + (stage == WorkStage.CUT ? recipe.cutOptionIndex() : 0)
                + " makeItems=[" + String.join("; ", parts) + "]");
        nextMakeWidgetDebugAt = now + 8_000L;
    }

    private boolean looksLikeMakeWidget(WidgetChild widget) {
        if (widget == null) {
            return false;
        }
        for (String action : widget.getActions()) {
            if (action != null && action.toLowerCase().contains("make")) {
                return true;
            }
        }
        String text = visibleText(widget).toLowerCase();
        return text.contains("make") || text.contains("how many");
    }

    private boolean makeInterfaceOpen(APIContext ctx) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            if (looksLikeMakeWidget(widget)) {
                return true;
            }
        }
        return false;
    }

    private boolean ensureAtGrandExchangeBeforeAnyAction(APIContext ctx) {
        if (isAtGrandExchange(ctx)) {
            return true;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting for ROW teleport to Grand Exchange");
            Time.sleep(900, 1400, () -> isAtGrandExchange(ctx), 100);
            return false;
        }

        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before ROW teleport");
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }

        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before ROW teleport");
            ctx.bank().close();
            Time.sleep(600, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        if (makeInterfaceOpen(ctx)) {
            stats.setStatus("Closing make interface before ROW teleport");
            ctx.widgets().closeInterface();
            Time.sleep(500, 900, () -> !makeInterfaceOpen(ctx), 100);
            return false;
        }

        if (clickGrandExchangeTeleportOption(ctx)) {
            return false;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.EQUIPMENT)) {
            stats.setStatus("Opening equipment tab for ROW teleport");
            ctx.tabs().open(ITabsAPI.Tabs.EQUIPMENT);
            Time.sleep(350, 700, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.EQUIPMENT), 100);
            return false;
        }

        ItemWidget ring = equippedRingOfWealth(ctx);
        if (ring == null || !ring.isValid()) {
            stats.setStatus("Not at GE and no equipped Ring of wealth found");
            logOccasionally("Script is gated at startup: equip a charged Ring of wealth to teleport to GE.");
            Time.sleep(1800, 2600);
            return false;
        }

        stats.setStatus("Teleporting to GE with equipped ROW");
        if (interactEquippedRowTeleport(ctx, ring)) {
            Time.sleep(3500, 6500,
                    () -> isAtGrandExchange(ctx)
                            || ctx.localPlayer().isMoving()
                            || ctx.localPlayer().isAnimating()
                            || clickGrandExchangeTeleportOption(ctx),
                    100);
            return false;
        }

        logOccasionally("Could not use equipped ROW teleport. Ring actions=" + ring.getActions());
        Time.sleep(900, 1400);
        return false;
    }

    private ItemWidget equippedRingOfWealth(APIContext ctx) {
        ItemWidget ringSlot = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (isRingOfWealth(ringSlot)) {
            return ringSlot;
        }
        return ctx.equipment().getItem(this::isRingOfWealth);
    }

    private boolean isRingOfWealth(Item item) {
        return item != null
                && item.isValid()
                && normalizedName(item.getName()).startsWith("ringofwealth");
    }

    private boolean interactEquippedRowTeleport(APIContext ctx, ItemWidget ring) {
        for (String action : rowGrandExchangeActions(ring)) {
            if (ring.interact(action, ring.getName()) || ring.interact(action)) {
                return true;
            }
        }
        if (ring.interact("Rub", ring.getName()) || ring.interact("Rub")) {
            Time.sleep(500, 900, () -> clickGrandExchangeTeleportOption(ctx), 100);
            return true;
        }
        return false;
    }

    private List<String> rowGrandExchangeActions(ItemWidget ring) {
        List<String> actions = new ArrayList<>();
        for (String preferred : new String[]{"Grand Exchange", "GE"}) {
            if (ring.hasAction(preferred)) {
                actions.add(preferred);
            }
        }
        for (String action : ring.getActions()) {
            String normalized = normalizedName(action);
            if ((normalized.contains("grandexchange")
                    || normalized.equals("ge")
                    || normalized.contains("exchange"))
                    && !actions.contains(action)) {
                actions.add(action);
            }
        }
        if (actions.isEmpty()) {
            actions.add("Grand Exchange");
            actions.add("GE");
        }
        return actions;
    }

    private boolean clickGrandExchangeTeleportOption(APIContext ctx) {
        WidgetChild option = findVisibleWidgetByText(ctx, "Grand Exchange");
        if (option == null) {
            return false;
        }
        stats.setStatus("Selecting ROW teleport option: Grand Exchange");
        if (clickWidgetCenter(ctx, option)
                || option.interact("Continue")
                || option.interact("Select")
                || option.click()) {
            Time.sleep(900, 1600);
            return true;
        }
        return false;
    }

    private void handleGrandExchange(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (!isAtGrandExchange(ctx)) {
            ensureAtGrandExchangeBeforeAnyAction(ctx);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening Grand Exchange");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmGeWarning(ctx)) {
            return;
        }

        if (!placedGeActions.isEmpty()) {
            handlePlacedGeActions(ctx);
            return;
        }

        GeAction action = pendingGeActions.poll();
        if (action == null) {
            stats.setStatus("Collecting any GE leftovers");
            try {
                ctx.grandExchange().collectToBank();
            } catch (RuntimeException ignored) {
                // Collection is harmless to retry.
            }
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        placeGeAction(ctx, action);
    }

    private void placeGeAction(APIContext ctx, GeAction action) {
        if (action.quantity <= 0) {
            return;
        }

        stats.lastGeAction = action.describe();
        stats.setStatus(action.describe());
        boolean placed;
        if (action.type == GeActionType.BUY) {
            placed = ctx.grandExchange().placeBuyOffer(action.itemName, action.quantity, action.price);
        } else {
            int inventoryCount = ctx.inventory().getCount(true, action.itemName);
            int quantity = Math.min(action.quantity, inventoryCount);
            if (quantity <= 0) {
                stats.setStatus("No inventory item to sell: " + action.itemName);
                Time.sleep(700, 1100);
                return;
            }
            placed = ctx.grandExchange().placeSellOffer(action.itemName, quantity, action.price);
        }

        Time.sleep(1000, 1500);
        if (!placed) {
            if (!confirmGeWarning(ctx)) {
                stats.setStatus("GE offer was not placed: " + action.describe());
                pendingGeActions.add(action);
                Time.sleep(1200, 1800);
            }
            return;
        }

        placedGeActions.add(action);
        nextGeCollectAt = System.currentTimeMillis() + 4_000L;
    }

    private void handlePlacedGeActions(APIContext ctx) {
        if (System.currentTimeMillis() < nextGeCollectAt) {
            stats.setStatus("Waiting for GE offer to fill");
            Time.sleep(800, 1200);
            return;
        }

        int waiting = 0;
        for (GeAction action : placedGeActions) {
            GrandExchangeSlot slot = findSlot(ctx, action);
            if (slot != null && !slot.isCompleted() && !slot.canCollect()) {
                waiting++;
            }
        }

        if (waiting > 0) {
            stats.setStatus("GE offer still pending (" + waiting + ")");
            nextGeCollectAt = System.currentTimeMillis() + 6_000L;
            Time.sleep(900, 1400);
            return;
        }

        stats.setStatus("Collecting completed GE offer(s) to bank");
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection is harmless to retry.
        }
        Time.sleep(900, 1400);
        boolean soldOutput = placedGeActions.stream().anyMatch(action -> action.type == GeActionType.SELL);
        placedGeActions.clear();
        if (soldOutput) {
            activeRecipe = null;
            activeQuote = null;
            nextRecipeRefreshAt = 0L;
            stats.setStatus("Finished sale cycle; refreshing profit selector");
        }
    }

    private GrandExchangeSlot findSlot(APIContext ctx, GeAction action) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (!namesMatch(offer.getItemName(), action.itemName)) {
                continue;
            }
            boolean buyState = slot.getState().name().contains("BUY") || slot.getState().name().contains("BOUGHT");
            boolean sellState = slot.getState().name().contains("SELL") || slot.getState().name().contains("SOLD");
            if ((action.type == GeActionType.BUY && buyState)
                    || (action.type == GeActionType.SELL && sellState)) {
                return slot;
            }
        }
        return null;
    }

    private boolean confirmGeWarning(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null) {
            return false;
        }

        String text = allWidgetText(ctx).toLowerCase();
        if (!text.contains("much higher") && !text.contains("are you sure")) {
            return false;
        }

        stats.setStatus("Confirming GE price warning");
        if (clickWidgetCenter(ctx, yes)
                || yes.interact("Continue")
                || yes.interact("Yes")
                || yes.click()) {
            Time.sleep(1000, 1500);
            return true;
        }
        Time.sleep(600, 900);
        return true;
    }

    private boolean openBank(APIContext ctx, String reason) {
        if (ctx.bank().isOpen()) {
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (!ctx.bank().isReachable()) {
            stats.setStatus("Walking to nearest bank: " + reason);
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkToBank();
            Time.sleep(1200, 1800);
            return false;
        }

        stats.setStatus("Opening bank: " + reason);
        ctx.bank().open();
        Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
        return ctx.bank().isOpen();
    }

    private void closeBank(APIContext ctx, String status) {
        stats.setStatus(status);
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
    }

    private boolean hasProcessInventory(APIContext ctx, FletchingRecipe recipe) {
        return nextInventoryStage(ctx, recipe) != WorkStage.NONE;
    }

    private WorkStage nextInventoryStage(APIContext ctx, FletchingRecipe recipe) {
        if (inventoryReadyForCutting(ctx, recipe)) {
            return WorkStage.CUT;
        }
        if (inventoryReadyForStringing(ctx, recipe)) {
            return WorkStage.STRING;
        }
        return WorkStage.NONE;
    }

    private boolean inventoryReadyForCutting(APIContext ctx, FletchingRecipe recipe) {
        return hasAnyCuttingTool(ctx)
                && ctx.inventory().getCount(recipe.logs) > 0
                && inventoryOnlyContains(ctx, recipe.logs, CUTTING_TOOLS[0], CUTTING_TOOLS[1]);
    }

    private boolean inventoryReadyForStringing(APIContext ctx, FletchingRecipe recipe) {
        return ctx.inventory().getCount(recipe.unstrung) > 0
                && ctx.inventory().getCount(BOW_STRING) > 0
                && inventoryOnlyContains(ctx, recipe.unstrung, BOW_STRING);
    }

    private boolean inventoryPartialForCutting(APIContext ctx, FletchingRecipe recipe) {
        return inventoryOnlyContains(ctx, recipe.logs, CUTTING_TOOLS[0], CUTTING_TOOLS[1])
                && (ctx.inventory().getCount(recipe.logs) > 0 || hasAnyCuttingTool(ctx));
    }

    private boolean inventoryPartialForStringing(APIContext ctx, FletchingRecipe recipe) {
        return inventoryOnlyContains(ctx, recipe.unstrung, BOW_STRING)
                && (ctx.inventory().getCount(recipe.unstrung) > 0
                || ctx.inventory().getCount(BOW_STRING) > 0);
    }

    private int inventoryActionCount(APIContext ctx, FletchingRecipe recipe, WorkStage stage) {
        if (stage == WorkStage.CUT) {
            return ctx.inventory().getCount(recipe.logs);
        }
        if (stage == WorkStage.STRING) {
            return Math.min(
                    ctx.inventory().getCount(recipe.unstrung),
                    ctx.inventory().getCount(BOW_STRING)
            );
        }
        return 0;
    }

    private boolean hasLogsAvailable(APIContext ctx, FletchingRecipe recipe) {
        return ctx.inventory().getCount(recipe.logs) > 0
                || (ctx.bank().isOpen() && ctx.bank().getCount(recipe.logs) > 0);
    }

    private boolean hasStringingMaterialsAvailable(APIContext ctx, FletchingRecipe recipe) {
        int unstrung = ctx.inventory().getCount(recipe.unstrung)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(recipe.unstrung) : 0);
        int strings = ctx.inventory().getCount(BOW_STRING)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(BOW_STRING) : 0);
        return Math.min(unstrung, strings) > 0;
    }

    private String stageOutput(FletchingRecipe recipe, WorkStage stage) {
        if (stage == WorkStage.CUT) {
            return recipe.unstrung;
        }
        if (stage == WorkStage.STRING) {
            return recipe.output;
        }
        return recipe.output;
    }

    private int stageOutputItemId(FletchingRecipe recipe, WorkStage stage) {
        if (stage == WorkStage.CUT) {
            return recipe.unstrungId;
        }
        if (stage == WorkStage.STRING) {
            return recipe.outputId;
        }
        return recipe.outputId;
    }

    private String stageLabel(WorkStage stage) {
        if (stage == WorkStage.CUT) {
            return "Cutting";
        }
        if (stage == WorkStage.STRING) {
            return "Stringing";
        }
        return "Idle";
    }

    private boolean inventoryOnlyContains(APIContext ctx, String... names) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!matchesAny(item.getName(), names)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAnyCuttingTool(APIContext ctx) {
        for (String tool : CUTTING_TOOLS) {
            if (ctx.inventory().contains(tool) || ctx.equipment().contains(tool)) {
                return true;
            }
        }
        return false;
    }

    private String bestCuttingTool(APIContext ctx) {
        for (String tool : CUTTING_TOOLS) {
            if (ctx.inventory().contains(tool)) {
                return tool;
            }
        }
        return null;
    }

    private boolean matchesAny(String actual, String... names) {
        for (String name : names) {
            if (namesMatch(actual, name)) {
                return true;
            }
        }
        return false;
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
    }

    private String visibleText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (widget.getName() != null) {
            text.append(' ').append(widget.getName());
        }
        if (widget.getText() != null) {
            text.append(' ').append(widget.getText());
        }
        if (widget.getRawText() != null) {
            text.append(' ').append(widget.getRawText());
        }
        return text.toString().replaceAll("<[^>]+>", " ");
    }

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }
            return text.equalsIgnoreCase(cleanWidgetText(candidate.getText()))
                    || text.equalsIgnoreCase(cleanWidgetText(candidate.getRawText()));
        })) {
            return widget;
        }
        WidgetChild queried = ctx.widgets().query().textContains(text).results().first();
        return isVisibleWidget(queried) ? queried : null;
    }

    private String cleanWidgetText(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", " ").trim();
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            text.append(' ').append(visibleText(widget));
        }
        return text.toString();
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private Point randomWidgetPoint(WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return widget.getCentralPoint();
        }
        int marginX = Math.min(6, Math.max(0, bounds.width / 5));
        int marginY = Math.min(6, Math.max(0, bounds.height / 5));
        int minX = bounds.x + marginX;
        int maxX = Math.max(minX, bounds.x + bounds.width - marginX - 1);
        int minY = bounds.y + marginY;
        int maxY = Math.max(minY, bounds.y + bounds.height - marginY - 1);
        return new Point(
                ThreadLocalRandom.current().nextInt(minX, maxX + 1),
                ThreadLocalRandom.current().nextInt(minY, maxY + 1)
        );
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null || tile.getPlane() != 0) {
            return false;
        }
        return tile.getX() >= GE_MIN_X
                && tile.getX() <= GE_MAX_X
                && tile.getY() >= GE_MIN_Y
                && tile.getY() <= GE_MAX_Y;
    }

    private int fletchingLevel(APIContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.skills().get(Skill.Skills.FLETCHING).getRealLevel();
    }

    private void clearClientInteractionState() {
        APIContext ctx = getAPIContext();
        if (ctx == null) {
            return;
        }
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only; stopping must not throw.
        }
    }

    private void clearInventoryInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private void log(String message) {
        if (stats != null) {
            stats.setStatus(message);
        }
        watchdog.note("LOG " + message);
        getLogger().info(message);
    }

    private void logOccasionally(String message) {
        long now = System.currentTimeMillis();
        if (now < nextIdleLogAt) {
            return;
        }
        log(message);
        nextIdleLogAt = now + 15_000L;
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private class Watchdog {
        private final DateTimeFormatter fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
        private final ArrayDeque<String> recentSamples = new ArrayDeque<>();
        private final ArrayDeque<String> recentCoarseSignatures = new ArrayDeque<>();
        private long startedAt;
        private long lastProgressAt;
        private long fineRepeatedSince;
        private long coarseRepeatedSince;
        private int fineRepeatedSamples;
        private int coarseRepeatedSamples;
        private String lastProgressSignature = "";
        private String lastFineLoopSignature = "";
        private String lastCoarseLoopSignature = "";
        private boolean triggered;
        private Path lastDumpPath;

        private void reset() {
            long now = System.currentTimeMillis();
            startedAt = now;
            lastProgressAt = now;
            fineRepeatedSince = now;
            coarseRepeatedSince = now;
            fineRepeatedSamples = 0;
            coarseRepeatedSamples = 0;
            lastProgressSignature = "";
            lastFineLoopSignature = "";
            lastCoarseLoopSignature = "";
            triggered = false;
            lastDumpPath = null;
            recentEvents.clear();
            recentSamples.clear();
            recentCoarseSignatures.clear();
            note("WATCHDOG reset for " + SCRIPT_VERSION);
        }

        private void note(String message) {
            if (message == null || message.isBlank()) {
                return;
            }
            appendLimited(recentEvents, timestampNow() + " | " + message, WATCHDOG_HISTORY_LIMIT);
        }

        private boolean guard(APIContext ctx) {
            if (triggered) {
                Time.sleep(1200, 1800);
                return false;
            }
            if (startedAt <= 0L) {
                reset();
            }

            WatchdogSample sample = capture(ctx);
            appendLimited(recentSamples, sample.line, WATCHDOG_HISTORY_LIMIT);
            appendLimited(recentCoarseSignatures, sample.coarseLoopSignature, WATCHDOG_LOOP_SAMPLE_THRESHOLD * 2);
            updateProgress(sample);
            updateLoopCounters(sample);

            long now = System.currentTimeMillis();
            if (now - startedAt < WATCHDOG_GRACE_MS) {
                return true;
            }

            String reason = watchdogReason(sample, now);
            if (reason == null) {
                return true;
            }

            trigger(ctx, reason, sample);
            return false;
        }

        private void updateProgress(WatchdogSample sample) {
            if (lastProgressSignature.equals(sample.progressSignature)) {
                return;
            }
            lastProgressSignature = sample.progressSignature;
            lastProgressAt = sample.timestamp;
            note("PROGRESS " + sample.progressSummary);
        }

        private void updateLoopCounters(WatchdogSample sample) {
            if (lastFineLoopSignature.equals(sample.fineLoopSignature)) {
                fineRepeatedSamples++;
            } else {
                lastFineLoopSignature = sample.fineLoopSignature;
                fineRepeatedSince = sample.timestamp;
                fineRepeatedSamples = 1;
            }

            if (lastCoarseLoopSignature.equals(sample.coarseLoopSignature)) {
                coarseRepeatedSamples++;
            } else {
                lastCoarseLoopSignature = sample.coarseLoopSignature;
                coarseRepeatedSince = sample.timestamp;
                coarseRepeatedSamples = 1;
            }
        }

        private String watchdogReason(WatchdogSample sample, long now) {
            long inactiveFor = now - lastProgressAt;
            if (inactiveFor >= WATCHDOG_INACTIVITY_MS) {
                return "No real progress for " + formatDuration(inactiveFor);
            }

            if (sample.expectedLongWait) {
                return null;
            }

            String oscillationReason = oscillationReason(now);
            if (oscillationReason != null) {
                return oscillationReason;
            }

            long fineLoopFor = now - fineRepeatedSince;
            if (fineRepeatedSamples >= WATCHDOG_LOOP_SAMPLE_THRESHOLD && fineLoopFor >= WATCHDOG_LOOP_MS) {
                return "Same script state repeated for " + formatDuration(fineLoopFor)
                        + " (" + fineRepeatedSamples + " samples)";
            }

            long coarseLoopFor = now - coarseRepeatedSince;
            if (coarseRepeatedSamples >= WATCHDOG_LOOP_SAMPLE_THRESHOLD && coarseLoopFor >= WATCHDOG_LOOP_MS) {
                return "Same account state repeated for " + formatDuration(coarseLoopFor)
                        + " (" + coarseRepeatedSamples + " samples)";
            }

            return null;
        }

        private String oscillationReason(long now) {
            long inactiveFor = now - lastProgressAt;
            if (inactiveFor < WATCHDOG_LOOP_MS
                    || recentCoarseSignatures.size() < WATCHDOG_LOOP_SAMPLE_THRESHOLD) {
                return null;
            }

            Map<String, Integer> counts = new HashMap<>();
            for (String signature : recentCoarseSignatures) {
                counts.put(signature, counts.getOrDefault(signature, 0) + 1);
            }
            int repeatedSamples = 0;
            for (int count : counts.values()) {
                if (count > 1) {
                    repeatedSamples += count;
                }
            }

            if (counts.size() <= 4 && repeatedSamples >= WATCHDOG_LOOP_SAMPLE_THRESHOLD) {
                return "Likely action loop: " + counts.size()
                        + " account states repeated for " + formatDuration(inactiveFor)
                        + " without productive progress";
            }
            return null;
        }

        private void trigger(APIContext ctx, String reason, WatchdogSample sample) {
            triggered = true;
            note("TRIGGER " + reason);
            stats.setStatus("WATCHDOG logout: " + shortText(reason, 52));

            closeBlockingInterfacesForWatchdog(ctx);

            boolean logoutRequested = false;
            String logoutError = "-";
            try {
                logoutRequested = ctx.game().logout();
            } catch (RuntimeException ex) {
                logoutError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
            Time.sleep(1600, 2800);
            note("LOGOUT requested=" + logoutRequested
                    + " gameState=" + safeGameState(ctx)
                    + " error=" + logoutError);

            Path dump = writeDiagnostic(ctx, reason, sample, logoutRequested, logoutError);
            if (dump != null) {
                log("WATCHDOG triggered; diagnostic saved at " + dump);
            } else {
                log("WATCHDOG triggered; diagnostic file could not be written");
            }

            try {
                ctx.script().stop("Watchdog logout: " + reason);
            } catch (RuntimeException ex) {
                getLogger().warn("Watchdog could not stop script after logout", ex);
            }
        }

        private void closeBlockingInterfacesForWatchdog(APIContext ctx) {
            try {
                clearClientInteractionState();
                if (ctx.grandExchange().isOpen()) {
                    ctx.grandExchange().close();
                    Time.sleep(400, 700);
                }
                if (ctx.bank().isOpen()) {
                    ctx.bank().close();
                    Time.sleep(400, 700);
                }
                if (makeInterfaceOpen(ctx)) {
                    ctx.widgets().closeInterface();
                    Time.sleep(400, 700);
                }
            } catch (RuntimeException ex) {
                note("WATCHDOG interface cleanup failed: " + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
            }
        }

        private WatchdogSample capture(APIContext ctx) {
            long now = System.currentTimeMillis();
            String status = stats == null ? "-" : stats.status;
            String recipe = activeRecipe == null ? "-" : activeRecipe.label;
            String tile = safeTile(ctx);
            String inventory = inventorySnapshot(ctx);
            String equipment = equipmentSnapshot(ctx);
            String ge = geSnapshot(ctx);
            String selected = safeString("-", () -> ctx.inventory().getSelectedItemName());
            boolean atGe = safeBoolean(false, () -> isAtGrandExchange(ctx));
            boolean moving = safeBoolean(false, () -> ctx.localPlayer().isMoving());
            boolean animating = safeBoolean(false, () -> ctx.localPlayer().isAnimating());
            boolean bankOpen = safeBoolean(false, () -> ctx.bank().isOpen());
            boolean geOpen = safeBoolean(false, () -> ctx.grandExchange().isOpen());
            boolean makeOpen = safeBoolean(false, () -> makeInterfaceOpen(ctx));
            boolean menuOpen = safeBoolean(false, () -> ctx.menu().isOpen());
            int xp = safeInt(-1, () -> ctx.skills().get(Skill.Skills.FLETCHING).getExperience());
            int processed = stats == null ? 0 : stats.processedActions;
            int gameState = safeGameState(ctx);
            int pending = pendingGeActions.size();
            int placed = placedGeActions.size();
            WorkStage stage = safeStage(ctx);
            boolean expectedLongWait = placed > 0 && geOpen;

            String progressSignature = "xp=" + xp
                    + "|processed=" + processed
                    + "|tile=" + tile
                    + "|recipe=" + recipe
                    + "|pending=" + pending
                    + "|placed=" + placed
                    + "|ge=" + ge;

            String fineLoopSignature = "game=" + gameState
                    + "|status=" + status
                    + "|recipe=" + recipe
                    + "|stage=" + stage
                    + "|tile=" + tile
                    + "|atGe=" + atGe
                    + "|moving=" + moving
                    + "|anim=" + animating
                    + "|bank=" + bankOpen
                    + "|geOpen=" + geOpen
                    + "|make=" + makeOpen
                    + "|menu=" + menuOpen
                    + "|selected=" + selected
                    + "|inv=" + inventory
                    + "|pending=" + pending
                    + "|placed=" + placed;

            String coarseLoopSignature = "game=" + gameState
                    + "|recipe=" + recipe
                    + "|stage=" + stage
                    + "|tile=" + tile
                    + "|atGe=" + atGe
                    + "|moving=" + moving
                    + "|anim=" + animating
                    + "|bank=" + bankOpen
                    + "|geOpen=" + geOpen
                    + "|make=" + makeOpen
                    + "|menu=" + menuOpen
                    + "|inv=" + inventory
                    + "|pending=" + pending
                    + "|placed=" + placed;

            String progressSummary = "xp=" + xp
                    + " actions=" + processed
                    + " tile=" + tile
                    + " recipe=" + recipe
                    + " inv=" + shortText(inventory, 80)
                    + " ge=" + shortText(ge, 80);

            String line = timestampNow()
                    + " | status=" + status
                    + " | recipe=" + recipe
                    + " | stage=" + stage
                    + " | game=" + gameState
                    + " | tile=" + tile
                    + " | atGE=" + atGe
                    + " | moving=" + moving
                    + " | anim=" + animating
                    + " | bank=" + bankOpen
                    + " | geOpen=" + geOpen
                    + " | make=" + makeOpen
                    + " | menu=" + menuOpen
                    + " | selected=" + selected
                    + " | xp=" + xp
                    + " | actions=" + processed
                    + " | pending=" + pending
                    + " | placed=" + placed
                    + " | inv=" + inventory;

            return new WatchdogSample(
                    now,
                    line,
                    progressSignature,
                    fineLoopSignature,
                    coarseLoopSignature,
                    progressSummary,
                    expectedLongWait
            );
        }

        private WorkStage safeStage(APIContext ctx) {
            if (activeRecipe == null) {
                return WorkStage.NONE;
            }
            try {
                return nextInventoryStage(ctx, activeRecipe);
            } catch (RuntimeException ignored) {
                return WorkStage.NONE;
            }
        }

        private Path writeDiagnostic(
                APIContext ctx,
                String reason,
                WatchdogSample sample,
                boolean logoutRequested,
                String logoutError
        ) {
            try {
                Path dir = watchdogLogDirectory(ctx);
                Files.createDirectories(dir);
                Path file = dir.resolve("fletching-watchdog-"
                        + LocalDateTime.now().format(fileTimestamp)
                        + "-" + Math.abs(System.currentTimeMillis() % 100_000L)
                        + ".log");

                List<String> lines = new ArrayList<>();
                lines.add("Fletching Profit watchdog diagnostic");
                lines.add("version=" + SCRIPT_VERSION);
                lines.add("reason=" + reason);
                lines.add("created=" + LocalDateTime.now());
                lines.add("runtime=" + (stats == null ? "-" : stats.runtimeText()));
                lines.add("logoutRequested=" + logoutRequested);
                lines.add("logoutError=" + logoutError);
                lines.add("gameStateAfterLogout=" + safeGameState(ctx));
                lines.add("lastProgressAgo=" + formatDuration(System.currentTimeMillis() - lastProgressAt));
                lines.add("fineLoopFor=" + formatDuration(System.currentTimeMillis() - fineRepeatedSince)
                        + " samples=" + fineRepeatedSamples);
                lines.add("coarseLoopFor=" + formatDuration(System.currentTimeMillis() - coarseRepeatedSince)
                        + " samples=" + coarseRepeatedSamples);
                lines.add("");
                lines.add("Current script state");
                lines.add("status=" + (stats == null ? "-" : stats.status));
                lines.add("lastChat=" + (stats == null ? "-" : stats.lastChat));
                lines.add("lastGeAction=" + (stats == null ? "-" : stats.lastGeAction));
                lines.add("processedActions=" + (stats == null ? 0 : stats.processedActions));
                lines.add("lastProcessedAt=" + (stats == null ? 0 : stats.lastProcessedAt));
                lines.add("activeRecipe=" + (activeRecipe == null ? "-" : activeRecipe.label));
                lines.add("activeQuote=" + activeQuoteSummary());
                lines.add("pendingActions=" + geActionQueueText(pendingGeActions));
                lines.add("placedActions=" + geActionQueueText(placedGeActions));
                lines.add("");
                lines.add("Trigger sample");
                lines.add(sample.line);
                lines.add("");
                lines.add("Current snapshots");
                lines.add("tile=" + safeTile(ctx));
                lines.add("inventory=" + inventorySnapshot(ctx));
                lines.add("equipment=" + equipmentSnapshot(ctx));
                lines.add("ge=" + geSnapshot(ctx));
                lines.add("makeWidgets=" + makeWidgetsSnapshot(ctx));
                lines.add("rowActions=" + rowActionsSnapshot(ctx));
                lines.add("");
                lines.add("Recent events");
                lines.addAll(recentEvents);
                lines.add("");
                lines.add("Recent samples");
                lines.addAll(recentSamples);

                Files.write(file, lines, StandardCharsets.UTF_8);
                lastDumpPath = file;
                return file;
            } catch (IOException | RuntimeException ex) {
                getLogger().error("Failed to write watchdog diagnostic", ex);
                return null;
            }
        }

        private Path watchdogLogDirectory(APIContext ctx) {
            File base = null;
            try {
                base = ctx.script().getSettingsDirectory();
            } catch (RuntimeException ignored) {
                // Fall back below.
            }
            if (base == null) {
                base = new File(System.getProperty("user.home"), "EpicBot");
            }
            return base.toPath().resolve("watchdog-logs");
        }

        private String activeQuoteSummary() {
            if (activeQuote == null) {
                return "-";
            }
            return "cost=" + activeQuote.costPerAction
                    + " profit=" + activeQuote.profitPerAction
                    + " profit/h=" + activeQuote.profitPerHour
                    + " bows/h=" + activeQuote.bowsPerHour
                    + " logsBuy=" + activeQuote.primaryBuyPrice
                    + " stringBuy=" + activeQuote.secondaryBuyPrice
                    + " outputSell=" + activeQuote.outputSellPrice;
        }

        private String geActionQueueText(Iterable<GeAction> actions) {
            List<String> parts = new ArrayList<>();
            for (GeAction action : actions) {
                parts.add(action.describe());
            }
            return parts.isEmpty() ? "-" : String.join(" | ", parts);
        }

        private String rowActionsSnapshot(APIContext ctx) {
            ItemWidget ring = safeItem(() -> equippedRingOfWealth(ctx));
            if (ring == null) {
                return "-";
            }
            return ring.getName() + " id=" + ring.getId() + " actions=" + ring.getActions();
        }

        private String makeWidgetsSnapshot(APIContext ctx) {
            try {
                List<String> parts = new ArrayList<>();
                for (WidgetChild widget : makeInterfaceItemWidgets(ctx)) {
                    parts.add("id=" + widget.getItemId()
                            + " child=" + widget.getChildId()
                            + " bounds=" + widget.getBounds()
                            + " actions=" + widget.getActions()
                            + " text='" + cleanWidgetText(visibleText(widget)) + "'");
                }
                return parts.isEmpty() ? "-" : String.join(" | ", parts);
            } catch (RuntimeException ex) {
                return "error:" + ex.getClass().getSimpleName() + ":" + ex.getMessage();
            }
        }

        private String geSnapshot(APIContext ctx) {
            return safeString("error", () -> {
                if (!ctx.grandExchange().isOpen()) {
                    return "closed";
                }
                List<String> parts = new ArrayList<>();
                for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
                    if (slot == null || !slot.inUse()) {
                        continue;
                    }
                    GrandExchangeOffer offer = slot.getOffer();
                    String item = offer == null ? "-" : offer.getItemName();
                    int current = offer == null ? 0 : offer.getCurrentQuantity();
                    int total = offer == null ? 0 : offer.getTotalQuantity();
                    int price = offer == null ? 0 : offer.getPrice();
                    parts.add(slot.getIndex() + ":" + slot.getState()
                            + ":" + item + ":" + current + "/" + total + "@" + price
                            + ":collect=" + slot.canCollect());
                }
                return parts.isEmpty() ? "open-empty" : String.join(",", parts);
            });
        }

        private String inventorySnapshot(APIContext ctx) {
            return safeString("error", () -> itemWidgetsSnapshot(ctx.inventory().getItems()));
        }

        private String equipmentSnapshot(APIContext ctx) {
            return safeString("error", () -> itemWidgetsSnapshot(ctx.equipment().getItems()));
        }

        private String itemWidgetsSnapshot(List<ItemWidget> items) {
            List<ItemWidget> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingInt(ItemWidget::getIndex));
            List<String> parts = new ArrayList<>();
            for (ItemWidget item : sorted) {
                if (item == null || !item.isValid() || item.getName() == null || item.getName().isBlank()) {
                    continue;
                }
                parts.add(item.getIndex()
                        + ":" + item.getId()
                        + ":" + normalizedName(item.getName())
                        + "x" + item.getStackSize());
            }
            return parts.isEmpty() ? "empty" : String.join(",", parts);
        }

        private String safeTile(APIContext ctx) {
            return safeString("-", () -> {
                Tile tile = ctx.localPlayer().getLocation();
                if (tile == null) {
                    return "-";
                }
                return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
            });
        }

        private int safeGameState(APIContext ctx) {
            return safeInt(-1, () -> ctx.game().getGameState().getState());
        }

        private ItemWidget safeItem(ItemSupplier supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private String safeString(String fallback, StringSupplier supplier) {
            try {
                String value = supplier.get();
                return value == null ? fallback : value;
            } catch (RuntimeException ex) {
                return fallback + "(" + ex.getClass().getSimpleName() + ")";
            }
        }

        private int safeInt(int fallback, IntSupplier supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                return fallback;
            }
        }

        private boolean safeBoolean(boolean fallback, BooleanSupplier supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                return fallback;
            }
        }

        private void appendLimited(ArrayDeque<String> deque, String value, int limit) {
            deque.addLast(value);
            while (deque.size() > limit) {
                deque.removeFirst();
            }
        }

        private String timestampNow() {
            return LocalDateTime.now().toString();
        }

        private String formatDuration(long millis) {
            long seconds = Math.max(0L, millis / 1000L);
            long minutes = seconds / 60L;
            long remainingSeconds = seconds % 60L;
            return minutes + "m " + remainingSeconds + "s";
        }
    }

    private static class WatchdogSample {
        private final long timestamp;
        private final String line;
        private final String progressSignature;
        private final String fineLoopSignature;
        private final String coarseLoopSignature;
        private final String progressSummary;
        private final boolean expectedLongWait;

        private WatchdogSample(
                long timestamp,
                String line,
                String progressSignature,
                String fineLoopSignature,
                String coarseLoopSignature,
                String progressSummary,
                boolean expectedLongWait
        ) {
            this.timestamp = timestamp;
            this.line = line;
            this.progressSignature = progressSignature;
            this.fineLoopSignature = fineLoopSignature;
            this.coarseLoopSignature = coarseLoopSignature;
            this.progressSummary = progressSummary;
            this.expectedLongWait = expectedLongWait;
        }
    }

    @FunctionalInterface
    private interface StringSupplier {
        String get();
    }

    @FunctionalInterface
    private interface IntSupplier {
        int get();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean get();
    }

    @FunctionalInterface
    private interface ItemSupplier {
        ItemWidget get();
    }

    private enum WorkStage {
        NONE,
        CUT,
        STRING
    }

    private static class FletchingRecipe {
        private final int level;
        private final String label;
        private final String plainLabel;
        private final String logs;
        private final String unstrung;
        private final int unstrungId;
        private final String output;
        private final int outputId;
        private final double stepXp;
        private final int cutXpPerHour;
        private final int stringXpPerHour;

        private FletchingRecipe(
                int level,
                String label,
                String plainLabel,
                String logs,
                String unstrung,
                int unstrungId,
                String output,
                int outputId,
                double stepXp,
                int cutXpPerHour,
                int stringXpPerHour
        ) {
            this.level = level;
            this.label = label;
            this.plainLabel = plainLabel;
            this.logs = logs;
            this.unstrung = unstrung;
            this.unstrungId = unstrungId;
            this.output = output;
            this.outputId = outputId;
            this.stepXp = stepXp;
            this.cutXpPerHour = cutXpPerHour;
            this.stringXpPerHour = stringXpPerHour;
        }

        private static List<FletchingRecipe> all() {
            return List.of(
                    pipeline(5, "Shortbow", "Shortbow", "Logs", "Shortbow (u)", 50, "Shortbow", 841, 5.0D, 13_500, 12_250),
                    pipeline(10, "Longbow", "Longbow", "Logs", "Longbow (u)", 48, "Longbow", 839, 10.0D, 27_000, 24_500),
                    pipeline(20, "Oak shortbow", "Oak shortbow", "Oak logs", "Oak shortbow (u)", 54, "Oak shortbow", 843, 16.5D, 44_550, 40_425),
                    pipeline(25, "Oak longbow", "Oak longbow", "Oak logs", "Oak longbow (u)", 56, "Oak longbow", 845, 25.0D, 67_500, 61_250),
                    pipeline(35, "Willow shortbow", "Willow shortbow", "Willow logs", "Willow shortbow (u)", 60, "Willow shortbow", 849, 33.3D, 89_910, 81_585),
                    pipeline(40, "Willow longbow", "Willow longbow", "Willow logs", "Willow longbow (u)", 58, "Willow longbow", 847, 41.5D, 112_050, 101_675),
                    pipeline(50, "Maple shortbow", "Maple shortbow", "Maple logs", "Maple shortbow (u)", 64, "Maple shortbow", 853, 50.0D, 135_000, 122_500),
                    pipeline(55, "Maple longbow", "Maple longbow", "Maple logs", "Maple longbow (u)", 62, "Maple longbow", 851, 58.3D, 157_410, 142_835),
                    pipeline(65, "Yew shortbow", "Yew shortbow", "Yew logs", "Yew shortbow (u)", 68, "Yew shortbow", 857, 67.5D, 182_250, 165_375),
                    pipeline(70, "Yew longbow", "Yew longbow", "Yew logs", "Yew longbow (u)", 66, "Yew longbow", 855, 75.0D, 202_500, 183_750),
                    pipeline(80, "Magic shortbow", "Magic shortbow", "Magic logs", "Magic shortbow (u)", 72, "Magic shortbow", 861, 83.3D, 224_910, 203_350),
                    pipeline(85, "Magic longbow", "Magic longbow", "Magic logs", "Magic longbow (u)", 70, "Magic longbow", 859, 91.5D, 247_050, 224_175)
            );
        }

        private static FletchingRecipe pipeline(
                int level,
                String label,
                String plainLabel,
                String logs,
                String unstrung,
                int unstrungId,
                String output,
                int outputId,
                double stepXp,
                int cutXpPerHour,
                int stringXpPerHour
        ) {
            return new FletchingRecipe(
                    level,
                    label,
                    plainLabel,
                    logs,
                    unstrung,
                    unstrungId,
                    output,
                    outputId,
                    stepXp,
                    cutXpPerHour,
                    stringXpPerHour
            );
        }

        private int cutOptionIndex() {
            return normalizedStatic(label).contains("longbow") ? 2 : 1;
        }

        private long completedBowsPerHour() {
            double cutHours = stepXp / Math.max(1, cutXpPerHour);
            double stringHours = stepXp / Math.max(1, stringXpPerHour);
            return Math.max(1L, Math.round(1.0D / (cutHours + stringHours)));
        }

        private double totalXpPerBow() {
            return stepXp * 2.0D;
        }

        private static String normalizedStatic(String value) {
            return value == null
                    ? ""
                    : value.replaceAll("<[^>]+>", " ")
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]", "");
        }
    }

    private class Pricing {
        private Quote quote(APIContext ctx, FletchingRecipe recipe) {
            ItemDetail logs = itemDetail(ctx, recipe.logs);
            ItemDetail bowString = itemDetail(ctx, BOW_STRING);
            ItemDetail output = itemDetail(ctx, recipe.output);
            int logsBuy = highPrice(logs);
            int stringBuy = highPrice(bowString);
            int outputSell = lowPrice(output);
            long cost = (long) logsBuy + stringBuy;
            long profit = outputSell <= 0 || logsBuy <= 0 || stringBuy <= 0
                    ? Long.MIN_VALUE
                    : taxedSellValue(outputSell) - cost;
            long bowsPerHour = recipe.completedBowsPerHour();
            long profitPerHour = profit == Long.MIN_VALUE ? Long.MIN_VALUE : profit * bowsPerHour;
            return new Quote(recipe, logsBuy, stringBuy, outputSell, cost, profit, profitPerHour, bowsPerHour);
        }

        private int quickBuyPrice(String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(getAPIContext(), itemName);
            long market = firstPositive(highPrice(detail), lowPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.ceil(market * BUY_MARKUP))));
        }

        private int quickSellPrice(String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(getAPIContext(), itemName);
            long market = firstPositive(lowPrice(detail), highPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.floor(market * SELL_MARKDOWN))));
        }

        private ItemDetail itemDetail(APIContext ctx, String itemName) {
            if (ctx == null || itemName == null || itemName.isBlank()) {
                return null;
            }
            try {
                return ctx.pricing().get(itemName);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private int highPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getHighestPrice());
        }

        private int lowPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getLowestPrice());
        }

        private long firstPositive(long first, long second, long third) {
            if (first > 0) {
                return first;
            }
            if (second > 0) {
                return second;
            }
            return Math.max(1L, third);
        }

        private long taxedSellValue(long sellPrice) {
            long tax = (long) Math.floor(sellPrice * GE_TAX_RATE);
            return Math.max(0L, sellPrice - tax);
        }
    }

    private static class Quote {
        private final FletchingRecipe recipe;
        private final int primaryBuyPrice;
        private final int secondaryBuyPrice;
        private final int outputSellPrice;
        private final long costPerAction;
        private final long profitPerAction;
        private final long profitPerHour;
        private final long bowsPerHour;

        private Quote(
                FletchingRecipe recipe,
                int primaryBuyPrice,
                int secondaryBuyPrice,
                int outputSellPrice,
                long costPerAction,
                long profitPerAction,
                long profitPerHour,
                long bowsPerHour
        ) {
            this.recipe = recipe;
            this.primaryBuyPrice = primaryBuyPrice;
            this.secondaryBuyPrice = secondaryBuyPrice;
            this.outputSellPrice = outputSellPrice;
            this.costPerAction = costPerAction;
            this.profitPerAction = profitPerAction;
            this.profitPerHour = profitPerHour;
            this.bowsPerHour = bowsPerHour;
        }

        private boolean hasPrices() {
            return profitPerAction != Long.MIN_VALUE;
        }

        private boolean profitable() {
            return hasPrices() && profitPerAction > 0;
        }

    }

    private static class RestockPlan {
        private final int primaryQuantity;
        private final int secondaryQuantity;
        private final long cost;

        private RestockPlan(int primaryQuantity, int secondaryQuantity, long cost) {
            this.primaryQuantity = primaryQuantity;
            this.secondaryQuantity = secondaryQuantity;
            this.cost = cost;
        }

        private boolean isEmpty() {
            return primaryQuantity <= 0 && secondaryQuantity <= 0;
        }

        private String actionsText() {
            if (secondaryQuantity <= 0) {
                return primaryQuantity + " logs";
            }
            return primaryQuantity + " logs / " + secondaryQuantity + " strings";
        }
    }

    private enum GeActionType {
        BUY,
        SELL
    }

    private static class GeAction {
        private final GeActionType type;
        private final String itemName;
        private final int quantity;
        private final int price;

        private GeAction(GeActionType type, String itemName, int quantity, int price) {
            this.type = type;
            this.itemName = itemName;
            this.quantity = quantity;
            this.price = price;
        }

        private static GeAction buy(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.BUY, itemName, quantity, price);
        }

        private static GeAction sell(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.SELL, itemName, quantity, price);
        }

        private String describe() {
            return type.name().toLowerCase() + " " + quantity + "x " + itemName + " @ " + price;
        }
    }

    private class Stats {
        private final long startedAt = System.currentTimeMillis();
        private int startingFletchingXp = -1;
        private int processedActions;
        private long lastProcessedAt;
        private String status = "Starting";
        private String lastChat = "-";
        private String lastGeAction = "-";

        private Stats() {
        }

        private void startExperienceIfNeeded(APIContext ctx) {
            if (ctx == null || startingFletchingXp >= 0) {
                return;
            }
            startingFletchingXp = ctx.skills().get(Skill.Skills.FLETCHING).getExperience();
        }

        private int xpGained(APIContext ctx) {
            if (ctx == null || startingFletchingXp < 0) {
                return 0;
            }
            return Math.max(0, ctx.skills().get(Skill.Skills.FLETCHING).getExperience() - startingFletchingXp);
        }

        private int xpPerHour(APIContext ctx) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - startedAt);
            return (int) Math.round(xpGained(ctx) * 3_600_000D / elapsed);
        }

        private String runtimeText() {
            long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            long hours = seconds / 3600L;
            long minutes = (seconds % 3600L) / 60L;
            long secs = seconds % 60L;
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }

        private void setStatus(String status) {
            String next = status == null ? "-" : status;
            if (!next.equals(this.status)) {
                this.status = next;
                watchdog.note("STATUS " + next);
            }
        }
    }
}
