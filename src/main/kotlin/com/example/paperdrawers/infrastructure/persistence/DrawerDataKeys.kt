package com.example.paperdrawers.infrastructure.persistence

import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin

/**
 * DrawerDataKeys: Defines all NamespacedKey constants for Persistent Data Container (PDC) storage.
 *
 * This object provides centralized key management for drawer data serialization.
 * Keys are lazily initialized after plugin initialization to ensure the plugin instance is available.
 *
 * Why: Centralizing key definitions prevents typos and ensures consistency across the codebase.
 * Using a singleton pattern with lazy initialization allows for safe access throughout the plugin lifecycle.
 */
object DrawerDataKeys {

    private lateinit var pluginInstance: Plugin

    /**
     * Initializes the DrawerDataKeys with the plugin instance.
     * Must be called during plugin onEnable before accessing any keys.
     *
     * @param plugin The plugin instance to use for creating NamespacedKeys
     * @throws IllegalStateException if already initialized
     */
    fun initialize(plugin: Plugin) {
        if (::pluginInstance.isInitialized) {
            throw IllegalStateException("DrawerDataKeys has already been initialized")
        }
        pluginInstance = plugin
    }

    /**
     * Checks if DrawerDataKeys has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    fun isInitialized(): Boolean = ::pluginInstance.isInitialized

    // ========================================
    // Core Drawer Identification Keys
    // ========================================

    /** Unique identifier for the drawer instance (UUID string) */
    val DRAWER_ID: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_id")
    }

    /** Type of drawer (e.g., "single", "double", "quad") */
    val DRAWER_TYPE: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_type")
    }

    /** Block face direction the drawer is facing (e.g., "NORTH", "SOUTH", "EAST", "WEST") */
    val DRAWER_FACING: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_facing")
    }

    /** UUID of the player who placed the drawer */
    val DRAWER_OWNER: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_owner")
    }

    /** Timestamp when the drawer was created (epoch milliseconds) */
    val DRAWER_CREATED: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_created")
    }

    /** Flag indicating the item has stored drawer contents (BYTE: 1 = has contents) */
    val DRAWER_HAS_CONTENTS: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_has_contents")
    }

    /** Flag indicating the drawer is a sorting drawer (BYTE: 1 = sorting) */
    val DRAWER_SORTING: NamespacedKey by lazy {
        requireInitialized()
        NamespacedKey(pluginInstance, "drawer_sorting")
    }

    // ========================================
    // Slot Data Keys (Dynamic Generation)
    // ========================================

    /**
     * Generates a NamespacedKey for a slot's stored material.
     *
     * Why: Each slot can store a different material type, requiring unique keys per slot index.
     *
     * @param slotIndex The zero-based index of the slot
     * @return NamespacedKey for the slot's material
     */
    fun slotMaterial(slotIndex: Int): NamespacedKey {
        requireInitialized()
        requireValidSlotIndex(slotIndex)
        return NamespacedKey(pluginInstance, "slot_${slotIndex}_material")
    }

    /**
     * Generates a NamespacedKey for a slot's item count.
     *
     * Why: Tracks the number of items stored in each slot.
     *
     * @param slotIndex The zero-based index of the slot
     * @return NamespacedKey for the slot's count
     */
    fun slotCount(slotIndex: Int): NamespacedKey {
        requireInitialized()
        requireValidSlotIndex(slotIndex)
        return NamespacedKey(pluginInstance, "slot_${slotIndex}_count")
    }

    /**
     * Generates a NamespacedKey for a slot's locked state.
     *
     * Why: Locked slots retain their material type even when empty,
     * preventing other materials from being inserted.
     *
     * @param slotIndex The zero-based index of the slot
     * @return NamespacedKey for the slot's locked state
     */
    fun slotLocked(slotIndex: Int): NamespacedKey {
        requireInitialized()
        requireValidSlotIndex(slotIndex)
        return NamespacedKey(pluginInstance, "slot_${slotIndex}_locked")
    }

    /**
     * Generates a NamespacedKey for a slot's locked material.
     *
     * Why: When a slot is locked, this stores the material that the slot is locked to,
     * even if the slot is currently empty.
     *
     * @param slotIndex The zero-based index of the slot
     * @return NamespacedKey for the slot's locked material
     */
    fun slotLockedMaterial(slotIndex: Int): NamespacedKey {
        requireInitialized()
        requireValidSlotIndex(slotIndex)
        return NamespacedKey(pluginInstance, "slot_${slotIndex}_locked_material")
    }

    /**
     * Generates a NamespacedKey for a slot's ItemStack template.
     *
     * Why: ItemStack template stores the full item metadata (enchantments, NBT tags, etc.)
     * as a serialized byte array. This allows restoring items with their complete data
     * when extracted from the drawer.
     *
     * @param slotIndex The zero-based index of the slot
     * @return NamespacedKey for the slot's item template
     */
    fun slotItemTemplate(slotIndex: Int): NamespacedKey {
        requireInitialized()
        requireValidSlotIndex(slotIndex)
        return NamespacedKey(pluginInstance, "slot_${slotIndex}_item_template")
    }

    // ========================================
    // Internal Validation Methods
    // ========================================

    /**
     * Ensures DrawerDataKeys has been initialized before accessing keys.
     *
     * @throws IllegalStateException if not initialized
     */
    private fun requireInitialized() {
        if (!::pluginInstance.isInitialized) {
            throw IllegalStateException(
                "DrawerDataKeys has not been initialized. " +
                "Call DrawerDataKeys.initialize(plugin) in onEnable before accessing keys."
            )
        }
    }

    /**
     * Validates that a slot index is non-negative.
     *
     * Why: Negative slot indices are invalid and indicate a programming error.
     *
     * @param slotIndex The slot index to validate
     * @throws IllegalArgumentException if slotIndex is negative
     */
    private fun requireValidSlotIndex(slotIndex: Int) {
        require(slotIndex >= 0) {
            "Slot index must be non-negative, but was: $slotIndex"
        }
    }
}
