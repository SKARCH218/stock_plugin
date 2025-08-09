package lightstudio.stock_plugin

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.*

class Stock_plugin : JavaPlugin(), CommandExecutor, Listener, TabCompleter {

    private var econ: Economy? = null
    private lateinit var db: Connection
    internal val stocks = mutableMapOf<String, Stock>()
    private val stockTrends = mutableMapOf<String, Trend>()

    // --- Caches ---
    private val playerPortfolios = mutableMapOf<UUID, MutableMap<String, PlayerStock>>()
    private val playerSubscriptions = mutableMapOf<UUID, MutableList<String>>()
    private val playerTransactions = mutableMapOf<UUID, PlayerDailyTransaction>()

    // --- Config values ---
    private var transactionFeePercent: Double = 0.0
    private var itemsAllStructureVoid: Boolean = false
    private var enableTransactionLimits: Boolean = false
    private var dailyBuyLimit: Int = 0
    private var dailySellLimit: Int = 0
    private var transactionLimitResetHours: Int = 24
    private lateinit var mainGuiTitle: String
    private lateinit var portfolioGuiTitle: String
    private lateinit var rankingGuiTitle: String
    private lateinit var subscribeGuiTitle: String
    private lateinit var buyGuiTitleFormat: String
    private lateinit var sellGuiTitleFormat: String

    // --- Button Configurations ---
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
            logger.severe("Vault not found! Disabling plugin.")
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

        server.onlinePlayers.forEach { player -> loadPlayerData(player.uniqueId) }

        startStockScheduler()
        startPeriodicSaveTask()
        logger.info("Stock Plugin Enabled.")
    }

    override fun onDisable() {
        logger.info("Saving all player data...")
        saveAllPlayerData(false)
        try {
            if (this::db.isInitialized && !db.isClosed) db.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        logger.info("Stock Plugin Disabled.")
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        loadPlayerData(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        object : BukkitRunnable() {
            override fun run() {
                savePlayerData(uuid)
                Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable {
                    playerPortfolios.remove(uuid)
                    playerSubscriptions.remove(uuid)
                    playerTransactions.remove(uuid)
                })
            }
        }.runTaskAsynchronously(this)
    }

    private fun loadPlayerData(uuid: UUID) {
        object : BukkitRunnable() {
            override fun run() {
                val portfolio = getPlayerPortfolioFromDb(uuid.toString())
                val subscriptions = getSubscriptionsFromDb(uuid.toString())
                val transaction = getPlayerDailyTransactionFromDb(uuid.toString())

                Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable {
                    playerPortfolios[uuid] = portfolio.associateBy { it.stockId }.toMutableMap()
                    playerSubscriptions[uuid] = subscriptions.toMutableList()
                    transaction?.let { playerTransactions[uuid] = it }
                })
            }
        }.runTaskAsynchronously(this)
    }

    private fun savePlayerData(uuid: UUID) {
        playerPortfolios[uuid]?.values?.toList()?.let { savePlayerPortfolioToDb(uuid.toString(), it) }
        playerSubscriptions[uuid]?.toList()?.let { savePlayerSubscriptionsToDb(uuid.toString(), it) }
        playerTransactions[uuid]?.let { savePlayerTransactionToDb(uuid.toString(), it) }
    }

    private fun saveAllPlayerData(async: Boolean) {
        val onlinePlayerUUIDs = server.onlinePlayers.map { it.uniqueId }
        if (async) {
            object : BukkitRunnable() {
                override fun run() {
                    onlinePlayerUUIDs.forEach { uuid -> savePlayerData(uuid) }
                }
            }.runTaskAsynchronously(this)
        } else {
            onlinePlayerUUIDs.forEach { uuid -> savePlayerData(uuid) }
        }
    }

    private fun startPeriodicSaveTask() {
        val saveInterval = 20L * 60 * 60
        object : BukkitRunnable() {
            override fun run() {
                saveAllPlayerData(true)
            }
        }.runTaskTimerAsynchronously(this, saveInterval, saveInterval)
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
        dailyBuyLimit = config.getInt("stock-transaction-limits.buy", 10)
        dailySellLimit = config.getInt("stock-transaction-limits.sell", 10)
        transactionLimitResetHours = config.getInt("stock-transaction-limits.reset-hours", 24)
        loadMessages()
    }

    private fun loadMessages() {
        val langFile = File(dataFolder, "lang.yml")
        if (!langFile.exists()) saveResource("lang.yml", false)
        val langConfig = YamlConfiguration.loadConfiguration(langFile)
        messages.clear()
        langConfig.getKeys(true).forEach { key ->
            if (langConfig.isString(key)) {
                messages[key] = langConfig.getString(key)!!
            }
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
                        uuid TEXT PRIMARY KEY NOT NULL,
                        buy_count INTEGER NOT NULL DEFAULT 0,
                        sell_count INTEGER NOT NULL DEFAULT 0,
                        transaction_reset_timestamp BIGINT NOT NULL DEFAULT 0
                    );
                """.trimIndent())
                 stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_transaction_limits (
                        uuid TEXT PRIMARY KEY NOT NULL,
                        buy_limit INTEGER,
                        sell_limit INTEGER
                    );
                """.trimIndent())

                val meta = db.metaData
                if (!meta.getColumns(null, null, "player_daily_transactions", "transaction_reset_timestamp").next()) {
                    stmt.execute("ALTER TABLE player_daily_transactions ADD COLUMN transaction_reset_timestamp BIGINT NOT NULL DEFAULT 0")
                }
                 if (!meta.getColumns(null, null, "player_stocks", "total_spent").next()) {
                    stmt.execute("ALTER TABLE player_stocks ADD COLUMN total_spent REAL NOT NULL DEFAULT 0")
                }
                if (meta.getColumns(null, null, "player_daily_transactions", "stock_id").next()) {
                    logger.info("Performing one-time migration of transaction data...")
                    stmt.execute("ALTER TABLE player_daily_transactions RENAME TO player_daily_transactions_old;")
                    stmt.execute("""
                        CREATE TABLE player_daily_transactions (
                            uuid TEXT PRIMARY KEY NOT NULL,
                            buy_count INTEGER NOT NULL DEFAULT 0,
                            sell_count INTEGER NOT NULL DEFAULT 0,
                            transaction_reset_timestamp BIGINT NOT NULL DEFAULT 0
                        );
                    """)
                    stmt.execute("""
                        INSERT INTO player_daily_transactions (uuid, buy_count, sell_count, transaction_reset_timestamp)
                        SELECT uuid, SUM(buy_count), SUM(sell_count), MAX(transaction_reset_timestamp)
                        FROM player_daily_transactions_old
                        GROUP BY uuid;
                    """)
                    stmt.execute("DROP TABLE player_daily_transactions_old;")
                    logger.info("Transaction data migration complete.")
                }
            }
        } catch (e: Exception) {
            logger.severe("Database setup failed!")
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
        val onlinePlayerUUIDs = server.onlinePlayers.map { it.uniqueId }
        val subscribedPlayersCache = mutableMapOf<String, List<UUID>>()

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
                    val subscribed = subscribedPlayersCache.getOrPut(id) { 
                        onlinePlayerUUIDs.filter { uuid -> 
                            playerSubscriptions[uuid]?.contains(id) == true 
                        }
                    }
                    subscribed.forEach { uuid ->
                        Bukkit.getPlayer(uuid)?.sendMessage(message)
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
            else -> openMainGui(sender)
        }
        return true
    }

    private fun handleAdminCommand(sender: Player, args: List<String>) {
        if (args.isEmpty()) {
            sender.sendMessage(messages["admin-command-usage"] ?: "§c사용법: /주식 관리 <리로드|가격설정|한도설정|한도확인|한도초기화|주식조회|개인주식수정>")
            return
        }
        when (args[0].lowercase()) {
            "리로드" -> {
                loadConfigOptions()
                loadStockConfig()
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
            "한도설정" -> {
                if (!enableTransactionLimits) {
                    sender.sendMessage(messages["admin-limits-disabled"] ?: "§c거래 한도 기능이 config.yml에서 비활성화되어 있습니다.")
                    return
                }
                if (args.size < 4) {
                    sender.sendMessage(messages["admin-setlimit-usage"] ?: "§c사용법: /주식 관리 한도설정 <플레이어> <구매|판매> <수량>")
                    return
                }
                val playerName = args[1]
                val type = args[2]
                val amount = args[3].toIntOrNull()
                val target = Bukkit.getOfflinePlayer(playerName)

                if (!target.hasPlayedBefore() && !target.isOnline) {
                     sender.sendMessage(messages["player-not-found"] ?: "§c플레이어를 찾을 수 없습니다.")
                     return
                }

                if (amount == null || amount < 0) {
                    sender.sendMessage(messages["admin-invalid-amount"] ?: "§c수량은 0 이상의 정수여야 합니다.")
                    return
                }
                if (type !in listOf("구매", "판매")) {
                    sender.sendMessage(messages["admin-invalid-limit-type"] ?: "§c타입은 '구매' 또는 '판매' 여야 합니다.")
                    return
                }

                setPlayerLimit(target.uniqueId, type == "구매", amount)
                sender.sendMessage(messages["admin-setlimit-success"]
                    ?.replace("%player%", target.name ?: playerName)
                    ?.replace("%type%", type)
                    ?.replace("%amount%", amount.toString()) ?: "§a${target.name ?: playerName}님의 ${type} 한도를 ${amount}로 설정했습니다.")
            }
            "한도확인" -> {
                if (!enableTransactionLimits) {
                    sender.sendMessage(messages["admin-limits-disabled"] ?: "§c거래 한도 기능이 config.yml에서 비활성화되어 있습니다.")
                    return
                }
                if (args.size < 2) {
                    sender.sendMessage(messages["admin-checklimit-usage"] ?: "§c사용법: /주식 관리 한도확인 <플레이어>")
                    return
                }
                val playerName = args[1]
                val target = Bukkit.getOfflinePlayer(playerName)
                if (!target.hasPlayedBefore() && !target.isOnline) {
                    sender.sendMessage(messages["player-not-found"] ?: "§c플레이어를 찾을 수 없습니다.")
                    return
                }

                val limits = getPlayerLimit(target.uniqueId)
                val transaction = getPlayerDailyTransaction(target.uniqueId)
                val (buyCount, sellCount) = if (transaction != null && System.currentTimeMillis() < transaction.transactionResetTimestamp) {
                    Pair(transaction.buyCount, transaction.sellCount)
                } else {
                    Pair(0, 0)
                }

                val buyLimit = limits.first ?: dailyBuyLimit
                val sellLimit = limits.second ?: dailySellLimit

                sender.sendMessage("§e--- ${target.name ?: playerName}님의 거래 한도 정보 ---")
                sender.sendMessage("§f구매 한도: §a${buyCount} / ${buyLimit}")
                sender.sendMessage("§f판매 한도: §c${sellCount} / ${sellLimit}")
            }
            "한도초기화" -> {
                if (!enableTransactionLimits) {
                    sender.sendMessage(messages["admin-limits-disabled"] ?: "§c거래 한도 기능이 config.yml에서 비활성화되어 있습니다.")
                    return
                }
                if (args.size < 2) {
                    sender.sendMessage(messages["admin-resetlimit-usage"] ?: "§c사용법: /주식 관리 한도초기화 <플레이어>")
                    return
                }
                val playerName = args[1]
                val target = Bukkit.getOfflinePlayer(playerName)
                if (!target.hasPlayedBefore() && !target.isOnline) {
                    sender.sendMessage(messages["player-not-found"] ?: "§c플레이어를 찾을 수 없습니다.")
                    return
                }
                resetPlayerDailyTransaction(target.uniqueId)
                sender.sendMessage(messages["admin-resetlimit-success"]?.replace("%player%", target.name ?: playerName) ?: "§a${target.name ?: playerName}님의 거래 횟수를 초기화했습니다.")
            }
            "주식조회" -> {
                if (args.size < 2) {
                    sender.sendMessage("§c사용법: /주식 관리 주식조회 <플레이어>")
                    return
                }
                val playerName = args[1]
                val target = Bukkit.getOfflinePlayer(playerName)
                if (!target.hasPlayedBefore() && !target.isOnline) {
                    sender.sendMessage(messages["player-not-found"] ?: "§c플레이어를 찾을 수 없습니다.")
                    return
                }
                openPlayerPortfolioGui(sender, target)
            }
            "개인주식수정" -> {
                if (args.size < 5) {
                    sender.sendMessage("§c사용법: /주식 관리 개인주식수정 <플레이어> <주식ID> <set|add|delete> <수량>")
                    return
                }
                val playerName = args[1]
                val stockId = args[2]
                val action = args[3].lowercase()
                val amount = args[4].toIntOrNull()

                val target = Bukkit.getOfflinePlayer(playerName)
                if (!target.hasPlayedBefore() && !target.isOnline) {
                    sender.sendMessage(messages["player-not-found"] ?: "§c플레이어를 찾을 수 없습니다.")
                    return
                }
                if (stocks[stockId] == null) {
                    sender.sendMessage("§c존재하지 않는 주식 ID입니다.")
                    return
                }
                if (action !in listOf("set", "add", "delete")) {
                    sender.sendMessage("§c잘못된 액션입니다. set, add, delete 중 하나를 사용하세요.")
                    return
                }
                if (amount == null || amount < 0) {
                    sender.sendMessage("§c수량은 0 이상의 정수여야 합니다.")
                    return
                }

                modifyPlayerStock(sender, target, stockId, action, amount)
            }
            else -> sender.sendMessage(messages["admin-unknown-command"] ?: "§c알 수 없는 관리 명령어입니다.")
        }
    }

    private fun openMainGui(player: Player) {
        playerGuiContext[player.uniqueId]?.clear()
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
        val view = event.view
        val currentItem = event.currentItem ?: return
        val clickedItemName = currentItem.itemMeta?.displayName ?: ""

        when (view.title) {
            mainGuiTitle -> {
                event.isCancelled = true
                when (clickedItemName) {
                    portfolioButton.name -> openPortfolioGui(player) { openMainGui(player) }
                    subscribeButton.name -> openSubscribeGui(player) { openMainGui(player) }
                    rankingButton.name -> openRankingGui(player) { openMainGui(player) }
                    else -> {
                        val stock = getStockFromItem(currentItem) ?: return
                        when (event.click) {
                            ClickType.LEFT -> openTradeGui(player, stock, "buy") { openMainGui(player) }
                            ClickType.RIGHT -> openTradeGui(player, stock, "sell") { openMainGui(player) }
                            else -> {}
                        }
                    }
                }
            }
            portfolioGuiTitle, rankingGuiTitle -> {
                event.isCancelled = true
                if (clickedItemName == backButton.name) {
                    playerGuiContext[player.uniqueId]?.pop()?.invoke()
                }
            }
            subscribeGuiTitle -> {
                event.isCancelled = true
                if (clickedItemName == backButton.name) {
                    playerGuiContext[player.uniqueId]?.pop()?.invoke()
                } else {
                    val stock = getStockFromItem(currentItem) ?: return
                    val isSubscribed = getSubscriptions(player.uniqueId).contains(stock.id)
                    if (isSubscribed) {
                        removeSubscription(player.uniqueId, stock.id)
                        player.sendMessage(messages["subscription-cancelled"]?.replace("%stock_name%", stock.name) ?: "§a${stock.name}§7 구독을 취소했습니다.")
                    } else {
                        addSubscription(player.uniqueId, stock.id)
                        player.sendMessage(messages["subscription-added"]?.replace("%stock_name%", stock.name) ?: "§a${stock.name}§7을(를) 구독했습니다.")
                    }
                    openSubscribeGui(player) { openMainGui(player) } // Refresh
                }
            }
            else -> {
                if (view.title.startsWith(buyGuiTitleFormat.substringBefore("%stock_name%")) ||
                    view.title.startsWith(sellGuiTitleFormat.substringBefore("%stock_name%")) || view.title.endsWith("'s Portfolio")) {
                    event.isCancelled = true
                    if (clickedItemName == backButton.name) {
                        playerGuiContext[player.uniqueId]?.pop()?.invoke()
                    } else if (!view.title.endsWith("'s Portfolio")) {
                        handleTrade(player, currentItem, view.title)
                    }
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
        val playerUUID = player.uniqueId

        if (enableTransactionLimits) {
            val limits = getPlayerLimit(playerUUID)
            val finalDailyBuyLimit = limits.first ?: dailyBuyLimit
            val finalDailySellLimit = limits.second ?: dailySellLimit

            val dailyTransaction = getPlayerDailyTransaction(playerUUID)
            val now = System.currentTimeMillis()

            val (currentBuyCount, currentSellCount) = if (dailyTransaction != null && now < dailyTransaction.transactionResetTimestamp) {
                Pair(dailyTransaction.buyCount, dailyTransaction.sellCount)
            } else {
                Pair(0, 0)
            }

            if (isBuy) {
                if (currentBuyCount >= finalDailyBuyLimit) {
                    player.sendMessage(messages["transaction-limit-exceeded-buy"]?.replace("%limit%", finalDailyBuyLimit.toString()) ?: "§c오늘 구매 한도를 초과했습니다. (일일 한도: ${finalDailyBuyLimit}회)")
                    player.closeInventory()
                    return
                }
            } else { // isSell
                if (currentSellCount >= finalDailySellLimit) {
                    player.sendMessage(messages["transaction-limit-exceeded-sell"]?.replace("%limit%", finalDailySellLimit.toString()) ?: "§c오늘 판매 한도를 초과했습니다. (일일 한도: ${finalDailySellLimit}회)")
                    player.closeInventory()
                    return
                }
            }
        }

        val clickedItemName = item.itemMeta?.displayName ?: return

        val amount: Int = if (isBuy && clickedItemName.startsWith(buyAllButton.name.substringBefore("%amount%"))) {
            (econ!!.getBalance(player) / (stock.price * (1 + transactionFeePercent / 100.0))).toInt()
        } else if (!isBuy && clickedItemName.startsWith(sellAllButton.name.substringBefore("%amount%"))) {
            getPlayerStock(playerUUID, stock.id)?.amount ?: 0
        } else {
            tradeButtons.find { tb -> clickedItemName == tb.button.name.replace("%amount%", tb.amount.toString()) }?.amount ?: return
        }

        if (amount <= 0) {
            player.sendMessage(if(isBuy) (messages["not-enough-money"] ?: "§c구매할 돈이 부족하거나, 구매할 수 있는 주식이 없습니다.") else (messages["not-enough-stock-to-sell"] ?: "§c판매할 주식이 없습니다."))
            player.closeInventory()
            return
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
            updatePlayerStock(playerUUID, stock.id, amount, totalPrice)

            if (enableTransactionLimits) {
                updatePlayerDailyTransaction(playerUUID, true)
                val limits = getPlayerLimit(playerUUID)
                val finalDailyBuyLimit = limits.first ?: dailyBuyLimit
                val dailyTransaction = getPlayerDailyTransaction(playerUUID)
                val currentBuyCount = dailyTransaction?.buyCount ?: 0
                val remainingBuysStr = (finalDailyBuyLimit - currentBuyCount).coerceAtLeast(0).toString()
                player.sendMessage(messages["buy-success"]
                    ?.replace("%stock_name%", stock.name)
                    ?.replace("%amount%", amount.toString())
                    ?.replace("%remaining_transactions%", remainingBuysStr)
                    ?: "§a${stock.name} ${amount}주를 매수했습니다. (남은 구매 횟수: $remainingBuysStr)")
            } else {
                player.sendMessage(messages["buy-success-simple"]
                    ?.replace("%stock_name%", stock.name)
                    ?.replace("%amount%", amount.toString())
                    ?: "§a${stock.name} ${amount}주를 성공적으로 매수했습니다.")
            }
        } else { // Sell
            val currentAmount = getPlayerStock(playerUUID, stock.id)?.amount ?: 0
            if (currentAmount < amount) {
                player.sendMessage(messages["not-enough-stock"]?.replace("%owned%", currentAmount.toString())?.replace("%requested%", amount.toString()) ?: "§c보유 주식이 부족합니다. (보유: ${currentAmount}주, 요청: ${amount}주)")
                return
            }
            val revenue = totalPrice * (1 - fee)
            econ!!.depositPlayer(player, revenue)
            val avgPrice = getPlayerStock(playerUUID, stock.id)?.avgPrice ?: 0.0
            val spentToRemove = avgPrice * amount
            updatePlayerStock(playerUUID, stock.id, -amount, -spentToRemove)

            if (enableTransactionLimits) {
                updatePlayerDailyTransaction(playerUUID, false)
                val limits = getPlayerLimit(playerUUID)
                val finalDailySellLimit = limits.second ?: dailySellLimit
                val dailyTransaction = getPlayerDailyTransaction(playerUUID)
                val currentSellCount = dailyTransaction?.sellCount ?: 0
                val remainingSellsStr = (finalDailySellLimit - currentSellCount).coerceAtLeast(0).toString()
                player.sendMessage(messages["sell-success"]
                    ?.replace("%stock_name%", stock.name)
                    ?.replace("%amount%", amount.toString())
                    ?.replace("%remaining_transactions%", remainingSellsStr)
                    ?: "§a${stock.name} ${amount}주를 매도했습니다. (남은 판매 횟수: $remainingSellsStr)")
            } else {
                 player.sendMessage(messages["sell-success-simple"]
                    ?.replace("%stock_name%", stock.name)
                    ?.replace("%amount%", amount.toString())
                    ?: "§a${stock.name} ${amount}주를 성공적으로 매도했습니다.")
            }
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
            inv.setItem(16, item)
        } else {
            val currentAmount = getPlayerStock(player.uniqueId, stock.id)?.amount ?: 0
            val item = createGuiItem(sellAllButton, mapOf("%amount%" to currentAmount.toString()))
            inv.setItem(16, item)
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
        
        val portfolio = getPlayerPortfolio(player.uniqueId)
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

        items.take(45).forEachIndexed { i, item -> inv.setItem(i, item) }
        inv.setItem(49, createGuiItem(portfolioTotalAssetButton, mapOf("%total_asset%" to String.format("%,.2f", totalAsset))))
        val backButtonSlot = getBackButtonSlot(inv.size)
        if (backButtonSlot != -1) {
            inv.setItem(backButtonSlot, createGuiItem(backButton))
        }
        player.openInventory(inv)
    }

    private fun openPlayerPortfolioGui(admin: Player, target: OfflinePlayer) {
        val title = "§l${target.name}'s Portfolio"
        val inv = Bukkit.createInventory(null, 54, title)
        admin.sendMessage("§7Loading ${target.name}'s portfolio...")

        object : BukkitRunnable() {
            override fun run() {
                val portfolio = if (target.isOnline) {
                    getPlayerPortfolio(target.uniqueId)
                } else {
                    getPlayerPortfolioFromDb(target.uniqueId.toString())
                }

                var totalAsset = 0.0
                val items = portfolio.map { pStock ->
                    val stock = stocks[pStock.stockId] ?: return@map null
                    val currentVal = stock.price * pStock.amount
                    totalAsset += currentVal
                    val profit = currentVal - pStock.totalSpent
                    val profitPercent = if (pStock.totalSpent > 0) (profit / pStock.totalSpent) * 100 else 0.0
                    val color = if (profit >= 0) "§a" else "§c"
                    createGuiItem(stock.material, "§e${stock.name}", listOf(
                        "§f보유 수량: §6${pStock.amount}주",
                        "§f매수 평균가: §6${String.format("%,.2f", pStock.avgPrice)}원",
                        "§f현재가: §6${String.format("%,.2f", stock.price)}원",
                        "§f평가 금액: ${color}${String.format("%,.2f", currentVal)}원",
                        "§f평가 손익: ${color}${String.format("%,.2f", profit)}원 (${String.format("%.2f", profitPercent)}%)"
                    ), stock.customModelData)
                }.filterNotNull()

                Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable {
                    items.take(45).forEachIndexed { i, item -> inv.setItem(i, item) }
                    inv.setItem(49, createGuiItem(portfolioTotalAssetButton, mapOf("%total_asset%" to String.format("%,.2f", totalAsset))))
                    admin.openInventory(inv)
                })
            }
        }.runTaskAsynchronously(this)
    }

    private fun modifyPlayerStock(admin: Player, target: OfflinePlayer, stockId: String, action: String, amount: Int) {
        object : BukkitRunnable() {
            override fun run() {
                val targetUUID = target.uniqueId
                val currentStock = if (target.isOnline) {
                    getPlayerStock(targetUUID, stockId)
                } else {
                    getPlayerPortfolioFromDb(targetUUID.toString()).find { it.stockId == stockId }
                }

                val currentAmount = currentStock?.amount ?: 0
                val newAmount = when (action) {
                    "set" -> amount
                    "add" -> currentAmount + amount
                    "delete" -> (currentAmount - amount).coerceAtLeast(0)
                    else -> currentAmount
                }

                if (target.isOnline) {
                    val spentDelta = if (currentAmount != 0) (currentStock!!.totalSpent / currentAmount) * (newAmount - currentAmount) else 0.0
                    updatePlayerStock(targetUUID, stockId, newAmount - currentAmount, spentDelta)
                } else {
                    val avgPrice = if (currentAmount != 0) currentStock!!.totalSpent / currentAmount else 0.0
                    val newSpent = avgPrice * newAmount
                    val sql = "INSERT INTO player_stocks (uuid, stock_id, amount, total_spent) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, stock_id) DO UPDATE SET amount = ?, total_spent = ?;"
                    try {
                        db.prepareStatement(sql).use { pstmt ->
                            pstmt.setString(1, targetUUID.toString())
                            pstmt.setString(2, stockId)
                            pstmt.setInt(3, newAmount)
                            pstmt.setDouble(4, newSpent)
                            pstmt.setInt(5, newAmount)
                            pstmt.setDouble(6, newSpent)
                            pstmt.executeUpdate()
                        }
                    } catch (e: SQLException) {
                        e.printStackTrace()
                        Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable { admin.sendMessage("§c데이터베이스 오류가 발생했습니다.") })
                        return
                    }
                }
                Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable { admin.sendMessage("§a${target.name}님의 ${stocks[stockId]?.name} 주식을 ${newAmount}주로 성공적으로 수정했습니다.") })
            }
        }.runTaskAsynchronously(this)
    }

    private fun openRankingGui(player: Player, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val inv = Bukkit.createInventory(null, 54, rankingGuiTitle)
        player.sendMessage(messages["stock-ranking-loading-info"] ?: "§7랭킹 정보를 불러오는 중...")
        
        object: BukkitRunnable() {
            override fun run() {
                val rankings = getRankingsFromDb()
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
                
                Bukkit.getScheduler().runTask(this@Stock_plugin, Runnable {
                    items.forEachIndexed { i, item -> inv.setItem(i, item) }
                    val backButtonSlot = getBackButtonSlot(inv.size)
                    if (backButtonSlot != -1) {
                        inv.setItem(backButtonSlot, createGuiItem(backButton))
                    }
                    player.openInventory(inv)
                })
            }
        }.runTaskAsynchronously(this)
    }

    private fun openSubscribeGui(player: Player, parentGui: () -> Unit) {
        playerGuiContext.getOrPut(player.uniqueId) { Stack() }.push(parentGui)
        val inv = Bukkit.createInventory(null, 54, subscribeGuiTitle)
        val playerSubscriptions = getSubscriptions(player.uniqueId)

        stocks.values.forEachIndexed { i, stock ->
            if (i < 45) {
                val isSubscribed = playerSubscriptions.contains(stock.id)
                val buttonConf = if (isSubscribed) subscribeToggleOnButton else subscribeToggleOffButton
                val item = createGuiItem(buttonConf, mapOf("%stock_name%" to stock.name, "stock_id" to stock.id))
                inv.setItem(i, item)
            }
        }

        val backButtonSlot = getBackButtonSlot(inv.size)
        if (backButtonSlot != -1) {
            inv.setItem(backButtonSlot, createGuiItem(backButton))
        }
        player.openInventory(inv)
    }

    private fun createStockItem(stock: Stock): ItemStack {
        val item = ItemStack(stock.material)
        val meta = item.itemMeta!!
        val trend = stockTrends[stock.id] ?: Trend.STABLE
        meta.setDisplayName("§e${stock.name} §7(${stock.id})")
        if (stock.customModelData != 0) {
            meta.setCustomModelData(stock.customModelData)
        }
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
        var finalLore = lore.toMutableList()

        placeholders.forEach { (key, value) ->
            finalName = finalName.replace(key, value)
            finalLore = finalLore.map { it.replace(key, value) }.toMutableList()
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
            27 -> 22
            36 -> 31
            45 -> 40
            54 -> 49
            else -> -1
        }
    }

    private fun getStockFromItem(item: ItemStack): Stock? {
        if (!item.hasItemMeta()) return null
        val name = item.itemMeta.displayName
        val stockIdMatch = """\((.*?)\)""".toRegex().find(name)
        val stockId = stockIdMatch?.groups?.get(1)?.value
        if (stockId != null && stocks.containsKey(stockId)) {
            return stocks[stockId]
        }
        val stockName = ChatColor.stripColor(name.substringBefore(" §7("))
        return stocks.values.find { it.name == stockName }
    }

    private fun updatePlayerStock(uuid: UUID, stockId: String, amountDelta: Int, spentDelta: Double) {
        val portfolio = playerPortfolios.getOrPut(uuid) { mutableMapOf() }
        val currentStock = portfolio.getOrPut(stockId) { PlayerStock(uuid.toString(), stockId, 0, 0.0) }
        val newAmount = currentStock.amount + amountDelta
        val newSpent = currentStock.totalSpent + spentDelta
        portfolio[stockId] = currentStock.copy(amount = newAmount, totalSpent = newSpent)
    }

    private fun getPlayerStock(uuid: UUID, stockId: String): PlayerStock? {
        return playerPortfolios[uuid]?.get(stockId)
    }

    private fun getPlayerPortfolio(uuid: UUID): List<PlayerStock> {
        return playerPortfolios[uuid]?.values?.toList() ?: emptyList()
    }

    private fun addSubscription(uuid: UUID, stockId: String) {
        val subscriptions = playerSubscriptions.getOrPut(uuid) { mutableListOf() }
        if (!subscriptions.contains(stockId)) {
            subscriptions.add(stockId)
        }
    }

    private fun removeSubscription(uuid: UUID, stockId: String) {
        playerSubscriptions[uuid]?.remove(stockId)
    }

    private fun getSubscriptions(uuid: UUID): List<String> {
        return playerSubscriptions[uuid] ?: emptyList()
    }

    private fun getPlayerDailyTransaction(uuid: UUID): PlayerDailyTransaction? {
        return playerTransactions[uuid]
    }

    private fun updatePlayerDailyTransaction(uuid: UUID, isBuy: Boolean) {
        val now = System.currentTimeMillis()
        val currentTransaction = playerTransactions.getOrPut(uuid) { PlayerDailyTransaction(uuid.toString(), 0, 0, 0) }

        if (now >= currentTransaction.transactionResetTimestamp) {
            val resetTimestamp = now + (transactionLimitResetHours * 3600 * 1000L)
            playerTransactions[uuid] = currentTransaction.copy(
                buyCount = if (isBuy) 1 else 0,
                sellCount = if (!isBuy) 1 else 0,
                transactionResetTimestamp = resetTimestamp
            )
        } else {
            if (isBuy) {
                currentTransaction.buyCount++
            } else {
                currentTransaction.sellCount++
            }
        }
    }
    
    private fun resetPlayerDailyTransaction(uuid: UUID) {
        playerTransactions.remove(uuid)
        object: BukkitRunnable() {
            override fun run() {
                val sql = "DELETE FROM player_daily_transactions WHERE uuid = ?;"
                try {
                    db.prepareStatement(sql).use { it.setString(1, uuid.toString()); it.executeUpdate() }
                } catch (e: SQLException) {
                    e.printStackTrace()
                }
            }
        }.runTaskAsynchronously(this)
    }

    private fun setPlayerLimit(uuid: UUID, isBuy: Boolean, limit: Int) {
        object: BukkitRunnable() {
            override fun run() {
                val column = if (isBuy) "buy_limit" else "sell_limit"
                val sql = "INSERT INTO player_transaction_limits (uuid, $column) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET $column = excluded.$column;"
                try {
                    db.prepareStatement(sql).use { it.setString(1, uuid.toString()); it.setInt(2, limit); it.executeUpdate() }
                } catch (e: SQLException) {
                    e.printStackTrace()
                }
            }
        }.runTaskAsynchronously(this)
    }

    private fun getPlayerLimit(uuid: UUID): Pair<Int?, Int?> {
        val sql = "SELECT buy_limit, sell_limit FROM player_transaction_limits WHERE uuid = ?;"
        try {
            db.prepareStatement(sql).use {
                it.setString(1, uuid.toString())
                val rs = it.executeQuery()
                if (rs.next()) {
                    val buyLimit = rs.getObject("buy_limit") as? Int
                    val sellLimit = rs.getObject("sell_limit") as? Int
                    return Pair(buyLimit, sellLimit)
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return Pair(null, null)
    }

    private fun getPlayerPortfolioFromDb(uuid: String): List<PlayerStock> {
        val portfolio = mutableListOf<PlayerStock>()
        val sql = "SELECT * FROM player_stocks WHERE uuid = ? AND amount > 0;"
        try {
            db.prepareStatement(sql).use {
                it.setString(1, uuid)
                val rs = it.executeQuery()
                while (rs.next()) { portfolio.add(PlayerStock(rs.getString("uuid"), rs.getString("stock_id"), rs.getInt("amount"), rs.getDouble("total_spent")))
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return portfolio
    }
    
    private fun savePlayerPortfolioToDb(uuid: String, portfolio: List<PlayerStock>) {
        val sql = """
            INSERT INTO player_stocks (uuid, stock_id, amount, total_spent) VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid, stock_id) DO UPDATE SET amount = excluded.amount, total_spent = excluded.total_spent;
        """.trimIndent()
        try {
            db.autoCommit = false
            db.prepareStatement(sql).use {
                for (stock in portfolio) {
                    it.setString(1, uuid)
                    it.setString(2, stock.stockId)
                    it.setInt(3, stock.amount)
                    it.setDouble(4, stock.totalSpent)
                    it.addBatch()
                }
                it.executeBatch()
            }
            val ownedStockIds = portfolio.map { "'${it.stockId}'" }.joinToString(",")
            if (portfolio.isNotEmpty()) {
                val cleanupSql = "DELETE FROM player_stocks WHERE uuid = ? AND stock_id NOT IN ($ownedStockIds);"
                 db.prepareStatement(cleanupSql).use { it.setString(1, uuid); it.executeUpdate() }
            } else {
                val deleteAllSql = "DELETE FROM player_stocks WHERE uuid = ?;"
                db.prepareStatement(deleteAllSql).use { it.setString(1, uuid); it.executeUpdate() }
            }
            db.commit()
        } catch (e: SQLException) {
            e.printStackTrace()
            try { db.rollback() } catch (ex: SQLException) { ex.printStackTrace() }
        } finally {
            try { db.autoCommit = true } catch (e: SQLException) { e.printStackTrace() }
        }
    }

    private fun getSubscriptionsFromDb(uuid: String): List<String> {
        val subscriptions = mutableListOf<String>()
        val sql = "SELECT stock_id FROM player_subscriptions WHERE uuid = ?;"
        try {
            db.prepareStatement(sql).use {
                it.setString(1, uuid)
                val rs = it.executeQuery()
                while (rs.next()) { subscriptions.add(rs.getString("stock_id")) }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return subscriptions
    }
    
    private fun savePlayerSubscriptionsToDb(uuid: String, subscriptions: List<String>) {
        val deleteSql = "DELETE FROM player_subscriptions WHERE uuid = ?;"
        val insertSql = "INSERT OR IGNORE INTO player_subscriptions (uuid, stock_id) VALUES (?, ?);"
        try {
            db.autoCommit = false
            db.prepareStatement(deleteSql).use { it.setString(1, uuid); it.executeUpdate() }
            if (subscriptions.isNotEmpty()) {
                db.prepareStatement(insertSql).use {
                    for (stockId in subscriptions) {
                        it.setString(1, uuid)
                        it.setString(2, stockId)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
            }
            db.commit()
        } catch (e: SQLException) {
            e.printStackTrace()
            try { db.rollback() } catch (ex: SQLException) { ex.printStackTrace() }
        } finally {
            try { db.autoCommit = true } catch (e: SQLException) { e.printStackTrace() }
        }
    }

    private fun getPlayerDailyTransactionFromDb(uuid: String): PlayerDailyTransaction? {
        val sql = "SELECT * FROM player_daily_transactions WHERE uuid = ?;"
        try {
            db.prepareStatement(sql).use {
                it.setString(1, uuid)
                val rs = it.executeQuery()
                if (rs.next()) {
                    return PlayerDailyTransaction(rs.getString("uuid"), rs.getInt("buy_count"), rs.getInt("sell_count"), rs.getLong("transaction_reset_timestamp"))
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun savePlayerTransactionToDb(uuid: String, transaction: PlayerDailyTransaction) {
        val sql = """
            INSERT INTO player_daily_transactions (uuid, buy_count, sell_count, transaction_reset_timestamp) VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET buy_count = excluded.buy_count, sell_count = excluded.sell_count, transaction_reset_timestamp = excluded.transaction_reset_timestamp;
        """.trimIndent()
        try {
            db.prepareStatement(sql).use {
                it.setString(1, uuid)
                it.setInt(2, transaction.buyCount)
                it.setInt(3, transaction.sellCount)
                it.setLong(4, transaction.transactionResetTimestamp)
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun getRankingsFromDb(): List<Ranking> {
        val playerAssets = mutableMapOf<String, Double>()
        val sql = "SELECT uuid, stock_id, amount FROM player_stocks WHERE amount > 0;"
        try {
            db.createStatement().use {
                val rs = it.executeQuery(sql)
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

    data class Stock(val id: String, val name: String, var price: Double, val fluctuation: Double, val material: Material, val customModelData: Int)
    data class PlayerStock(val uuid: String, val stockId: String, val amount: Int, val totalSpent: Double) {
        val avgPrice: Double get() = if (amount > 0) totalSpent / amount else 0.0
    }
    data class Ranking(val uuid: String, val totalAssets: Double)
    data class PlayerDailyTransaction(val uuid: String, var buyCount: Int, var sellCount: Int, var transactionResetTimestamp: Long)
    data class ButtonConfig(val material: Material, val customModelData: Int, val name: String, val lore: List<String>)
    data class TradeButtonConfig(val amount: Int, val button: ButtonConfig)

    enum class Trend(val multiplier: Double, val display: String) {
        UP(1.5, "§a상승"), DOWN(0.5, "§c하락"), STABLE(1.0, "§e안정");
        var duration: Int = 0
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (!command.name.equals("주식", ignoreCase = true) || !sender.hasPermission("stock.admin")) return null

        if (args.size == 1) {
            return mutableListOf("관리").filter { it.startsWith(args[0], true) }.toMutableList()
        }

        if (args[0].equals("관리", ignoreCase = true)) {
            when (args.size) {
                2 -> {
                    return mutableListOf("리로드", "가격설정", "한도설정", "한도확인", "한도초기화", "주식조회", "개인주식수정")
                        .filter { it.startsWith(args[1], true) }.toMutableList()
                }
                3 -> {
                    when (args[1].lowercase()) {
                        "가격설정" -> return stocks.keys.filter { it.startsWith(args[2], true) }.toMutableList()
                        "한도설정", "한도확인", "한도초기화", "주식조회", "개인주식수정" -> {
                            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) }.toMutableList()
                        }
                    }
                }
                4 -> {
                    when (args[1].lowercase()) {
                        "한도설정" -> return mutableListOf("구매", "판매").filter { it.startsWith(args[3], true) }.toMutableList()
                        "개인주식수정" -> return stocks.keys.filter { it.startsWith(args[3], true) }.toMutableList()
                    }
                }
                5 -> {
                    if (args[1].lowercase() == "개인주식수정") {
                        return mutableListOf("set", "add", "delete").filter { it.startsWith(args[4], true) }.toMutableList()
                    }
                }
            }
        }
        return null
    }
}
