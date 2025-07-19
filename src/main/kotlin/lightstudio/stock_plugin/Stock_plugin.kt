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

class Stock_plugin : JavaPlugin(), CommandExecutor, Listener {

    private var econ: Economy? = null
    private lateinit var db: Connection
    internal val stocks = mutableMapOf<String, Stock>() // Changed to internal
    private val stockTrends = mutableMapOf<String, Trend>()

    // Config values
    private var transactionFeePercent: Double = 0.0
    private var itemsAllStructureVoid: Boolean = false
    private lateinit var mainGuiTitle: String
    private lateinit var portfolioGuiTitle: String
    private lateinit var rankingGuiTitle: String
    private val guiButtonConfigs = mutableMapOf<String, ButtonConfig>()
    private val messages = mutableMapOf<String, String>()

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
        server.pluginManager.registerEvents(this, this)

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            StockPlaceholders(this).register()
            logger.info("Successfully hooked into PlaceholderAPI.")
        }

        startStockScheduler()
        logger.info(messages["plugin-enabled"] ?: "Stock Plugin Enabled.")
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
        itemsAllStructureVoid = config.getBoolean("gui.items-all-structure-void", false)

        guiButtonConfigs.clear()
        config.getConfigurationSection("gui.buttons")?.getKeys(false)?.forEach { key ->
            val section = config.getConfigurationSection("gui.buttons.$key")!!
            guiButtonConfigs[key] = ButtonConfig(
                material = Material.valueOf(section.getString("material", "STONE")!!),
                customModelData = section.getInt("custom-model-data", 0),
                name = section.getString("name", "")!!,
                lore = section.getStringList("lore")
            )
        }
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
                fluctuation = section.getDouble("fluctuation", 100.0)
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
            val trend = stockTrends[id] ?: Trend.STABLE
            if (Random().nextDouble() < 0.1) {
                stockTrends[id] = Trend.values().random().apply { duration = Random().nextInt(5) + 3 }
            } else {
                trend.duration--
                if (trend.duration <= 0) stockTrends[id] = Trend.STABLE
            }
            val change = (Random().nextDouble() * 2 - 1) * stock.fluctuation * trend.multiplier
            stock.price = (stock.price + change).coerceAtLeast(1.0)
        }
    }

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        if (args.isNotEmpty() && args[0].equals("관리", ignoreCase = true)) {
            if (!sender.hasPermission("stock.admin")) {
                sender.sendMessage(messages["permission-denied"] ?: "§c권한이 없습니다.")
                return true
            }
            handleAdminCommand(sender, args.drop(1))
        } else {
            openMainGui(sender)
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
        val inv = Bukkit.createInventory(null, 54, mainGuiTitle)
        stocks.values.forEachIndexed { i, stock ->
            if (i < 45) inv.setItem(i, createStockItem(stock))
        }
        inv.setItem(48, createGuiItem(guiButtonConfigs["portfolio"]!!.material, guiButtonConfigs["portfolio"]!!.name, guiButtonConfigs["portfolio"]!!.lore, "portfolio"))
        inv.setItem(50, createGuiItem(guiButtonConfigs["ranking"]!!.material, guiButtonConfigs["ranking"]!!.name, guiButtonConfigs["ranking"]!!.lore, "ranking"))
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val viewTitle = event.view.title
        val clickedItem = event.currentItem ?: return

        event.isCancelled = true

        when (viewTitle) {
            mainGuiTitle -> {
                val clickedItemName = clickedItem.itemMeta?.displayName
                when (clickedItemName) {
                    guiButtonConfigs["portfolio"]?.name -> openPortfolioGui(player)
                    guiButtonConfigs["ranking"]?.name -> openRankingGui(player)
                    else -> {
                        val stock = getStockFromItem(clickedItem) ?: return
                        when (event.click) {
                            ClickType.LEFT -> openTradeGui(player, stock, "buy")
                            ClickType.RIGHT -> openTradeGui(player, stock, "sell")
                            else -> {}
                        }
                    }
                }
            }
            portfolioGuiTitle, rankingGuiTitle -> return // No actions needed
            else -> {
                if (viewTitle.contains("구매") || viewTitle.contains("판매")) {
                    handleTrade(player, clickedItem, viewTitle)
                }
            }
        }
    }

    private fun handleTrade(player: Player, item: ItemStack, viewTitle: String) {
        val amount = item.itemMeta?.displayName?.filter { it.isDigit() }?.toIntOrNull() ?: return
        val stockName = viewTitle.substring(2).substringBefore(" ")
        val stock = stocks.values.find { it.name == stockName } ?: return
        val isBuy = viewTitle.contains("구매")

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
            player.sendMessage(messages["buy-success"]?.replace("%stock_name%", stock.name)?.replace("%amount%", amount.toString()) ?: "§a${stock.name} ${amount}주를 매수했습니다.")
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
            player.sendMessage(messages["sell-success"]?.replace("%stock_name%", stock.name)?.replace("%amount%", amount.toString()) ?: "§a${stock.name} ${amount}주를 매도했습니다.")
        }
        player.closeInventory()
    }

    private fun openTradeGui(player: Player, stock: Stock, type: String) {
        val title = if (type == "buy") messages["buy-gui-title"]?.replace("%stock_name%", stock.name) ?: "§a${stock.name} 구매" else messages["sell-gui-title"]?.replace("%stock_name%", stock.name) ?: "§c${stock.name} 판매"
        val inv = Bukkit.createInventory(null, 27, title)
        listOf(1, 5, 10, 50, 100, 500).forEachIndexed { i, amount ->
            inv.setItem(i, createGuiItem(Material.GREEN_STAINED_GLASS_PANE, "§e${amount}주", emptyList()))
        }
        player.openInventory(inv)
    }

    private fun openPortfolioGui(player: Player) {
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
                createGuiItem(Material.BOOK, "§e${stock.name}", listOf(
                    messages["portfolio-stock-quantity"]?.replace("%amount%", pStock.amount.toString()) ?: "§f보유 수량: §6${pStock.amount}주",
                    messages["portfolio-avg-price"]?.replace("%avg_price%", String.format("%,.2f", pStock.avgPrice)) ?: "§f매수 평균가: §6${String.format("%,.2f", pStock.avgPrice)}원",
                    messages["portfolio-current-price"]?.replace("%current_price%", String.format("%,.2f", stock.price)) ?: "§f현재가: §6${String.format("%,.2f", stock.price)}원",
                    messages["portfolio-current-value"]?.replace("%current_value%", String.format("%,.2f", currentVal))?.replace("%color%", color) ?: "§f평가 금액: ${color}${String.format("%,.2f", currentVal)}원",
                    messages["portfolio-profit-loss"]?.replace("%profit_loss%", String.format("%,.2f", profit))?.replace("%profit_loss_percent%", String.format("%.2f", profitPercent))?.replace("%color%", color) ?: "§f평가 손익: ${color}${String.format("%,.2f", profit)}원 (${String.format("%.2f", profitPercent)}%)"
                ))
            }.filterNotNull()

            Bukkit.getScheduler().runTask(this, Runnable {
                items.forEachIndexed { i, item -> if (i < 45) inv.setItem(i, item) }
                inv.setItem(49, createGuiItem(Material.GOLD_INGOT, messages["portfolio-total-asset"]?.replace("%total_asset%", String.format("%,.2f", totalAsset)) ?: "§e총 자산 평가액", listOf(messages["portfolio-total-asset"]?.replace("%total_asset%", String.format("%,.2f", totalAsset)) ?: "§6${String.format("%,.2f", totalAsset)}원")))
                player.openInventory(inv)
            })
        })
    }

    private fun openRankingGui(player: Player) {
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
                player.openInventory(inv)
            })
        })
    }

    // --- Helper and Data Access Functions ---

    private fun createStockItem(stock: Stock): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta
        val trend = stockTrends[stock.id] ?: Trend.STABLE
        meta.setDisplayName("§e${stock.name} §7(${stock.id})")
        meta.lore = listOf(
            "§f현재가: §6${String.format("%,.2f", stock.price)}원",
            "§f추세: ${trend.display}",
            "",
            "§a좌클릭: 구매하기",
            "§c우클릭: 판매하기"
        )
        item.itemMeta = meta
        return item
    }

    private fun createGuiItem(mat: Material, name: String, lore: List<String>, buttonKey: String? = null): ItemStack {
        val finalMaterial = if (itemsAllStructureVoid) Material.STRUCTURE_VOID else mat
        val item = ItemStack(finalMaterial)
        val meta = item.itemMeta

        val config = buttonKey?.let { guiButtonConfigs[it] }

        meta.setDisplayName(config?.name ?: name)
        meta.lore = config?.lore ?: lore
        config?.customModelData?.let { meta.setCustomModelData(it) }

        item.itemMeta = meta
        return item
    }

    private fun getStockFromItem(item: ItemStack): Stock? {
        if (item.type != Material.PAPER || !item.hasItemMeta()) return null
        val stockId = item.itemMeta.displayName.substringAfter('(')?.substringBefore(')') ?: return null
        return stocks[stockId]
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

    data class Stock(val id: String, val name: String, var price: Double, val fluctuation: Double)
    data class PlayerStock(val uuid: String, val stockId: String, val amount: Int, val totalSpent: Double) {
        val avgPrice: Double get() = if (amount > 0) totalSpent / amount else 0.0
    }
    data class Ranking(val uuid: String, val totalAssets: Double)

    data class ButtonConfig(
        val material: Material,
        val customModelData: Int,
        val name: String,
        val lore: List<String>
    )

    enum class Trend(val multiplier: Double, val display: String) {
        UP(1.5, "§a상승"), DOWN(0.5, "§c하락"), STABLE(1.0, "§e안정");
        var duration: Int = 0
    }
}