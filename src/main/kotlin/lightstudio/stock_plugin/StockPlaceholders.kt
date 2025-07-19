package lightstudio.stock_plugin

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class StockPlaceholders(private val plugin: Stock_plugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String {
        return "stock"
    }

    override fun getAuthor(): String {
        return plugin.description.authors.joinToString()
    }

    override fun getVersion(): String {
        return plugin.description.version
    }

    override fun persist(): Boolean {
        return true // PAPI가 재로딩되어도 이 확장 기능이 유지됩니다.
    }

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (params.startsWith("price_")) {
            val stockId = params.substringAfter("price_")
            val stock = plugin.stocks[stockId]
            return if (stock != null) {
                String.format("%.2f", stock.price)
            } else {
                "Invalid Stock"
            }
        }
        return null
    }
}