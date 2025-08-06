package lightstudio.stock_plugin

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*
import kotlin.math.roundToInt

import org.bukkit.command.TabCompleter

class Stock_plugin : JavaPlugin(), CommandExecutor, Listener, TabCompleter {

    private var econ: Economy? = null
    private lateinit var db: Connection
    internal val stocks = mutableMapOf<String, Stock>() // Changed to internal
    private val stockTrends = mutableMapOf<String, Trend>()
    private val lastNotifiedPrices = mutableMapOf<String, Double>()

    // Config values
    private var transactionFeePercent: Double = 0.0
    private var itemsAllStructureVoid: Boolean = false
    private var enableTransactionLimits: Boolean = false
    private var dailyBuyLimit: Int = 0
    private var dailySellLimit: Int = 0
    private lateinit var mainGuiTitle: String
    private lateinit var portfolioGuiTitle: String
    private lateinit var rankingGuiTitle: String
    private lateinit var subscribeGuiTitle: String
    private lateinit var buyGuiTitleFormat: String
    private lateinit var sellGuiTitleFormat: String

    // Button Configurations
    private lateinit var portfolioButton: ButtonConfig
    private lateinit var subscribeButton: ButtonConfig
    private lateinit var rankingButton: ButtonConfig
    private lateinit var subscribeToggleOnButton: ButtonConfig
    private lateinit var subscribeToggleOffButton: ButtonConfig
    private lateinit var portfolioTotalAssetButton: ButtonConfig
    private lateinit var backButton: ButtonConfig
    private var tradeButtons = listOf<TradeButtonConfig>()
    private lateinit var buyAllButton: ButtonConfig
    private lateinit var sellAllButton: ButtonConfig
    private val messages = mutableMapOf<String, String>()
    private val playerGuiContext = mutableMapOf<UUID, Stack<() -> Unit>>()

    override fun onEnable() {
        if (!setupEconomy()) {
            logger.severe(messages["vault-not-found"] ?: "Vault not found! Disabling plugin.")
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        loadConfigOptions()
        loadStockConfig()
        setupDatabase()

        getCommand("주식")?.setExecutor(this)
        getCommand("주식")?.setTabCompleter(this)
        server.pluginManager.registerEvents(this, this)

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            StockPlaceholders(this).register()
            logger.info("Successfully hooked into PlaceholderAPI.")
        }

        startStockScheduler()
        logger.info(messages["plugin-enabled"] ?: "Stock Plugin Enabled.")
        stocks.forEach { (id, stock) -> lastNotifiedPrices[id] = stock.price }
    }

    override fun onDisable() {
        try {
            if (::db.isInitialized && !db.isClosed) db.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        logger.info(messages["plugin-disabled"] ?: "Stock Plugin Disabled.")
    }

    private fun setupEconomy(): Boolean {
        val rsp = server.servicesManager.getRegistration(Economy::class.java)
        econ = rsp?.provider
        return econ != null
    }

    private fun loadConfigOptions() {
        reloadConfig()
        transactionFeePercent = config.getDouble("transaction-fee-percent", 0.5)
        mainGuiTitle = config.getString("gui.main-title", "§l주식 시장")!!
        portfolioGuiTitle = config.getString("gui.portfolio-title", "§l내 주식 현황")!!
        rankingGuiTitle = config.getString("gui.ranking-title", "§l투자 순위표")!!
        subscribeGuiTitle = config.getString("gui.subscribe-title", "§l주식 구독")!!
        buyGuiTitleFormat = config.getString("gui.buy-title-format", "§a%stock_name% 구매")!!
        sellGuiTitleFormat = config.getString("gui.sell-title-format", "§c%stock_name% 판매")!!
        itemsAllStructureVoid = config.getBoolean("gui.items-all-structure-void", false)

        fun loadButtonConfig(path: String, default: ButtonConfig): ButtonConfig {
            val section = config.getConfigurationSection(path)
            return if (section == null) default else ButtonConfig(
                material = Material.getMaterial(section.getString("material", default.material.name)!!.uppercase()) ?: default.material,
                customModelData = section.getInt("custom-model-data", default.customModelData),
                name = section.getString("name", default.name)!!,
                lore = section.getStringList("lore").ifEmpty { default.lore }
            )
        }

        portfolioButton = loadButtonConfig("buttons.portfolio", ButtonConfig(Material.PLAYER_HEAD, 0, "§a내 주식 현황", listOf("§7클릭하여 내 주식 정보를 봅니다.")))
        subscribeButton = loadButtonConfig("buttons.subscribe", ButtonConfig(Material.BOOK, 0, "§d주식 구독", listOf("§7클릭하여 주식 알림을 구독/해지합니다.")))
        rankingButton = loadButtonConfig("buttons.ranking", ButtonConfig(Material.EMERALD, 0, "§b투자 순위표", listOf("§7클릭하여 투자 순위를 봅니다.")))

        subscribeToggleOnButton = loadButtonConfig("buttons.subscribe-toggle-on", ButtonConfig(Material.LIME_WOOL, 0, "§e%stock_name% §7(구독 중)", listOf("§7상태: §a구독 중", "", "§c클릭하여 구독 해지")))
        subscribeToggleOffButton = loadButtonConfig("buttons.subscribe-toggle-off", ButtonConfig(Material.RED_WOOL, 0, "§e%stock_name% §7(미구독)", listOf("§7상태: §c구독 안 함", "", "§a클릭하여 구독")))
        portfolioTotalAssetButton = loadButtonConfig("buttons.portfolio-total-asset", ButtonConfig(Material.GOLD_INGOT, 0, "§e총 자산 평가액", listOf("§6%total_asset%원")))
        backButton = loadButtonConfig("buttons.back", ButtonConfig(Material.BARRIER, 0, "§c뒤로가기", listOf("§7이전 화면으로 돌아갑니다.")))

        tradeButtons = config.getMapList("trade-gui.buttons").map { map ->
            TradeButtonConfig(
                amount = (map["amount"] as? Int) ?: 1,
                button = ButtonConfig(
                    material = Material.getMaterial((map["material"] as? String)?.uppercase() ?: "STONE") ?: Material.STONE,
                    customModelData = (map["custom-model-data"] as? Int) ?: 0,
                    name = (map["name"] as? String) ?: "%amount%주",
                    lore = (map["lore"] as? List<String>) ?: emptyList()
                )
            )
        }

        buyAllButton = loadButtonConfig("trade-gui.buy-all-button", ButtonConfig(Material.GOLD_INGOT, 0, "§6올인", listOf("§7최대 §e%amount%§7주 구매 가능")))
        sellAllButton = loadButtonConfig("trade-gui.sell-all-button", ButtonConfig(Material.REDSTONE, 0, "§c전부 판매", listOf("§7보유 주식 §e%amount%§7주")))

        enableTransactionLimits = config.getBoolean("stock-transaction-limits.enable", false)
        dailyBuyLimit = config.getInt("stock-transaction-limits.buy", 0)
        dailySellLimit = config.getInt("stock-transaction-limits.sell", 0)

        loadMessages()
    }

    private fun loadMessages() {
        val langFile = File(dataFolder, "lang.yml")
        if (!langFile.exists()) saveResource("lang.yml", false)
        val langConfig = YamlConfiguration.loadConfiguration(langFile)
        messages.clear()
        langConfig.getKeys(true).forEach { key ->
            messages[key] = langConfig.getString(key)!!
        }
    }

    private fun loadStockConfig() {
        val stockFile = File(dataFolder, "stock.yml")
        if (!stockFile.exists()) saveResource("stock.yml", false)
        val stockConfig = YamlConfiguration.loadConfiguration(stockFile)
        stocks.clear()
        stockTrends.clear()
        stockConfig.getConfigurationSection("stocks")?.getKeys(false)?.forEach { key ->
            val section = stockConfig.getConfigurationSection("stocks.$key")!!
            stocks[key] = Stock(
                id = key,
                name = section.getString("name", key)!!,
                price = section.getDouble("initial-price", 1000.0),
                fluctuation = section.getDouble("fluctuation", 100.0),
                material = Material.getMaterial(section.getString("material", "PAPER")!!.uppercase()) ?: Material.PAPER,
                customModelData = section.getInt("custom-model-data", 0)
            )
            stockTrends[key] = Trend.STABLE
        }
    }

    private fun setupDatabase() {
        val dbFile = File(dataFolder, "playerdata.db")
        try {
            Class.forName("org.sqlite.JDBC")
            db = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            db.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_stocks (
                        uuid TEXT NOT NULL,
                        stock_id TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        total_spent REAL NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, stock_id)
                    );
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_subscriptions (
                        uuid TEXT NOT NULL,
                        stock_id TEXT NOT NULL,
                        PRIMARY KEY (uuid, stock_id)
                    );
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_daily_transactions (
                        uuid TEXT NOT NULL,
                        stock_id TEXT NOT NULL,
                        buy_count INTEGER NOT NULL DEFAULT 0,
                        sell_count INTEGER NOT NULL DEFAULT 0,
                        last_updated_date TEXT NOT NULL,
                        PRIMARY KEY (uuid, stock_id)
                    );
                """.trimIndent())
            }
            val meta = db.metaData
            val rs = meta.getColumns(null, null, "player_stocks", "total_spent")
            if (!rs.next()) {
                db.createStatement().use { it.execute("ALTER TABLE player_stocks ADD COLUMN total_spent REAL NOT NULL DEFAULT 0") }
            }
        } catch (e: Exception) {
            logger.severe(messages["database-setup-failed"] ?: "Database setup failed!")
            e.printStackTrace()
        }
    }

    private fun startStockScheduler() {
        val updateInterval = config.getLong("update-interval-seconds", 60) * 20L
        object : BukkitRunnable() {
            override fun run() {
                updateStockPrices()
            }
        }.runTaskTimerAsynchronously(this, 0L, updateInterval)
    }

    private fun updateStockPrices() {
        stocks.forEach { (id, stock) ->
            val oldPrice = stock.price
            val trend = stockTrends[id] ?: Trend.STABLE
            if (Random().nextDouble() < 0.1) {
                stockTrends[id] = Trend.values().random().apply { duration = Random().nextInt(5) + 3 }
            } else {
                trend.duration--
                if (trend.duration <= 0) stockTrends[id] = Trend.STABLE
            }
            val change = (Random().nextDouble() * 2 - 1) * stock.fluctuation * trend.multiplier
            stock.price = (stock.price + change).coerceAtLeast(1.0)

            // Notify subscribed players if price changes significantly
            val priceChangePercent = ((stock.price - oldPrice) / oldPrice) * 100
            val notificationThreshold = config.getDouble("notification-threshold-percent", 1.0)

            if (Math.abs(priceChangePercent) >= notificationThreshold) {
                val messageKey = if (priceChangePercent > 0) "stock-price-increase-notification" else "stock-price-decrease-notification"
                val message = messages[messageKey]
                    ?.replace("%stock_name%", stock.name)
                    ?.replace("%old_price%", String.format("%,.2f", oldPrice))
                    ?.replace("%new_price%", String.format("%,.2f", stock.price))
                    ?.replace("%change_percent%", String.format("%.2f", priceChangePercent))

                if (message != null) {
                    getSubscribedPlayers(stock.id).forEach { uuid ->
                        val player = Bukkit.getPlayer(uuid)
                        player?.sendMessage(message)
                    }
                }
            }
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        when {
            args.isNotEmpty() && args[0].equals("관리", ignoreCase = true) -> {
                if (!sender.hasPermission("stock.admin")) {
                    sender.sendMessage(messages["permission-denied"] ?: "§c권한이 없습니다.")
                    return true
                }
                handleAdminCommand(sender, args.drop(1))
            }
            else -> {
                openMainGui(sender)
            }
        }
        return true
    }

    private fun handleAdminCommand(sender: Player, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(messages["admin-command-usage"] ?: "§c사용법: /주식 관리 <리로드|가격설정>")
            return
        }
        when (args[0].lowercase()) {
            "리로드" -> {
                loadConfigOptions()
                loadStockConfig()
                stocks.forEach { (id, stock) -> lastNotifiedPrices[id] = stock.price }
                sender.sendMessage(messages["admin-reload-success"] ?: "§a설정을 리로드했습니다.")
            }
            "가격설정" -> {
                if (args.size < 3) {
                    sender.sendMessage(messages["admin-setprice-usage"] ?: "§c사용법: /주식 관리 가격설정 <주식ID> <가격>")
                    return
                }
                val stockId = args[1]
                val price = args[2].toDoubleOrNull()
                if (stocks[stockId] == null || price == null || price <= 0) {
                    sender.sendMessage(messages["admin-invalid-stock-or-price"] ?: "§c잘못된 주식 ID 또는 가격입니다.")
                    return
                }
                stocks[stockId]?.price = price
                sender.sendMessage(messages["admin-setprice-success"]?.replace("%stock_name%", stocks[stockId]?.name ?: "")?.replace("%price%", price.toString()) ?: "§a${stocks[stockId]?.name}의 가격을 ${price}로 설정했습니다.")
            }
            else -> sender.sendMessage(messages["admin-unknown-command"] ?: "§c알 수 없는 관리 명령어입니다.")
        }
    }

    

    private fun openMainGui(player: Player) {
        playerGuiContext[player.uniqueId]?.clear() // Clear context when opening main GUI
        val inv = Bukkit.createInventory(null, 54, mainGuiTitle)
        stocks.values.forEachIndexed { i, stock ->
            if (i < 45) inv.setItem(i, createStockItem(stock))
        }
        inv.setItem(48, createGuiItem(portfolioButton))
        inv.setItem(49, createGuiItem(subscribeButton))
        inv.setItem(50, createGuiItem(rankingButton))
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val viewTitle = event.view.title
        val clickedItem = event.currentItem ?: return

        val buyTitlePrefix = buyGuiTitleFormat.substringBefore("%stock_name%")
        val sellTitlePrefix = sellGuiTitleFormat.substringBefore("%stock_name%")

        val isPluginGui = when {
            viewTitle == mainGuiTitle -> true
            viewTitle == portfolioGuiTitle -> true
            viewTitle == rankingGuiTitle -> true
            viewTitle == subscribeGuiTitle -> true
            viewTitle.startsWith(buyTitlePrefix) -> true
            viewTitle.startsWith(sellTitlePrefix) -> true
            else -> false
        }

        if (isPluginGui) {
            event.isCancelled = true
        }

        if (clickedItem.itemMeta?.displayName == backButton.name) {
            if (isPluginGui) { // Only handle back button for plugin GUIs
                val playerStack = playerGuiContext[player.uniqueId]
                if (playerStack != null && playerStack.isNotEmpty()) {
                    playerStack.pop().invoke() // Go back to the previous GUI
                } else {
                    player.closeInventory() // Close if no previous GUI
                }
            }
            return
        }

        if (!isPluginGui) return // Only process plugin GUI clicks further

        when (viewTitle) {
            mainGuiTitle -> {
                val clickedItemName = clickedItem.itemMeta?.displayName
                when (clickedItemName) {
                    portfolioButton.name -> openPortfolioGui(player) { openMainGui(player) }
                    rankingButton.name -> openRankingGui(player) { openMainGui(player) }
                    subscribeButton.name -> openSubscribeGui(player) { openMainGui(player) }
                    else -> {
                        val stock = getStockFromItem(clickedItem) ?: return
                        when (event.click) {
                            ClickType.LEFT -> openTradeGui(player, stock, "buy") { openMainGui(player) }
                            ClickType.RIGHT -> openTradeGui(player, stock, "sell") { openMainGui(player) }
                            else -> {}
                        }
                    }
                }
            }
            portfolioGuiTitle, rankingGuiTitle -> {
                // No specific actions for items within these GUIs yet, but back button is handled.
            }
            subscribeGuiTitle -> {
                val stock = getStockFromItem(clickedItem) ?: return
                val playerUuid = player.uniqueId.toString()
                val isSubscribed = getSubscriptions(playerUuid).contains(stock.id)
                if (isSubscribed) {
                    removeSubscription(playerUuid, stock.id)
                    player.sendMessage(messages["subscribe-removed"]?.replace("%stock_name%", stock.name) ?: "§a${stock.name} 주식 구독을 해지했습니다.")
                } else {
                    addSubscription(playerUuid, stock.id)
                    player.sendMessage(messages["subscribe-added"]?.replace("%stock_name%", stock.name) ?: "§a${stock.name} 주식을 구독했습니다.")
                }
                openSubscribeGui(player) { openMainGui(player) } // Refresh GUI
            }
            else -> {
                val buyTitlePrefix = buyGuiTitleFormat.substringBefore("%stock_name%")
                val sellTitlePrefix = sellGuiTitleFormat.substringBefore("%stock_name%")
                if (viewTitle.startsWith(buyTitlePrefix) || viewTitle.startsWith(sellTitlePrefix)) {
                    handleTrade(player, clickedItem, viewTitle)
                }
            }
        }
    }

    private fun handleTrade(player: Player, item: ItemStack, viewTitle: String) {
        val buyTitlePrefix = buyGuiTitleFormat.substringBefore("%stock_name%")
        val buyTitleSuffix = buyGuiTitleFormat.substringAfter("%stock_name%")
        val sellTitlePrefix = sellGuiTitleFormat.substringBefore("%stock_name%")
        val sellTitleSuffix = sellGuiTitleFormat.substringAfter("%stock_name%")

        val stockName: String? = when {
            viewTitle.startsWith(buyTitlePrefix) && viewTitle.endsWith(buyTitleSuffix) -> viewTitle.removePrefix(buyTitlePrefix).removeSuffix(buyTitleSuffix)
            viewTitle.startsWith(sellTitlePrefix) && viewTitle.endsWith(sellTitleSuffix) -> viewTitle.removePrefix(sellTitlePrefix).removeSuffix(sellTitleSuffix)
            else -> null
        }

        if (stockName == null) return

        val stock = stocks.values.find { it.name == stockName } ?: return
        val isBuy = viewTitle.startsWith(buyTitlePrefix)

        val clickedItemName = item.itemMeta?.displayName ?: return

        val amount: Int = if (isBuy && clickedItemName == buyAllButton.name.replace("%amount%", (econ!!.getBalance(player) / (stock.price * (1 + transactionFeePercent / 100.0))).toInt().toString())) {
            (econ!!.getBalance(player) / (stock.price * (1 + transactionFeePercent / 100.0))).toInt()
        } else if (!isBuy && clickedItemName == sellAllButton.name.replace("%amount%", (getPlayerStock(player.uniqueId.toString(), stock.id)?.amount ?: 0).toString())) {
            getPlayerStock(player.uniqueId.toString(), stock.id)?.amount ?: 0
        } else {
            tradeButtons.find { tb -> clickedItemName == tb.button.name.replace("%amount%", tb.amount.toString()) }?.amount ?: return
        }

        if (amount <= 0) {
            player.sendMessage(if(isBuy) (messages["not-enough-money"] ?: "§c돈이 부족합니다.") else (messages["not-enough-stock"] ?: "§c보유 주식이 부족합니다."))
            player.closeInventory()
            return
        }

        val playerUuid = player.uniqueId.toString()
        val dailyTransaction = getPlayerDailyTransaction(playerUuid, "GLOBAL_TRADE")
        val today = java.time.LocalDate.now().toString()

        var currentBuyCount = if (dailyTransaction?.lastUpdatedDate == today) dailyTransaction.buyCount else 0
        var currentSellCount = if (dailyTransaction?.lastUpdatedDate == today) dailyTransaction.sellCount else 0

        if (enableTransactionLimits) {
            if (isBuy) {
                if (dailyBuyLimit > 0 && currentBuyCount >= dailyBuyLimit) {
                    player.sendMessage(messages["transaction-limit-exceeded-buy"]?.replace("%limit%", dailyBuyLimit.toString()) ?: "§c오늘 이 주식의 구매 한도를 초과했습니다. (일일 한도: ${dailyBuyLimit}회)")
                    player.closeInventory()
                    return
                }
            } else {
                if (dailySellLimit > 0 && currentSellCount >= dailySellLimit) {
                    player.sendMessage(messages["transaction-limit-exceeded-sell"]?.replace("%limit%", dailySellLimit.toString()) ?: "§c오늘 이 주식의 판매 한도를 초과했습니다. (일일 한도: ${dailySellLimit}회)")
                    player.closeInventory()
                    return
                }
            }
        }

        val fee = transactionFeePercent / 100.0
        val totalPrice = stock.price * amount

        if (isBuy) {
            val cost = totalPrice * (1 + fee)
            if (econ!!.getBalance(player) < cost) {
                player.sendMessage(messages["not-enough-money"]?.replace("%cost%", String.format("%,.2f", cost)) ?: "§c돈이 부족합니다. (수수료 포함: ${String.format("%,.2f", cost)}원)")
                return
            }
            econ!!.withdrawPlayer(player, cost)
            updatePlayerStock(player.uniqueId.toString(), stock.id, amount, totalPrice)
            updatePlayerDailyTransaction(player.uniqueId.toString(), stock.id, true)
            val remainingBuys = if (dailyBuyLimit > 0) dailyBuyLimit - (currentBuyCount + 1) else -1 // -1 for unlimited
            val finalBuyMessage = messages["buy-success"]?.replace("%stock_name%", stock.name)?.replace("%amount%", amount.toString())?.replace("%remaining_transactions%", remainingBuys.toString()) ?: "§a${stock.name} ${amount}주를 매수했습니다. (남은 구매 횟수: ${if (remainingBuys == -1) "무제한" else remainingBuys}회)"
            player.sendMessage(finalBuyMessage)
        } else {
            val currentAmount = getPlayerStock(player.uniqueId.toString(), stock.id)?.amount ?: 0
            if (currentAmount < amount) {
                player.sendMessage(messages["not-enough-stock"] ?: "§c보유 주식이 부족합니다.")
                return
            }
            val revenue = totalPrice * (1 - fee)
            econ!!.depositPlayer(player, revenue)
            val avgPrice = getPlayerStock(player.uniqueId.toString(), stock.id)?.avgPrice ?: 0.0
            val spentToRemove = avgPrice * amount
            updatePlayerStock(player.uniqueId.toString(), stock.id, -amount, -spentToRemove)
            updatePlayerDailyTransaction(player.uniqueId.toString(), stock.id, false)
            val remainingSells = if (dailySellLimit > 0) dailySellLimit - (currentSellCount + 1) else -1 // -1 for unlimited
            val finalSellMessage = messages["sell-success"]?.replace("%stock_name%", stock.name)?.replace("%amount%", amount.toString())?.replace("%remaining_transactions%", remainingSells.toString()) ?: "§a${stock.name} ${amount}주를 매도했습니다. (남은 판매 횟수: ${if (remainingSells == -1) "무제한" else remainingSells}회)"
            player.sendMessage(finalSellMessage)
        }
        player.closeInventory()
    }

    private fun openTradeGui(player: Player, stock: Stock, type: String, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val title = if (type == "buy") buyGuiTitleFormat.replace("%stock_name%", stock.name) else sellGuiTitleFormat.replace("%stock_name%", stock.name)
        val inv = Bukkit.createInventory(null, 27, title)

        val startSlot = (27 - tradeButtons.size) / 2
        tradeButtons.forEachIndexed { i, tradeButton ->
            if (i < 9) {
                val buttonConf = tradeButton.button
                val item = createGuiItem(buttonConf, mapOf("%amount%" to tradeButton.amount.toString()))
                inv.setItem(startSlot + i, item)
            }
        }

        if (type == "buy") {
            val maxBuyable = (econ!!.getBalance(player) / (stock.price * (1 + transactionFeePercent / 100.0))).toInt()
            val item = createGuiItem(buyAllButton, mapOf("%amount%" to maxBuyable.toString()))
            inv.setItem(16, item) // Consider making this slot configurable
        } else {
            val currentAmount = getPlayerStock(player.uniqueId.toString(), stock.id)?.amount ?: 0
            val item = createGuiItem(sellAllButton, mapOf("%amount%" to currentAmount.toString()))
            inv.setItem(16, item) // Consider making this slot configurable
        }

        val backButtonSlot = getBackButtonSlot(inv.size)
        if (backButtonSlot != -1) {
            inv.setItem(backButtonSlot, createGuiItem(backButton))
        }
        player.openInventory(inv)
    }

    private fun openPortfolioGui(player: Player, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val inv = Bukkit.createInventory(null, 54, portfolioGuiTitle)
        player.sendMessage(messages["stock-portfolio-loading-info"] ?: "§7주식 정보를 불러오는 중...")
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            val portfolio = getPlayerPortfolio(player.uniqueId.toString())
            var totalAsset = 0.0
            val items = portfolio.map { pStock ->
                val stock = stocks[pStock.stockId] ?: return@map null
                val currentVal = stock.price * pStock.amount
                totalAsset += currentVal
                val profit = currentVal - pStock.totalSpent
                val profitPercent = if (pStock.totalSpent > 0) (profit / pStock.totalSpent) * 100 else 0.0
                val color = if (profit >= 0) "§a" else "§c"
                createGuiItem(stock.material, "§e${stock.name}", listOf(
                    messages["portfolio-stock-quantity"]?.replace("%amount%", pStock.amount.toString()) ?: "§f보유 수량: §6${pStock.amount}주",
                    messages["portfolio-avg-price"]?.replace("%avg_price%", String.format("%,.2f", pStock.avgPrice)) ?: "§f매수 평균가: §6${String.format("%,.2f", pStock.avgPrice)}원",
                    messages["portfolio-current-price"]?.replace("%current_price%", String.format("%,.2f", stock.price)) ?: "§f현재가: §6${String.format("%,.2f", stock.price)}원",
                    messages["portfolio-current-value"]?.replace("%current_value%", String.format("%,.2f", currentVal))?.replace("%color%", color) ?: "§f평가 금액: ${color}${String.format("%,.2f", currentVal)}원",
                    messages["portfolio-profit-loss"]?.replace("%profit_loss%", String.format("%,.2f", profit))?.replace("%profit_loss_percent%", String.format("%.2f", profitPercent))?.replace("%color%", color) ?: "§f평가 손익: ${color}${String.format("%,.2f", profit)}원 (${String.format("%.2f", profitPercent)}%)"
                ), stock.customModelData)
            }.filterNotNull()

            Bukkit.getScheduler().runTask(this, Runnable {
                items.forEachIndexed { i, item -> if (i < 45) inv.setItem(i, item) }
                inv.setItem(49, createGuiItem(portfolioTotalAssetButton, mapOf("%total_asset%" to String.format("%,.2f", totalAsset))))
                val backButtonSlot = getBackButtonSlot(inv.size)
                if (backButtonSlot != -1) {
                    inv.setItem(backButtonSlot, createGuiItem(backButton))
                }
                player.openInventory(inv)
            })
        })
    }

    private fun openRankingGui(player: Player, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val inv = Bukkit.createInventory(null, 54, rankingGuiTitle)
        player.sendMessage(messages["stock-ranking-loading-info"] ?: "§7랭킹 정보를 불러오는 중...")
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            val rankings = getRankings()
            val items = rankings.take(45).mapIndexed { i, rank ->
                val p = Bukkit.getOfflinePlayer(UUID.fromString(rank.uuid))
                val item = ItemStack(Material.PLAYER_HEAD)
                val meta = item.itemMeta as SkullMeta
                meta.owningPlayer = p
                meta.setDisplayName("§e${i + 1}. ${p.name}")
                meta.lore = listOf(messages["ranking-total-asset"]?.replace("%total_asset%", String.format("%,.2f", rank.totalAssets)) ?: "§f총 자산: §6${String.format("%,.2f", rank.totalAssets)}원")
                item.itemMeta = meta
                item
            }
            Bukkit.getScheduler().runTask(this, Runnable {
                items.forEachIndexed { i, item -> inv.setItem(i, item) }
                val backButtonSlot = getBackButtonSlot(inv.size)
                if (backButtonSlot != -1) {
                    inv.setItem(backButtonSlot, createGuiItem(backButton))
                }
                player.openInventory(inv)
            })
        })
    }

    private fun openSubscribeGui(player: Player, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val inv = Bukkit.createInventory(null, 54, subscribeGuiTitle)
        val playerSubscriptions = getSubscriptions(player.uniqueId.toString())

        stocks.values.forEachIndexed { i, stock ->
            if (i < 45) {
                val isSubscribed = playerSubscriptions.contains(stock.id)
                val buttonConf = if (isSubscribed) subscribeToggleOnButton else subscribeToggleOffButton
                val item = createGuiItem(buttonConf, mapOf("%stock_name%" to stock.name, "stock_id" to stock.id)) // Pass stock_id for getStockFromItem
                inv.setItem(i, item)
            }
        }

        val backButtonSlot = getBackButtonSlot(inv.size)
        if (backButtonSlot != -1) {
            inv.setItem(backButtonSlot, createGuiItem(backButton))
        }
        player.openInventory(inv)
    }

    // --- Helper and Data Access Functions ---

    private fun createStockItem(stock: Stock): ItemStack {
        val item = ItemStack(stock.material)
        val meta = item.itemMeta!!
        val trend = stockTrends[stock.id] ?: Trend.STABLE
        meta.setDisplayName("§e${stock.name} §7(${stock.id})")
        if (stock.customModelData != 0) {
            meta.setCustomModelData(stock.customModelData)
        }
        // Lore from lang.yml or default
        val lore = messages["stock-item-lore"]?.split("\n") ?: listOf(
            "§f현재가: §6%price%원",
            "§f추세: %trend%",
            "",
            "§a좌클릭: 구매하기",
            "§c우클릭: 판매하기"
        )
        meta.lore = lore.map {
            it.replace("%price%", String.format("%,.2f", stock.price))
              .replace("%trend%", trend.display)
        }
        item.itemMeta = meta
        return item
    }

    private fun createGuiItem(buttonConfig: ButtonConfig, placeholders: Map<String, String> = emptyMap()): ItemStack {
        return createGuiItem(buttonConfig.material, buttonConfig.name, buttonConfig.lore, buttonConfig.customModelData, true, placeholders)
    }

    private fun createGuiItem(mat: Material, name: String, lore: List<String>, customModelData: Int = 0, isButton: Boolean = false, placeholders: Map<String, String> = emptyMap()): ItemStack {
        val finalMaterial = if (itemsAllStructureVoid && isButton) Material.STRUCTURE_VOID else mat
        val item = ItemStack(finalMaterial)
        val meta = item.itemMeta!!

        var finalName = name
        var finalLore = lore

        placeholders.forEach { (key, value) ->
            finalName = finalName.replace(key, value)
            finalLore = finalLore.map { it.replace(key, value) }
        }

        meta.setDisplayName(finalName)
        meta.lore = finalLore
        if (customModelData != 0) {
            meta.setCustomModelData(customModelData)
        }

        item.itemMeta = meta
        return item
    }

    private fun getBackButtonSlot(inventorySize: Int): Int {
        return when (inventorySize) {
            27 -> 22 // Center bottom for 3 rows
            36 -> 31 // Center bottom for 4 rows
            45 -> 40 // Center bottom for 5 rows
            54 -> 49 // Center bottom for 6 rows
            else -> -1 // No back button for other sizes
        }
    }

    private fun getStockFromItem(item: ItemStack): Stock? {
        if (!item.hasItemMeta()) return null
        val name = item.itemMeta.displayName
        // Main GUI: "§e주식 이름 §7(ID)"
        // Subscribe GUI: "§e주식 이름 §7(구독 중)" or "§e주식 이름 §7(미구독)"
        val stockIdMatch = "\\((.*?)\\)".toRegex().find(name)
        val stockId = stockIdMatch?.groups?.get(1)?.value
        if (stockId != null && stocks.containsKey(stockId)) {
            return stocks[stockId]
        }
        // Fallback for subscribe GUI items that might not have the ID in the name
        val stockName = name.substringBefore(" §7(")
        return stocks.values.find { it.name == stockName }
    }

    private fun updatePlayerStock(uuid: String, stockId: String, amountDelta: Int, spentDelta: Double) {
        val sql = """
            INSERT INTO player_stocks (uuid, stock_id, amount, total_spent)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid, stock_id) DO UPDATE SET
            amount = amount + ?,
            total_spent = total_spent + ?;
        """.trimIndent()
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                pstmt.setString(2, stockId)
                pstmt.setInt(3, amountDelta)
                pstmt.setDouble(4, spentDelta)
                pstmt.setInt(5, amountDelta)
                pstmt.setDouble(6, spentDelta)
                pstmt.executeUpdate()
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun getPlayerStock(uuid: String, stockId: String): PlayerStock? {
        val sql = "SELECT * FROM player_stocks WHERE uuid = ? AND stock_id = ?;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                pstmt.setString(2, stockId)
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    return PlayerStock(
                        uuid = rs.getString("uuid"),
                        stockId = rs.getString("stock_id"),
                        amount = rs.getInt("amount"),
                        totalSpent = rs.getDouble("total_spent")
                    )
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

    private fun getPlayerPortfolio(uuid: String): List<PlayerStock> {
        val portfolio = mutableListOf<PlayerStock>()
        val sql = "SELECT * FROM player_stocks WHERE uuid = ? AND amount > 0;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    portfolio.add(PlayerStock(
                        uuid = rs.getString("uuid"),
                        stockId = rs.getString("stock_id"),
                        amount = rs.getInt("amount"),
                        totalSpent = rs.getDouble("total_spent")
                    ))
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return portfolio
    }

    private fun addSubscription(uuid: String, stockId: String) {
        val sql = "INSERT OR IGNORE INTO player_subscriptions (uuid, stock_id) VALUES (?, ?);"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                pstmt.setString(2, stockId)
                pstmt.executeUpdate()
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun removeSubscription(uuid: String, stockId: String) {
        val sql = "DELETE FROM player_subscriptions WHERE uuid = ? AND stock_id = ?;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                pstmt.setString(2, stockId)
                pstmt.executeUpdate()
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun getSubscriptions(uuid: String): List<String> {
        val subscriptions = mutableListOf<String>()
        val sql = "SELECT stock_id FROM player_subscriptions WHERE uuid = ?;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    subscriptions.add(rs.getString("stock_id"))
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return subscriptions
    }

    private fun getSubscribedPlayers(stockId: String): List<UUID> {
        val players = mutableListOf<UUID>()
        val sql = "SELECT uuid FROM player_subscriptions WHERE stock_id = ?;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, stockId)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    players.add(UUID.fromString(rs.getString("uuid")))
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return players
    }

    private fun getPlayerDailyTransaction(uuid: String, stockId: String): PlayerDailyTransaction? {
        val sql = "SELECT * FROM player_daily_transactions WHERE uuid = ? AND stock_id = ?;"
        try {
            db.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, uuid)
                pstmt.setString(2, "GLOBAL_TRADE")
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    return PlayerDailyTransaction(
                        uuid = rs.getString("uuid"),
                        stockId = rs.getString("stock_id"),
                        buyCount = rs.getInt("buy_count"),
                        sellCount = rs.getInt("sell_count"),
                        lastUpdatedDate = rs.getString("last_updated_date")
                    )
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

    private fun updatePlayerDailyTransaction(uuid: String, stockId: String, isBuy: Boolean) {
        val today = java.time.LocalDate.now().toString()
        val currentTransaction = getPlayerDailyTransaction(uuid, "GLOBAL_TRADE")

        if (currentTransaction == null || currentTransaction.lastUpdatedDate != today) {
            // New entry or new day, reset counts
            val sql = "INSERT OR REPLACE INTO player_daily_transactions (uuid, stock_id, buy_count, sell_count, last_updated_date) VALUES (?, ?, ?, ?, ?);"
            try {
                db.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, uuid)
                    pstmt.setString(2, "GLOBAL_TRADE")
                    pstmt.setInt(3, if (isBuy) 1 else 0)
                    pstmt.setInt(4, if (!isBuy) 1 else 0)
                    pstmt.setString(5, today)
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                e.printStackTrace()
            }
        } else {
            // Update existing entry for today
            val sql = if (isBuy) {
                "UPDATE player_daily_transactions SET buy_count = buy_count + 1 WHERE uuid = ? AND stock_id = ?;"
            } else {
                "UPDATE player_daily_transactions SET sell_count = sell_count + 1 WHERE uuid = ? AND stock_id = ?;"
            }
            try {
                db.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, uuid)
                    pstmt.setString(2, "GLOBAL_TRADE")
                    pstmt.executeUpdate()
                }
            } catch (e: SQLException) {
                e.printStackTrace()
            }
        }
    }

    private fun getRankings(): List<Ranking> {
        val playerAssets = mutableMapOf<String, Double>()
        val sql = "SELECT uuid, stock_id, amount FROM player_stocks WHERE amount > 0;"
        try {
            db.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    val uuid = rs.getString("uuid")
                    val stock = stocks[rs.getString("stock_id")] ?: continue
                    val amount = rs.getInt("amount")
                    playerAssets[uuid] = (playerAssets[uuid] ?: 0.0) + (stock.price * amount)
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return playerAssets.map { Ranking(it.key, it.value) }.sortedByDescending { it.totalAssets }
    }

    // --- Data Classes ---

        data class Stock(val id: String, val name: String, var price: Double, val fluctuation: Double, val material: Material, val customModelData: Int)
    data class PlayerStock(val uuid: String, val stockId: String, val amount: Int, val totalSpent: Double) {
        val avgPrice: Double get() = if (amount > 0) totalSpent / amount else 0.0
    }
    data class Ranking(val uuid: String, val totalAssets: Double)

    data class PlayerDailyTransaction(
        val uuid: String,
        val stockId: String,
        var buyCount: Int,
        var sellCount: Int,
        var lastUpdatedDate: String
    )

    data class ButtonConfig(
        val material: Material,
        val customModelData: Int,
        val name: String,
        val lore: List<String>
    )

    data class TradeButtonConfig(
        val amount: Int,
        val button: ButtonConfig
    )

    enum class Trend(val multiplier: Double, val display: String) {
        UP(1.5, "§a상승"), DOWN(0.5, "§c하락"), STABLE(1.0, "§e안정");
        var duration: Int = 0
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (command.name.equals("주식", ignoreCase = true)) {
            if (args.size == 1) {
                return mutableListOf("관리").filter { it.startsWith(args[0], true) }.toMutableList()
            } else if (args.size == 2) {
                when (args[0].lowercase()) {
                    "관리" -> return mutableListOf("리로드", "가격설정").filter { it.startsWith(args[1], true) }.toMutableList()
                }
            } else if (args.size == 3) {
                when (args[0].lowercase()) {
                    "관리" -> {
                        if (args[1].lowercase() == "가격설정") {
                            return stocks.keys.filter { it.startsWith(args[2], true) }.toMutableList()
                        }
                    }
                }
            }
        }
        return null
    }
}
