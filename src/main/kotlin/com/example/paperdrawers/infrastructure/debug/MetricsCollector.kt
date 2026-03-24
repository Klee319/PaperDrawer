package com.example.paperdrawers.infrastructure.debug

import java.util.concurrent.atomic.AtomicLong

/**
 * Collects and reports plugin metrics.
 *
 * Why: Provides centralized metric collection for monitoring plugin performance
 * and usage statistics. Uses AtomicLong for thread-safe operations.
 *
 * Usage:
 * - Call increment methods when relevant events occur
 * - Use getReport() to get a formatted string of all metrics
 * - Use reset() to clear all metrics (e.g., for testing or periodic reset)
 *
 * Thread Safety: All operations are thread-safe using AtomicLong.
 */
object MetricsCollector {

    // ========================================
    // Drawer Lifecycle Metrics
    // ========================================

    /**
     * Number of drawers created since plugin start or last reset.
     */
    val drawersCreated: AtomicLong = AtomicLong(0)

    /**
     * Number of drawers destroyed since plugin start or last reset.
     */
    val drawersDestroyed: AtomicLong = AtomicLong(0)

    // ========================================
    // Item Transaction Metrics
    // ========================================

    /**
     * Total number of items inserted into drawers.
     */
    val itemsInserted: AtomicLong = AtomicLong(0)

    /**
     * Total number of items extracted from drawers.
     */
    val itemsExtracted: AtomicLong = AtomicLong(0)

    // ========================================
    // Display Update Metrics
    // ========================================

    /**
     * Number of display entity updates performed.
     */
    val displayUpdates: AtomicLong = AtomicLong(0)

    /**
     * Number of display entities created.
     */
    val displayCreations: AtomicLong = AtomicLong(0)

    /**
     * Number of display entities removed.
     */
    val displayRemovals: AtomicLong = AtomicLong(0)

    // ========================================
    // Cache Metrics
    // ========================================

    /**
     * Number of cache hits (data found in cache).
     */
    val cacheHits: AtomicLong = AtomicLong(0)

    /**
     * Number of cache misses (data not found, had to load from storage).
     */
    val cacheMisses: AtomicLong = AtomicLong(0)

    // ========================================
    // Interaction Metrics
    // ========================================

    /**
     * Number of right-click interactions on drawers.
     */
    val rightClickInteractions: AtomicLong = AtomicLong(0)

    /**
     * Number of left-click interactions on drawers.
     */
    val leftClickInteractions: AtomicLong = AtomicLong(0)

    /**
     * Number of double-click (bulk insert) operations.
     */
    val bulkInsertOperations: AtomicLong = AtomicLong(0)

    /**
     * Number of drawer key (lock/unlock) operations.
     */
    val drawerKeyOperations: AtomicLong = AtomicLong(0)

    // ========================================
    // Increment Methods
    // ========================================

    /**
     * Increment the count of created drawers by 1.
     */
    fun incrementDrawersCreated() {
        drawersCreated.incrementAndGet()
    }

    /**
     * Increment the count of destroyed drawers by 1.
     */
    fun incrementDrawersDestroyed() {
        drawersDestroyed.incrementAndGet()
    }

    /**
     * Increment the count of inserted items.
     *
     * @param count Number of items inserted (must be positive)
     */
    fun incrementItemsInserted(count: Int) {
        if (count > 0) {
            itemsInserted.addAndGet(count.toLong())
        }
    }

    /**
     * Increment the count of extracted items.
     *
     * @param count Number of items extracted (must be positive)
     */
    fun incrementItemsExtracted(count: Int) {
        if (count > 0) {
            itemsExtracted.addAndGet(count.toLong())
        }
    }

    /**
     * Increment the count of display updates by 1.
     */
    fun incrementDisplayUpdates() {
        displayUpdates.incrementAndGet()
    }

    /**
     * Increment the count of display creations by 1.
     */
    fun incrementDisplayCreations() {
        displayCreations.incrementAndGet()
    }

    /**
     * Increment the count of display removals by 1.
     */
    fun incrementDisplayRemovals() {
        displayRemovals.incrementAndGet()
    }

    /**
     * Increment the count of cache hits by 1.
     */
    fun incrementCacheHits() {
        cacheHits.incrementAndGet()
    }

    /**
     * Increment the count of cache misses by 1.
     */
    fun incrementCacheMisses() {
        cacheMisses.incrementAndGet()
    }

    /**
     * Increment the count of right-click interactions by 1.
     */
    fun incrementRightClickInteractions() {
        rightClickInteractions.incrementAndGet()
    }

    /**
     * Increment the count of left-click interactions by 1.
     */
    fun incrementLeftClickInteractions() {
        leftClickInteractions.incrementAndGet()
    }

    /**
     * Increment the count of bulk insert operations by 1.
     */
    fun incrementBulkInsertOperations() {
        bulkInsertOperations.incrementAndGet()
    }

    /**
     * Increment the count of drawer key operations by 1.
     */
    fun incrementDrawerKeyOperations() {
        drawerKeyOperations.incrementAndGet()
    }

    // ========================================
    // Reporting Methods
    // ========================================

    /**
     * Get a formatted report of all metrics.
     *
     * @return Multi-line string containing all metric values
     */
    fun getReport(): String {
        val cacheTotal = cacheHits.get() + cacheMisses.get()
        val cacheHitRate = if (cacheTotal > 0) {
            String.format("%.1f%%", (cacheHits.get().toDouble() / cacheTotal) * 100)
        } else {
            "N/A"
        }

        return buildString {
            appendLine("=== PaperDrawers Metrics ===")
            appendLine()
            appendLine("-- Drawer Lifecycle --")
            appendLine("  Created: ${drawersCreated.get()}")
            appendLine("  Destroyed: ${drawersDestroyed.get()}")
            appendLine("  Net Active: ${drawersCreated.get() - drawersDestroyed.get()}")
            appendLine()
            appendLine("-- Item Transactions --")
            appendLine("  Items Inserted: ${itemsInserted.get()}")
            appendLine("  Items Extracted: ${itemsExtracted.get()}")
            appendLine("  Net Items: ${itemsInserted.get() - itemsExtracted.get()}")
            appendLine()
            appendLine("-- Display System --")
            appendLine("  Updates: ${displayUpdates.get()}")
            appendLine("  Creations: ${displayCreations.get()}")
            appendLine("  Removals: ${displayRemovals.get()}")
            appendLine()
            appendLine("-- Cache Performance --")
            appendLine("  Hits: ${cacheHits.get()}")
            appendLine("  Misses: ${cacheMisses.get()}")
            appendLine("  Hit Rate: $cacheHitRate")
            appendLine()
            appendLine("-- Interactions --")
            appendLine("  Right Clicks: ${rightClickInteractions.get()}")
            appendLine("  Left Clicks: ${leftClickInteractions.get()}")
            appendLine("  Bulk Inserts: ${bulkInsertOperations.get()}")
            appendLine("  Key Operations: ${drawerKeyOperations.get()}")
        }
    }

    /**
     * Get metrics as a map for programmatic access.
     *
     * @return Map of metric names to their current values
     */
    fun getMetricsMap(): Map<String, Long> {
        return mapOf(
            "drawers_created" to drawersCreated.get(),
            "drawers_destroyed" to drawersDestroyed.get(),
            "items_inserted" to itemsInserted.get(),
            "items_extracted" to itemsExtracted.get(),
            "display_updates" to displayUpdates.get(),
            "display_creations" to displayCreations.get(),
            "display_removals" to displayRemovals.get(),
            "cache_hits" to cacheHits.get(),
            "cache_misses" to cacheMisses.get(),
            "right_click_interactions" to rightClickInteractions.get(),
            "left_click_interactions" to leftClickInteractions.get(),
            "bulk_insert_operations" to bulkInsertOperations.get(),
            "drawer_key_operations" to drawerKeyOperations.get()
        )
    }

    /**
     * Reset all metrics to zero.
     *
     * Why: Useful for testing or when administrators want to start
     * fresh metric collection without restarting the server.
     */
    fun reset() {
        drawersCreated.set(0)
        drawersDestroyed.set(0)
        itemsInserted.set(0)
        itemsExtracted.set(0)
        displayUpdates.set(0)
        displayCreations.set(0)
        displayRemovals.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        rightClickInteractions.set(0)
        leftClickInteractions.set(0)
        bulkInsertOperations.set(0)
        drawerKeyOperations.set(0)
    }
}
