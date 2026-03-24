package com.example.paperdrawers.domain.model

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * ドロワー内の単一スロットを表すデータクラス。
 *
 * スロットはアイテムの格納、ロック機能、容量管理を担当する。
 * ロックされたスロットは、ロックされたマテリアルのみを受け入れる。
 *
 * @property index スロットのインデックス（0から始まる）
 * @property storedMaterial 格納されているアイテムのマテリアル（空の場合はnull）
 * @property itemCount 格納されているアイテム数
 * @property maxCapacity 最大容量（アイテム数単位、スタック数 * 64で計算）
 * @property isLocked スロットがロックされているかどうか
 * @property lockedMaterial ロックされているマテリアル（ロック時のみ有効）
 * @property storedItemTemplate 格納されているアイテムのテンプレート（エンチャント・NBTタグ保持用、amount=1）
 */
data class DrawerSlot(
    val index: Int,
    val storedMaterial: Material? = null,
    val itemCount: Int = 0,
    val maxCapacity: Int,
    val isLocked: Boolean = false,
    val lockedMaterial: Material? = null,
    val storedItemTemplate: ItemStack? = null
) {
    init {
        require(index >= 0) { "index must be non-negative, but was $index" }
        require(itemCount >= 0) { "itemCount must be non-negative, but was $itemCount" }
        require(maxCapacity > 0) { "maxCapacity must be positive, but was $maxCapacity" }
        require(itemCount <= maxCapacity) { "itemCount ($itemCount) cannot exceed maxCapacity ($maxCapacity)" }

        // storedMaterialがnullの場合、itemCountは0でなければならない
        if (storedMaterial == null) {
            require(itemCount == 0) { "itemCount must be 0 when storedMaterial is null" }
        }

        // isLockedがtrueの場合、lockedMaterialはnullであってはならない
        if (isLocked) {
            require(lockedMaterial != null) { "lockedMaterial must be set when slot is locked" }
        }
    }

    /**
     * 指定されたマテリアルをこのスロットに格納できるかどうかを判定する。
     *
     * 以下の条件で受け入れ可能:
     * - スロットが空で、ロックされていない場合
     * - スロットが空で、ロックされていてロックマテリアルと一致する場合
     * - 既に同じマテリアルが格納されていて、空き容量がある場合
     *
     * @param material 格納しようとするマテリアル
     * @return 格納可能な場合true
     */
    fun canAccept(material: Material): Boolean {
        // 既に満杯の場合は受け入れ不可
        if (isFull()) return false

        // ロックされている場合はロックマテリアルのみ受け入れ
        if (isLocked) {
            return material == lockedMaterial
        }

        // 空のスロットは任意のマテリアルを受け入れ
        if (isEmpty()) return true

        // 既存マテリアルと同じ場合のみ受け入れ
        return material == storedMaterial
    }

    /**
     * 指定された ItemStack をこのスロットに格納できるかどうかを判定する。
     *
     * ItemStack.isSimilar() を使用して、エンチャントやNBTタグも含めて比較する。
     * これにより、同じマテリアルでも異なるエンチャントのアイテムは別々に格納される。
     *
     * ロックされている場合も、既にアイテムが格納されていれば
     * そのアイテムと isSimilar() で比較する。これにより異なるNBTデータの
     * アイテムが混在することを防ぐ。
     *
     * @param itemStack 格納しようとするアイテム
     * @return 格納可能な場合true
     */
    fun canAcceptItemStack(itemStack: ItemStack): Boolean {
        // 既に満杯の場合は受け入れ不可
        if (isFull()) return false

        // ロックされている場合
        if (isLocked) {
            // マテリアルが一致しない場合は受け入れ不可
            if (itemStack.type != lockedMaterial) return false

            // テンプレートが存在する場合はisSimilarで比較（エンチャント・NBT含む）
            // これにより、ロック中でも異なるNBTデータのアイテムは混在しない
            if (storedItemTemplate != null) {
                return storedItemTemplate.isSimilar(itemStack)
            }

            // テンプレートがない場合（ロックされているが空）は
            // ロックマテリアルと一致すれば受け入れ
            return true
        }

        // 空のスロットは任意のアイテムを受け入れ
        if (isEmpty()) return true

        // テンプレートが存在する場合はisSimilarで比較（エンチャント・NBT含む）
        if (storedItemTemplate != null) {
            return storedItemTemplate.isSimilar(itemStack)
        }

        // テンプレートがない場合はマテリアルのみで比較（後方互換性）
        return itemStack.type == storedMaterial
    }

    /**
     * アイテムをスロットに追加する。
     *
     * Note: この関数はマテリアルのみを保存し、ItemMeta は保持しない。
     * エンチャントやNBTタグを保持したい場合は addItemStack() を使用すること。
     *
     * @param material 追加するマテリアル
     * @param count 追加する数量
     * @return 実際に追加された数量と更新されたスロットのペア
     * @throws IllegalArgumentException マテリアルを受け入れられない場合
     */
    fun addItems(material: Material, count: Int): Pair<Int, DrawerSlot> {
        require(count > 0) { "count must be positive, but was $count" }
        require(canAccept(material)) { "Cannot accept material $material in this slot" }

        val remainingSpace = getRemainingSpace()
        val actuallyAdded = minOf(count, remainingSpace)

        val updatedSlot = copy(
            storedMaterial = material,
            itemCount = itemCount + actuallyAdded
        )

        return Pair(actuallyAdded, updatedSlot)
    }

    /**
     * ItemStack をスロットに追加する。
     *
     * ItemStack のメタデータ（エンチャント、名前、説明、NBTタグ等）を
     * テンプレートとして保存し、取り出し時に復元する。
     *
     * @param itemStack 追加するアイテム
     * @return 実際に追加された数量と更新されたスロットのペア
     * @throws IllegalArgumentException アイテムを受け入れられない場合
     */
    fun addItemStack(itemStack: ItemStack): Pair<Int, DrawerSlot> {
        val count = itemStack.amount
        require(count > 0) { "count must be positive, but was $count" }
        require(canAcceptItemStack(itemStack)) { "Cannot accept item ${itemStack.type} in this slot" }

        val remainingSpace = getRemainingSpace()
        val actuallyAdded = minOf(count, remainingSpace)

        // テンプレートを作成（amount=1 で保存）
        val template = if (storedItemTemplate != null) {
            // 既存のテンプレートを使用
            storedItemTemplate
        } else {
            // 新規テンプレートを作成
            itemStack.clone().apply { amount = 1 }
        }

        val updatedSlot = copy(
            storedMaterial = itemStack.type,
            itemCount = itemCount + actuallyAdded,
            storedItemTemplate = template
        )

        return Pair(actuallyAdded, updatedSlot)
    }

    /**
     * アイテムをスロットから取り出す。
     *
     * @param count 取り出そうとする数量
     * @return 実際に取り出された数量と更新されたスロットのペア
     */
    fun removeItems(count: Int): Pair<Int, DrawerSlot> {
        require(count > 0) { "count must be positive, but was $count" }

        val actuallyRemoved = minOf(count, itemCount)
        val newItemCount = itemCount - actuallyRemoved

        // アイテムが空になった場合、ロックされていなければマテリアルとテンプレートもクリア
        val (newStoredMaterial, newTemplate) = if (newItemCount == 0 && !isLocked) {
            Pair(null, null)
        } else {
            Pair(storedMaterial, storedItemTemplate)
        }

        val updatedSlot = copy(
            storedMaterial = newStoredMaterial,
            itemCount = newItemCount,
            storedItemTemplate = newTemplate
        )

        return Pair(actuallyRemoved, updatedSlot)
    }

    /**
     * 格納されているアイテムのテンプレートから指定数量の ItemStack を作成する。
     *
     * エンチャントやNBTタグを含む完全な ItemStack を返す。
     * テンプレートが存在しない場合（後方互換性用）はマテリアルのみの ItemStack を返す。
     *
     * @param amount 作成するアイテム数
     * @return 作成された ItemStack、スロットが空の場合は null
     */
    fun createItemStack(amount: Int): ItemStack? {
        if (isEmpty() || storedMaterial == null) return null

        return if (storedItemTemplate != null) {
            // テンプレートからクローンして数量を設定
            storedItemTemplate.clone().apply { this.amount = amount }
        } else {
            // テンプレートがない場合はマテリアルのみで作成（後方互換性）
            ItemStack(storedMaterial, amount)
        }
    }

    /**
     * 残りの空き容量を取得する。
     *
     * @return 追加可能なアイテム数
     */
    fun getRemainingSpace(): Int = maxCapacity - itemCount

    /**
     * スロットが空かどうかを判定する。
     *
     * @return アイテムが格納されていない場合true
     */
    fun isEmpty(): Boolean = itemCount == 0

    /**
     * スロットが満杯かどうかを判定する。
     *
     * @return 最大容量に達している場合true
     */
    fun isFull(): Boolean = itemCount >= maxCapacity

    /**
     * スロットを指定されたマテリアルでロックする。
     *
     * ロックされたスロットは、指定されたマテリアルのみを受け入れる。
     * アイテムが空になってもマテリアル情報を保持する。
     *
     * @param material ロックするマテリアル
     * @return ロックされた新しいスロット
     */
    fun lock(material: Material): DrawerSlot {
        return copy(
            isLocked = true,
            lockedMaterial = material,
            // 空のスロットをロックする場合、storedMaterialも設定
            storedMaterial = storedMaterial ?: material
        )
    }

    /**
     * スロットのロックを解除する。
     *
     * 空のスロットの場合、storedMaterial と storedItemTemplate もクリアする。
     * これにより、アンロック後に異なるアイテムを格納できるようになる。
     *
     * @return ロック解除された新しいスロット
     */
    fun unlock(): DrawerSlot {
        return copy(
            isLocked = false,
            lockedMaterial = null,
            // 空のスロットの場合、storedMaterialとテンプレートもクリア
            storedMaterial = if (itemCount == 0) null else storedMaterial,
            storedItemTemplate = if (itemCount == 0) null else storedItemTemplate
        )
    }

    companion object {
        /** 1スタックあたりのアイテム数 */
        const val ITEMS_PER_STACK = 64

        /**
         * 指定された設定で新しいスロットを作成する。
         *
         * @param index スロットインデックス
         * @param capacityInStacks 容量（スタック単位）
         * @return 新しい空のスロット
         */
        fun create(index: Int, capacityInStacks: Int): DrawerSlot {
            return DrawerSlot(
                index = index,
                maxCapacity = capacityInStacks * ITEMS_PER_STACK
            )
        }
    }
}
