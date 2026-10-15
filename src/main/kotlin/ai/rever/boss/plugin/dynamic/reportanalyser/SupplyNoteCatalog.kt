package ai.rever.boss.plugin.dynamic.reportanalyser

enum class SnKind { INSIGHT, DATASET }

/** How a SupplyNote endpoint treats `start_date` / `end_date`. */
enum class SnDates { REQUIRED, OPTIONAL, REFUSED }

data class SnReport(
    val id: String,
    val kind: SnKind,
    val title: String,
    val description: String,
    val dates: SnDates,
    /** Insight pages with `limit` + `offset` (and `meta.has_more`). Datasets page by cursor instead. */
    val offsetPaged: Boolean = false,
    /** Caveats from the integration guide that change how the numbers must be read. */
    val caveat: String? = null,
    /** Table to use in `POST /v2/sql` (datasets only). */
    val table: String? = null,
) {
    val path: String get() = if (kind == SnKind.INSIGHT) "/v2/insights/$id" else "/v2/data/$id"
    val label: String get() = "$title (${if (kind == SnKind.INSIGHT) "insight" else "dataset"})"
}

/**
 * Report catalogue from the SupplyNote Data API integration guide v2.0 (Aug 2026),
 * sections 6 and 7.
 */
object SupplyNoteCatalog {

    const val DEFAULT_BASE_URL = "https://api.supplynote.in"
    const val SECRET_DOMAIN = "supplynote.in"

    val insights = listOf(
        SnReport("outlet-summary", SnKind.INSIGHT, "Outlet summary", "One line per outlet — sales, purchases, wastage and stock position over the range.", SnDates.REQUIRED),
        SnReport("balance-sheet", SnKind.INSIGHT, "Stock balance sheet", "Opening stock, everything that moved, and closing stock, reconciled per outlet (optionally per product).", SnDates.REQUIRED, offsetPaged = true,
            caveat = "Heaviest endpoint in the API; include_products=true returns very many rows."),
        SnReport("current-stock", SnKind.INSIGHT, "Current stock", "What is on hand right now, valued at weighted-average cost.", SnDates.REFUSED, offsetPaged = true,
            caveat = "current_stock is the physical balance and accounting_stock the book balance; variance_value is their drift (a reconciliation finding). Products without a weighted average contribute zero value and are counted in summary.unpriced_products."),
        SnReport("expiry", SnKind.INSIGHT, "Expiry", "Batches with stock left, bucketed by days to expiry and valued.", SnDates.REFUSED, offsetPaged = true,
            caveat = "Buckets are computed from expires_on at query time; no_expiry_date is its own bucket, not expired."),
        SnReport("menu", SnKind.INSIGHT, "Menu engineering", "Stars, plowhorses, puzzles and dogs — what to protect, reprice, promote or drop.", SnDates.REQUIRED, offsetPaged = true,
            caveat = "Items with costed=false have null classification/food_cost; they are excluded from avg_margin_per_unit. Thresholds apply to the whole menu."),
        SnReport("purchase-fulfilment", SnKind.INSIGHT, "Purchase fulfilment", "Ordered versus received, by value and by quantity.", SnDates.REQUIRED,
            caveat = "group_by=product or outlet_product requires category."),
        SnReport("recipe-costing", SnKind.INSIGHT, "Recipe costing", "What one unit of each recipe costs to make, with menu price and food-cost %.", SnDates.REFUSED, offsetPaged = true,
            caveat = "Costs are TAX-EXCLUSIVE (unlike every other endpoint). costing_complete=false means the cost is UNDERSTATED. limit/offset page recipes, not rows."),
        SnReport("stock-trend", SnKind.INSIGHT, "Stock trend", "Stock value per business day across the range.", SnDates.REQUIRED),
        SnReport("vendor-scorecard", SnKind.INSIGHT, "Vendor scorecard", "How well each supplier served you: fulfilment, turnaround, price variance.", SnDates.REQUIRED,
            caveat = "value_gap/short_received_value are quantity problems; price_variance_value is a price problem. Vendors below min_orders are excluded. Internal transfers are excluded."),
        SnReport("wastage", SnKind.INSIGHT, "Wastage", "What was written off, by reason, outlet, category or product, valued at weighted-average cost.", SnDates.REQUIRED),
    )

    val datasets = listOf(
        ds("outlets", "Outlets", "Your outlets, kitchens and stores.", SnDates.REFUSED),
        ds("products", "Products", "Your product catalogue.", SnDates.REFUSED),
        ds("outlet-products", "Outlet products", "Which products are stocked where, with current costing prices.", SnDates.REFUSED,
            "current_stock has drifted for some businesses; anchor on stock-audits plus inventory-log for a reliable figure."),
        ds("recipes", "Recipes", "Your recipes.", SnDates.REFUSED),
        ds("recipe-ingredients", "Recipe ingredients", "Recipe bills of materials.", SnDates.REFUSED),
        ds("batches", "Batches", "Batch and lot records with expiry dates.", SnDates.REFUSED,
            "Stored expiry_level/is_expired can be stale; prefer the expiry insight."),
        ds("business", "Business", "Your business profile (one row).", SnDates.REFUSED),
        ds("daily-valuation", "Daily valuation", "Daily stock valuation per outlet, split by movement reason.", SnDates.OPTIONAL,
            "A large unspecified_value indicates a data-entry gap."),
        ds("daily-sales", "Daily sales", "Daily POS sales per outlet, split by channel.", SnDates.OPTIONAL,
            "net_sales is the revenue figure to use for food-cost percentages; cancelled_sales are already excluded."),
        ds("menu-item-sales", "Menu item sales", "Daily sales per menu item with theoretical ingredient cost.", SnDates.OPTIONAL,
            "is_costed=false means theoretical_food_cost is UNKNOWN (reported as 0) — exclude before computing food-cost %."),
        ds("ingredient-consumption", "Ingredient consumption", "Theoretical daily ingredient consumption from POS sales.", SnDates.OPTIONAL),
        ds("category-sales", "Category sales", "Daily sales and theoretical food cost per menu category.", SnDates.OPTIONAL),
        ds("recipe-metrics", "Recipe metrics", "Latest selling price and food cost per recipe per outlet (snapshot).", SnDates.REFUSED,
            "as_of_day is the last day the recipe sold — old values are still reported."),
        ds("purchase-fulfilment", "Purchase fulfilment lines", "PO lines compared with what was received, invoice figures joined.", SnDates.OPTIONAL),
        ds("inventory-log", "Inventory log", "The stock ledger: every movement of every product at every outlet.", SnDates.REQUIRED,
            "quantity_adjusted is SIGNED (negative = stock out). Exclude is_accounting_update rows when measuring physical flow."),
        ds("stock-audits", "Stock audits", "Physical stock counts — the only measured stock figures.", SnDates.OPTIONAL),
        ds("purchase-orders", "Purchase orders", "Purchase order headers.", SnDates.OPTIONAL),
        ds("purchase-order-lines", "Purchase order lines", "Purchase order lines.", SnDates.OPTIONAL),
        ds("invoices", "Invoices", "Invoices.", SnDates.OPTIONAL),
        ds("returns", "Returns", "Returns.", SnDates.OPTIONAL),
        ds("batch-movements", "Batch movements", "Movements per batch.", SnDates.OPTIONAL),
    )

    val all: List<SnReport> = insights + datasets

    fun find(id: String, kind: SnKind? = null): SnReport? =
        all.firstOrNull { it.id == id && (kind == null || it.kind == kind) }

    /** Domain rules from the guide that apply to every SupplyNote report. */
    val GENERAL_CAVEATS = """
        SupplyNote conventions (from the API guide):
        - Money is INR and tax-inclusive unless meta.units.values_include_tax says otherwise (recipe-costing is tax-exclusive).
        - Quantities are in each product's own base unit (kg, L, pcs…): never sum or compare quantities across different products.
        - Percentages are already scaled 0–100. Dates are IST business days (YYYY-MM-DD).
        - An item with is_costed/costed=false has an UNKNOWN cost, not a zero cost.
    """.trimIndent()

    // A getter, not a stored val: `datasets` is initialised above this line.
    private val TABLES: Map<String, String> get() = mapOf(
        "outlets" to "dim_outlet",
        "products" to "dim_product",
        "outlet-products" to "dim_outlet_product",
        "recipes" to "dim_recipe",
        "recipe-ingredients" to "dim_recipe_ingredient",
        "batches" to "dim_batch",
        "business" to "dim_business",
        "daily-valuation" to "agg_outlet_daily_valuation",
        "daily-sales" to "agg_pos_daily",
        "menu-item-sales" to "agg_menu_item_daily",
        "ingredient-consumption" to "agg_ingredient_daily",
        "category-sales" to "agg_category_daily",
        "recipe-metrics" to "agg_recipe_metrics",
        "purchase-fulfilment" to "agg_po_vs_grn_line",
        "inventory-log" to "fact_inventory_log",
        "stock-audits" to "fact_stock_audit",
        "purchase-orders" to "fact_purchase_order",
        "purchase-order-lines" to "fact_purchase_order_line",
        "invoices" to "fact_invoice",
        "returns" to "fact_return",
        "batch-movements" to "fact_batch_movement"
    )

    private fun ds(id: String, title: String, description: String, dates: SnDates, caveat: String? = null) =
        SnReport(id, SnKind.DATASET, title, description, dates, caveat = caveat, table = TABLES[id]?.let { "sn_public.$it" })
}
