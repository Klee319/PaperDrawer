package com.example.paperdrawers.infrastructure.item

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Drawer Keyアイテムの生成とチェックを担当するファクトリー。
 *
 * Drawer Keyは、ドロワースロットを特定のアイテムタイプにロック/アンロックするために使用する
 * 特殊なアイテムです。ロックされたスロットは、アイテムが空になっても指定されたアイテムタイプのみを
 * 受け入れます。
 *
 * 使用方法:
 * - ドロワースロットにアイテムがある状態でDrawer Keyを右クリック: スロットをそのアイテムタイプにロック
 * - ロックされたスロットにDrawer Keyを右クリック: スロットのロックを解除
 */
object DrawerKeyFactory {

    /** Drawer Keyを識別するためのPDCキー名 */
    private const val DRAWER_KEY_TAG = "drawer_key"

    /** PDCキーを保持（プラグイン初期化時に設定） */
    private var drawerKeyNamespacedKey: NamespacedKey? = null

    /**
     * ファクトリーを初期化する。
     *
     * プラグインの起動時に呼び出す必要がある。
     *
     * @param plugin プラグインインスタンス
     */
    fun initialize(plugin: JavaPlugin) {
        drawerKeyNamespacedKey = NamespacedKey(plugin, DRAWER_KEY_TAG)
    }

    /**
     * 新しいDrawer Keyアイテムを作成する。
     *
     * @param amount 作成するアイテムの数量（デフォルト: 1）
     * @return Drawer KeyのItemStack
     * @throws IllegalStateException ファクトリーが初期化されていない場合
     */
    fun createDrawerKey(amount: Int = 1): ItemStack {
        val key = drawerKeyNamespacedKey
            ?: throw IllegalStateException("DrawerKeyFactory has not been initialized. Call initialize(plugin) first.")

        require(amount > 0) { "amount must be positive, but was $amount" }

        val itemStack = ItemStack(Material.TRIPWIRE_HOOK, amount)

        itemStack.itemMeta = itemStack.itemMeta?.apply {
            // Set display name
            setDisplayName("${org.bukkit.ChatColor.GOLD}ドロワーキー")

            // Set lore with usage instructions
            lore = listOf(
                "${org.bukkit.ChatColor.GRAY}ドロワースロットを右クリックでロック/アンロック",
                "",
                "${org.bukkit.ChatColor.YELLOW}使い方:",
                "${org.bukkit.ChatColor.WHITE}- ロック: アイテムのあるスロットを右クリック",
                "${org.bukkit.ChatColor.WHITE}- アンロック: ロック済みスロットを右クリック",
                "",
                "${org.bukkit.ChatColor.AQUA}ロックされたスロットは指定された",
                "${org.bukkit.ChatColor.AQUA}アイテムのみ受け付けます"
            )

            // Set PDC tag to identify this as a Drawer Key
            persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
        }

        return itemStack
    }

    /**
     * 指定されたItemStackがDrawer Keyかどうかを判定する。
     *
     * @param item 判定するItemStack
     * @return Drawer Keyの場合true、そうでない場合false
     */
    fun isDrawerKey(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) {
            return false
        }

        val key = drawerKeyNamespacedKey ?: return false
        val meta = item.itemMeta ?: return false

        return meta.persistentDataContainer.has(key, PersistentDataType.BYTE)
    }

    /**
     * ファクトリーが初期化されているかどうかを確認する。
     *
     * @return 初期化されている場合true
     */
    fun isInitialized(): Boolean = drawerKeyNamespacedKey != null
}
