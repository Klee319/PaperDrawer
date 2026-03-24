package com.example.paperdrawers.infrastructure.debug

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.infrastructure.display.DrawerDisplayManager
import org.bukkit.Bukkit
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Centralized debug and diagnostics manager.
 *
 * Why: Provides a single entry point for all debug-related operations,
 * including debug logging, performance measurement, and diagnostic utilities.
 *
 * Features:
 * - Debug mode toggle for verbose logging
 * - Performance metrics tracking with timing measurements
 * - Drawer inspection utilities
 * - Display entity diagnostics
 *
 * Thread Safety: Uses ConcurrentHashMap for metric storage and volatile for debug flag.
 *
 * @property plugin Plugin instance for accessing server resources
 * @property logger Logger for output
 */
class DebugManager(
    private val plugin: Plugin,
    private val logger: Logger
) {

    // ========================================
    // Debug Mode State
    // ========================================

    /**
     * Flag indicating whether debug mode is enabled.
     *
     * When enabled, additional debug messages are logged and
     * more detailed information is provided in commands.
     */
    @Volatile
    var isDebugEnabled: Boolean = false
        private set

    // ========================================
    // Performance Metrics Storage
    // ========================================

    /**
     * Performance metric data for a named operation.
     *
     * @property count Number of times this operation was executed
     * @property totalTimeNs Total execution time in nanoseconds
     * @property minTimeNs Minimum execution time in nanoseconds
     * @property maxTimeNs Maximum execution time in nanoseconds
     */
    data class MetricData(
        var count: Long = 0,
        var totalTimeNs: Long = 0,
        var minTimeNs: Long = Long.MAX_VALUE,
        var maxTimeNs: Long = 0
    ) {
        /**
         * Get average execution time in nanoseconds.
         */
        fun getAverageTimeNs(): Double {
            return if (count > 0) totalTimeNs.toDouble() / count else 0.0
        }

        /**
         * Get average execution time in milliseconds.
         */
        fun getAverageTimeMs(): Double {
            return getAverageTimeNs() / 1_000_000.0
        }

        /**
         * Get minimum execution time in milliseconds.
         */
        fun getMinTimeMs(): Double {
            return if (minTimeNs == Long.MAX_VALUE) 0.0 else minTimeNs / 1_000_000.0
        }

        /**
         * Get maximum execution time in milliseconds.
         */
        fun getMaxTimeMs(): Double {
            return maxTimeNs / 1_000_000.0
        }

        /**
         * Get total execution time in milliseconds.
         */
        fun getTotalTimeMs(): Double {
            return totalTimeNs / 1_000_000.0
        }
    }

    /**
     * Map of operation names to their metric data.
     */
    private val metrics: ConcurrentHashMap<String, MetricData> = ConcurrentHashMap()

    // ========================================
    // Debug Mode Control
    // ========================================

    /**
     * Enable debug mode.
     */
    fun enableDebug() {
        isDebugEnabled = true
        logger.info("[Debug] Debug mode enabled")
    }

    /**
     * Disable debug mode.
     */
    fun disableDebug() {
        isDebugEnabled = false
        logger.info("[Debug] Debug mode disabled")
    }

    /**
     * Toggle debug mode.
     *
     * @return The new state of debug mode
     */
    fun toggleDebug(): Boolean {
        isDebugEnabled = !isDebugEnabled
        logger.info("[Debug] Debug mode ${if (isDebugEnabled) "enabled" else "disabled"}")
        return isDebugEnabled
    }

    // ========================================
    // Debug Logging
    // ========================================

    /**
     * Log a debug message if debug mode is enabled.
     *
     * @param message The message to log
     */
    fun debug(message: String) {
        if (isDebugEnabled) {
            logger.info("[DEBUG] $message")
        }
    }

    /**
     * Log a debug message with lazy evaluation.
     *
     * Why: Avoids expensive string concatenation when debug mode is disabled.
     *
     * @param messageProvider Lambda that produces the message
     */
    fun debugLazy(messageProvider: () -> String) {
        if (isDebugEnabled) {
            logger.info("[DEBUG] ${messageProvider()}")
        }
    }

    // ========================================
    // Performance Measurement
    // ========================================

    /**
     * Measure the execution time of a block and record the metric.
     *
     * Why: Allows tracking performance of specific operations to identify
     * bottlenecks and monitor plugin health.
     *
     * @param name Name of the operation being measured
     * @param block The code block to measure
     * @return The result of the code block
     */
    inline fun <T> measure(name: String, block: () -> T): T {
        val startTime = System.nanoTime()
        try {
            return block()
        } finally {
            val endTime = System.nanoTime()
            val elapsed = endTime - startTime
            recordMetric(name, elapsed)
        }
    }

    /**
     * Record a timing metric for an operation.
     *
     * @param name Name of the operation
     * @param timeNs Execution time in nanoseconds
     */
    fun recordMetric(name: String, timeNs: Long) {
        val metric = metrics.computeIfAbsent(name) { MetricData() }
        synchronized(metric) {
            metric.count++
            metric.totalTimeNs += timeNs
            if (timeNs < metric.minTimeNs) {
                metric.minTimeNs = timeNs
            }
            if (timeNs > metric.maxTimeNs) {
                metric.maxTimeNs = timeNs
            }
        }
    }

    /**
     * Get a formatted report of all performance metrics.
     *
     * @return Multi-line string containing metric data
     */
    fun getMetricsReport(): String {
        if (metrics.isEmpty()) {
            return "No performance metrics recorded."
        }

        return buildString {
            appendLine("=== Performance Metrics ===")
            appendLine()
            appendLine(
                String.format(
                    "%-30s %10s %12s %12s %12s %12s",
                    "Operation", "Count", "Avg(ms)", "Min(ms)", "Max(ms)", "Total(ms)"
                )
            )
            appendLine("-".repeat(90))

            metrics.entries
                .sortedByDescending { it.value.totalTimeNs }
                .forEach { (name, data) ->
                    appendLine(
                        String.format(
                            "%-30s %10d %12.3f %12.3f %12.3f %12.3f",
                            name.take(30),
                            data.count,
                            data.getAverageTimeMs(),
                            data.getMinTimeMs(),
                            data.getMaxTimeMs(),
                            data.getTotalTimeMs()
                        )
                    )
                }
        }
    }

    /**
     * Reset all performance metrics.
     */
    fun resetMetrics() {
        metrics.clear()
        logger.info("[Debug] Performance metrics reset")
    }

    /**
     * Get raw metrics data for programmatic access.
     *
     * @return Map of operation names to MetricData
     */
    fun getMetrics(): Map<String, MetricData> {
        return metrics.toMap()
    }

    // ========================================
    // Drawer Inspection
    // ========================================

    /**
     * Inspect a drawer and return detailed information.
     *
     * @param drawer The drawer to inspect
     * @return Multi-line string with drawer details
     */
    fun inspectDrawer(drawer: DrawerBlock): String {
        return buildString {
            appendLine("=== Drawer Inspection ===")
            appendLine("ID: ${drawer.id}")
            appendLine("Location: ${drawer.getLocationKey()}")
            appendLine("Type: ${drawer.type.name}")
            appendLine("Facing: ${drawer.facing}")
            appendLine("Owner: ${drawer.ownerId ?: "None"}")
            appendLine("Created: ${java.util.Date(drawer.createdAt)}")
            appendLine()
            appendLine("-- Slots --")
            drawer.slots.forEachIndexed { index, slot ->
                appendLine("  Slot $index:")
                appendLine("    Material: ${slot.storedMaterial?.name ?: "Empty"}")
                appendLine("    Count: ${slot.itemCount} / ${slot.maxCapacity}")
                appendLine("    Locked: ${slot.isLocked}")
                if (slot.isLocked) {
                    appendLine("    Locked Material: ${slot.lockedMaterial?.name ?: "N/A"}")
                }
            }
            appendLine()
            appendLine("Total Items: ${drawer.getTotalItemCount()}")
            appendLine("Empty: ${drawer.isEmpty()}")
        }
    }

    /**
     * Inspect display entities for a drawer.
     *
     * @param drawer The drawer to inspect
     * @return Multi-line string with display entity details
     */
    fun inspectDisplay(drawer: DrawerBlock): String {
        val locationKey = drawer.getLocationKey()
        val world = drawer.location.world

        return buildString {
            appendLine("=== Display Inspection ===")
            appendLine("Drawer Location: $locationKey")

            if (world == null) {
                appendLine("World is null - cannot inspect displays")
                return@buildString
            }

            // Find display entities near the drawer
            val nearbyEntities = world.getNearbyEntities(
                drawer.location.clone().add(0.5, 0.5, 0.5),
                1.5,
                1.5,
                1.5
            )

            val itemDisplays = nearbyEntities.filterIsInstance<ItemDisplay>()
            val textDisplays = nearbyEntities.filterIsInstance<TextDisplay>()

            appendLine()
            appendLine("-- ItemDisplay Entities (${itemDisplays.size}) --")
            itemDisplays.forEach { display ->
                appendLine("  UUID: ${display.uniqueId}")
                appendLine("    Location: ${display.location.x}, ${display.location.y}, ${display.location.z}")
                appendLine("    Item: ${display.itemStack?.type?.name ?: "None"}")
                appendLine("    Scale: ${display.transformation.scale}")
            }

            appendLine()
            appendLine("-- TextDisplay Entities (${textDisplays.size}) --")
            textDisplays.forEach { display ->
                appendLine("  UUID: ${display.uniqueId}")
                appendLine("    Location: ${display.location.x}, ${display.location.y}, ${display.location.z}")
                appendLine("    Text: ${display.text()}")
            }
        }
    }

    // ========================================
    // Display Entity Utilities
    // ========================================

    /**
     * List all drawer-related display entities in all worlds.
     *
     * @return Information about all display entities
     */
    fun listAllDisplayEntities(): String {
        return buildString {
            appendLine("=== All Drawer Display Entities ===")
            appendLine()

            var totalItemDisplays = 0
            var totalTextDisplays = 0

            for (world in Bukkit.getWorlds()) {
                val itemDisplays = world.entities.filterIsInstance<ItemDisplay>().filter { display ->
                    display.persistentDataContainer.has(
                        org.bukkit.NamespacedKey(plugin, "display_drawer_id"),
                        org.bukkit.persistence.PersistentDataType.STRING
                    )
                }

                val textDisplays = world.entities.filterIsInstance<TextDisplay>().filter { display ->
                    display.persistentDataContainer.has(
                        org.bukkit.NamespacedKey(plugin, "text_display_drawer_id"),
                        org.bukkit.persistence.PersistentDataType.STRING
                    )
                }

                if (itemDisplays.isNotEmpty() || textDisplays.isNotEmpty()) {
                    appendLine("World: ${world.name}")
                    appendLine("  ItemDisplays: ${itemDisplays.size}")
                    appendLine("  TextDisplays: ${textDisplays.size}")

                    if (isDebugEnabled) {
                        itemDisplays.take(5).forEach { display ->
                            appendLine("    - ItemDisplay at ${display.location.blockX}, ${display.location.blockY}, ${display.location.blockZ}")
                        }
                        if (itemDisplays.size > 5) {
                            appendLine("    ... and ${itemDisplays.size - 5} more")
                        }
                    }
                    appendLine()
                }

                totalItemDisplays += itemDisplays.size
                totalTextDisplays += textDisplays.size
            }

            appendLine("-- Summary --")
            appendLine("Total ItemDisplays: $totalItemDisplays")
            appendLine("Total TextDisplays: $totalTextDisplays")
            appendLine("Total Entities: ${totalItemDisplays + totalTextDisplays}")
        }
    }

    // ========================================
    // Cache Statistics
    // ========================================

    /**
     * Get cache statistics from MetricsCollector.
     *
     * @return Formatted cache statistics
     */
    fun getCacheStatistics(): String {
        val hits = MetricsCollector.cacheHits.get()
        val misses = MetricsCollector.cacheMisses.get()
        val total = hits + misses

        val hitRate = if (total > 0) {
            String.format("%.2f%%", (hits.toDouble() / total) * 100)
        } else {
            "N/A"
        }

        return buildString {
            appendLine("=== Cache Statistics ===")
            appendLine("Cache Hits: $hits")
            appendLine("Cache Misses: $misses")
            appendLine("Total Lookups: $total")
            appendLine("Hit Rate: $hitRate")
        }
    }

    // ========================================
    // Display Manager Integration
    // ========================================

    /**
     * Get display statistics from DrawerDisplayManager.
     *
     * @param displayManager The display manager to query
     * @return Formatted display statistics
     */
    fun getDisplayStatistics(displayManager: DrawerDisplayManager): String {
        val stats = displayManager.getDisplayStatistics()

        return buildString {
            appendLine("=== Display Statistics ===")
            appendLine("Total Drawers with Displays: ${stats["totalDrawers"]}")
            appendLine("Total Item Displays: ${stats["totalItemDisplays"]}")
            appendLine("Total Text Displays: ${stats["totalTextDisplays"]}")
            appendLine("Strategies in Use: ${stats["strategiesInUse"]}")
        }
    }

    // ========================================
    // Combined Report
    // ========================================

    /**
     * Get a comprehensive debug report.
     *
     * @param displayManager Optional display manager for additional stats
     * @return Full debug report
     */
    fun getFullReport(displayManager: DrawerDisplayManager? = null): String {
        return buildString {
            appendLine("===================================")
            appendLine("   PaperDrawers Debug Report")
            appendLine("===================================")
            appendLine()
            appendLine("Debug Mode: ${if (isDebugEnabled) "ENABLED" else "DISABLED"}")
            appendLine()

            // Metrics from MetricsCollector
            appendLine(MetricsCollector.getReport())
            appendLine()

            // Performance metrics
            appendLine(getMetricsReport())
            appendLine()

            // Cache statistics
            appendLine(getCacheStatistics())
            appendLine()

            // Display statistics
            if (displayManager != null) {
                appendLine(getDisplayStatistics(displayManager))
                appendLine()
            }

            // Display entity count
            appendLine(listAllDisplayEntities())
        }
    }
}
