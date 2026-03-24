package com.example.paperdrawers.infrastructure.item

import com.example.paperdrawers.domain.model.DrawerSlot
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.infrastructure.config.DrawerCapacityConfig
import com.example.paperdrawers.infrastructure.config.DrawerDisplayConfig
import com.example.paperdrawers.infrastructure.persistence.DrawerDataKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Deserialized slot data read from an item's PDC.
 *
 * Why: Provides a lightweight, immutable representation of slot contents
 * without requiring a full DrawerSlot (which needs maxCapacity, etc.).
 *
 * @property index Slot index (zero-based)
 * @property storedMaterial Material stored in this slot (null for locked empty slots)
 * @property itemCount Number of items stored
 * @property isLocked Whether the slot is locked
 * @property lockedMaterial Material the slot is locked to (only when isLocked is true)
 * @property storedItemTemplate Deserialized item template with full metadata (amount=1)
 */
data class StoredSlotData(
    val index: Int,
    val storedMaterial: Material?,
    val itemCount: Int,
    val isLocked: Boolean = false,
    val lockedMaterial: Material? = null,
    val storedItemTemplate: ItemStack? = null
)

/**
 * Factory for creating drawer items with proper metadata.
 *
 * Why: Centralizes drawer item creation logic to ensure consistency.
 * All drawer items must have proper PDC tags for identification and
 * Adventure API formatting for display.
 */
object DrawerItemFactory {

    // ========================================
    // Display Color Constants (Tier-based)
    // ========================================

    private val DISPLAY_COLORS: Map<DrawerType, NamedTextColor> = mapOf(
        DrawerType.SINGLE_TIER_1 to NamedTextColor.WHITE,       // Basic: WHITE
        DrawerType.SINGLE_TIER_2 to NamedTextColor.GOLD,        // Copper: GOLD
        DrawerType.SINGLE_TIER_3 to NamedTextColor.GRAY,        // Iron: GRAY
        DrawerType.SINGLE_TIER_4 to NamedTextColor.YELLOW,      // Gold: YELLOW
        DrawerType.SINGLE_TIER_5 to NamedTextColor.AQUA,        // Diamond: AQUA
        DrawerType.SINGLE_TIER_6 to NamedTextColor.DARK_RED,    // Netherite: DARK_RED
        DrawerType.SINGLE_TIER_7 to NamedTextColor.LIGHT_PURPLE, // Creative: LIGHT_PURPLE
        DrawerType.VOID to NamedTextColor.DARK_GRAY              // Void: DARK_GRAY
    )

    /**
     * config.ymlから読み込んだ表示設定。
     *
     * Why: 設定ファイルによるカスタマイズを可能にするため、
     * initialize() で注入される。null の場合はハードコードされたデフォルト値を使用する。
     */
    private var displayConfig: DrawerDisplayConfig? = null

    /**
     * DrawerDisplayConfig を注入して表示設定を有効化する。
     *
     * Why: プラグイン起動時に config.yml から読み込んだ設定を
     * ファクトリに渡すことで、アイテム生成時にカスタム表示を適用する。
     *
     * @param config 表示設定インスタンス
     */
    fun initialize(config: DrawerDisplayConfig) {
        displayConfig = config
    }

    // ========================================
    // Public API Methods
    // ========================================

    /**
     * Creates a drawer item with proper metadata and display information.
     *
     * Why: Drawer items need PDC tags for identification when placed,
     * and formatted display names/lore for player visibility.
     *
     * @param type The type of drawer to create
     * @param amount The number of items to create (1-64)
     * @return ItemStack configured as a drawer item
     * @throws IllegalArgumentException if amount is not in valid range
     */
    fun createDrawerItem(type: DrawerType, amount: Int = 1): ItemStack {
        require(amount in 1..64) {
            "Amount must be between 1 and 64, but was: $amount"
        }

        val item = ItemStack(Material.BARREL, amount)

        item.editMeta { meta ->
            // Set display name with Adventure API
            val displayName = buildDisplayName(type)
            meta.displayName(displayName)

            // Set lore explaining capacity
            val lore = buildLore(type)
            meta.lore(lore)

            // Set PDC tag for drawer type identification
            val pdc = meta.persistentDataContainer
            pdc.set(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING, type.name)
        }

        return item
    }

    /**
     * Creates a drawer item with stored contents encoded in PDC.
     *
     * Why: When a drawer is broken, its contents should be preserved inside
     * the dropped item (shulker-box-like behavior). Slot data is serialized
     * into the item's PDC so it can be restored when the drawer is placed again.
     *
     * @param type The type of drawer to create
     * @param slots The list of drawer slots containing stored items
     * @return ItemStack configured as a drawer item with contents
     */
    fun createDrawerItemWithContents(type: DrawerType, slots: List<DrawerSlot>): ItemStack {
        val item = createDrawerItem(type)

        val hasContents = slots.any { !it.isEmpty() || it.isLocked }

        if (!hasContents) {
            return item
        }

        item.editMeta { meta ->
            val pdc = meta.persistentDataContainer

            // Flag indicating this drawer item carries stored contents
            pdc.set(DrawerDataKeys.DRAWER_HAS_CONTENTS, PersistentDataType.BYTE, 1.toByte())

            for (slot in slots) {
                val idx = slot.index

                // Store material (if present)
                val material = slot.storedMaterial
                if (material != null) {
                    pdc.set(DrawerDataKeys.slotMaterial(idx), PersistentDataType.STRING, material.name)
                }

                // Store item count
                pdc.set(DrawerDataKeys.slotCount(idx), PersistentDataType.INTEGER, slot.itemCount)

                // Store lock state
                if (slot.isLocked) {
                    pdc.set(DrawerDataKeys.slotLocked(idx), PersistentDataType.BYTE, 1.toByte())
                    val lockedMat = slot.lockedMaterial
                    if (lockedMat != null) {
                        pdc.set(DrawerDataKeys.slotLockedMaterial(idx), PersistentDataType.STRING, lockedMat.name)
                    }
                }

                // Store item template as serialized bytes (for enchantments, NBT, etc.)
                val template = slot.storedItemTemplate
                if (template != null) {
                    pdc.set(DrawerDataKeys.slotItemTemplate(idx), PersistentDataType.BYTE_ARRAY, template.serializeAsBytes())
                }
            }

            // Update lore to show stored contents summary
            val lore = buildLoreWithContents(type, slots)
            meta.lore(lore)
        }

        return item
    }

    /**
     * Checks if an ItemStack is a drawer item.
     *
     * Why: Required for placement validation and special handling
     * of drawer items throughout the plugin.
     *
     * @param item The item to check
     * @return true if the item is a drawer item, false otherwise
     */
    fun isDrawerItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.BARREL) {
            return false
        }

        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer

        return pdc.has(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING)
    }

    /**
     * Gets the drawer type from an ItemStack.
     *
     * Why: Determines which type of drawer should be created
     * when the item is placed.
     *
     * @param item The item to get the type from
     * @return The DrawerType if the item is a drawer item, null otherwise
     */
    fun getDrawerType(item: ItemStack?): DrawerType? {
        if (item == null || item.type != Material.BARREL) {
            return null
        }

        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer

        val typeName = pdc.get(DrawerDataKeys.DRAWER_TYPE, PersistentDataType.STRING)
            ?: return null

        return try {
            DrawerType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            // Invalid drawer type stored in PDC
            null
        }
    }

    /**
     * Reads stored slot data from an item's PDC.
     *
     * Why: When a drawer item with stored contents is placed, the slot data
     * must be deserialized to restore the drawer's previous state.
     * Returns null if the item has no stored contents flag.
     *
     * @param item The item to read slot data from
     * @return List of StoredSlotData if contents exist, null otherwise
     */
    fun getStoredSlots(item: ItemStack): List<StoredSlotData>? {
        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer

        val hasContentsFlag = pdc.get(DrawerDataKeys.DRAWER_HAS_CONTENTS, PersistentDataType.BYTE)
        if (hasContentsFlag == null || hasContentsFlag != 1.toByte()) {
            return null
        }

        return deserializeSlotsFromPdc(pdc)
    }

    /**
     * Checks whether an item has stored drawer contents.
     *
     * Why: Quick check to determine if a drawer item needs content restoration
     * without performing full deserialization of all slot data.
     *
     * @param item The item to check
     * @return true if the item has stored drawer contents
     */
    fun hasStoredContents(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer

        val flag = pdc.get(DrawerDataKeys.DRAWER_HAS_CONTENTS, PersistentDataType.BYTE)
        return flag != null && flag == 1.toByte()
    }

    // ========================================
    // Private Helper Methods
    // ========================================

    /**
     * Builds the display name Component for a drawer type.
     *
     * Why: displayConfig が設定されている場合はそちらに委譲し、
     * 設定がない場合はハードコードされたデフォルト値を使用する。
     * これにより後方互換性を維持しながらカスタマイズを可能にする。
     */
    private fun buildDisplayName(type: DrawerType): Component {
        val config = displayConfig
        if (config != null) {
            return config.getDisplayName(type)
        }

        val name = type.displayName
        val color = DISPLAY_COLORS[type] ?: NamedTextColor.WHITE

        return Component.text(name)
            .color(color)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
    }

    /**
     * Builds the lore list for a drawer type.
     *
     * Why: displayConfig が設定されている場合はそちらに委譲し、
     * 設定がない場合はハードコードされたデフォルト値を使用する。
     */
    private fun buildLore(type: DrawerType): List<Component> {
        val config = displayConfig
        if (config != null) {
            return config.getLore(type)
        }

        val slotCount = type.getSlotCount()
        val capacityPerSlot = DrawerCapacityConfig.getCapacity(type)
        val totalCapacity = slotCount * capacityPerSlot

        return listOf(
            Component.empty(),
            Component.text("スロット数: ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                    Component.text("$slotCount")
                        .color(NamedTextColor.WHITE)
                ),
            Component.text("スロット容量: ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                    Component.text("${capacityPerSlot} スタック")
                        .color(NamedTextColor.WHITE)
                ),
            Component.text("総容量: ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                    Component.text("${totalCapacity} スタック")
                        .color(NamedTextColor.WHITE)
                ),
            Component.empty(),
            Component.text("設置してストレージドロワーを作成")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true)
        )
    }

    /**
     * Builds the lore list for a drawer that contains stored items.
     *
     * Why: Players need to see what is stored inside a drawer item
     * before placing it, similar to shulker box tooltips.
     */
    private fun buildLoreWithContents(type: DrawerType, slots: List<DrawerSlot>): List<Component> {
        val baseLore = buildLore(type).toMutableList()

        // 格納アイテムセクション
        baseLore.add(Component.empty())
        baseLore.add(
            Component.text("格納アイテム:")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )

        for (slot in slots) {
            if (slot.isEmpty() && !slot.isLocked) {
                continue
            }

            val materialName = slot.storedMaterial?.name?.lowercase()?.replace('_', ' ') ?: "空"
            val lockIndicator = if (slot.isLocked) " [ロック済]" else ""
            val slotText = "  スロット${slot.index + 1}: ${slot.itemCount}個 $materialName$lockIndicator"

            baseLore.add(
                Component.text(slotText)
                    .color(NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }

        return baseLore
    }

    // ========================================
    // Private Helper Methods - Deserialization
    // ========================================

    /**
     * Maximum number of slot indices to scan when deserializing from PDC.
     *
     * Why: Current DrawerType supports 1 slot, but this allows forward
     * compatibility if multi-slot drawers are added in the future.
     */
    private const val MAX_SLOT_SCAN = 8

    /**
     * Deserializes slot data from a PersistentDataContainer.
     *
     * Why: Restores slot state from PDC keys written by createDrawerItemWithContents.
     * Iterates over slot indices 0..MAX_SLOT_SCAN and collects any that have
     * material data stored or are locked (even if empty).
     *
     * @param pdc The PersistentDataContainer to read from
     * @return List of deserialized StoredSlotData entries (may be empty)
     */
    private fun deserializeSlotsFromPdc(
        pdc: org.bukkit.persistence.PersistentDataContainer
    ): List<StoredSlotData> {
        val results = mutableListOf<StoredSlotData>()

        for (slotIndex in 0 until MAX_SLOT_SCAN) {
            val materialName = pdc.get(
                DrawerDataKeys.slotMaterial(slotIndex),
                PersistentDataType.STRING
            )

            val lockedByte = pdc.get(
                DrawerDataKeys.slotLocked(slotIndex),
                PersistentDataType.BYTE
            ) ?: 0.toByte()
            val isLocked = lockedByte == 1.toByte()

            // Skip slot if neither material nor lock exists
            if (materialName == null && !isLocked) {
                continue
            }

            val material = if (materialName != null) {
                try {
                    Material.valueOf(materialName)
                } catch (e: IllegalArgumentException) {
                    // Unknown material (possibly removed in a newer MC version); skip gracefully
                    continue
                }
            } else {
                null
            }

            val itemCount = pdc.get(
                DrawerDataKeys.slotCount(slotIndex),
                PersistentDataType.INTEGER
            ) ?: 0

            val lockedMaterial = if (isLocked) {
                pdc.get(
                    DrawerDataKeys.slotLockedMaterial(slotIndex),
                    PersistentDataType.STRING
                )?.let { name ->
                    try {
                        Material.valueOf(name)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            } else {
                null
            }

            val itemTemplate = pdc.get(
                DrawerDataKeys.slotItemTemplate(slotIndex),
                PersistentDataType.BYTE_ARRAY
            )?.let { bytes ->
                try {
                    ItemStack.deserializeBytes(bytes)
                } catch (e: Exception) {
                    // Corrupted template data; skip gracefully
                    null
                }
            }

            results.add(
                StoredSlotData(
                    index = slotIndex,
                    storedMaterial = material,
                    itemCount = itemCount,
                    isLocked = isLocked,
                    lockedMaterial = lockedMaterial,
                    storedItemTemplate = itemTemplate
                )
            )
        }

        return results
    }

    // ========================================
    // Type Parsing (delegates to DrawerType)
    // ========================================

    /**
     * Parses a drawer type from a string input.
     *
     * Why: Provides flexible input parsing for commands,
     * accepting multiple naming conventions.
     * Delegates to DrawerType.fromNameOrAlias() for actual parsing.
     *
     * @param input The string to parse (case-insensitive)
     * @return The parsed DrawerType, or null if not recognized
     */
    fun parseDrawerType(input: String): DrawerType? {
        return DrawerType.fromNameOrAlias(input)
    }

    /**
     * Gets all valid type names for tab completion.
     *
     * Why: Provides comprehensive suggestions for command tab completion.
     * Delegates to DrawerType.getValidTypeNames() for the list.
     *
     * @return List of all valid type name strings
     */
    fun getValidTypeNames(): List<String> {
        return DrawerType.getValidTypeNames()
    }
}
