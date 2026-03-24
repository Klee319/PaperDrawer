package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerSlot
import com.example.paperdrawers.domain.model.DrawerType
import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.debug.MetricsCollector
import com.example.paperdrawers.infrastructure.display.DisplayManager
import com.example.paperdrawers.infrastructure.item.DrawerKeyFactory
import com.example.paperdrawers.infrastructure.message.MessageManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * ドロワーブロックとのプレイヤーインタラクションを処理するイベントリスナー。
 *
 * 右クリックでアイテム挿入、左クリックでアイテム取り出しを行う。
 * スニーク状態によって一括操作も可能。
 *
 * This is the MOST IMPORTANT listener for drawer functionality.
 *
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property logger ログ出力用のロガー
 */
class DrawerInteractionListener(
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val logger: Logger
) : Listener {

    companion object {
        /** ダブルクリック判定のしきい値（ミリ秒） */
        private const val DOUBLE_CLICK_THRESHOLD_MS = 150L
        /** 正面のエッジ判定マージン（0.0〜1.0、各辺からの比率） */
        private const val FRONT_EDGE_MARGIN = 0.05f
    }

    /**
     * ItemFrameInteractionListener への参照（循環依存を避けるため後から設定）。
     *
     * 右クリック処理時に recordRightClick を呼び出して、
     * onPlayerAnimation での誤検出を防ぐ。
     */
    private var itemFrameInteractionListener: ItemFrameInteractionListener? = null

    /**
     * ItemFrameInteractionListener を設定する。
     *
     * プラグイン初期化時に、両リスナー作成後に呼び出す。
     *
     * @param listener ItemFrameInteractionListener インスタンス
     */
    fun setItemFrameInteractionListener(listener: ItemFrameInteractionListener) {
        this.itemFrameInteractionListener = listener
    }

    /** プレイヤーごとの最後のクリック時刻を追跡 */
    private val lastClickTime: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** プレイヤーごとの最後のクリック位置を追跡 */
    private val lastClickLocation: MutableMap<UUID, String> = ConcurrentHashMap()

    /**
     * tick単位のインタラクション重複防止マップ。
     *
     * Why: 同一クリックに対してDrawerInteractionListenerとItemFrameInteractionListenerの
     * 両方が発火するため、同一tick内の同一プレイヤー・同一ドロワー・同一アクションを
     * 重複処理しないようにする。
     * Key: "playerUUID:locationKey:actionType"
     * Value: 最後に処理したサーバーtick
     */
    private val processedActions: MutableMap<String, Int> = ConcurrentHashMap()

    /**
     * 同一tick内で既に処理済みかどうかを判定する。
     *
     * 処理済みでない場合は現在のtickを記録し、falseを返す。
     * 処理済みの場合はtrueを返す（呼び出し元はスキップすべき）。
     *
     * @param player プレイヤー
     * @param drawer ドロワーブロック
     * @param actionType アクションタイプ（"left" or "right"）
     * @return 既に処理済みの場合true
     */
    private fun isAlreadyProcessedThisTick(player: Player, drawer: DrawerBlock, actionType: String): Boolean {
        val currentTick = Bukkit.getCurrentTick()
        val key = "${player.uniqueId}:${drawer.getLocationKey()}:$actionType"
        val lastTick = processedActions[key]
        if (lastTick != null && lastTick == currentTick) {
            logger.fine("Skipping duplicate $actionType interaction for ${player.name} at ${drawer.getLocationKey()} (same tick)")
            return true
        }
        processedActions[key] = currentTick
        // 古いエントリをクリーンアップ（100エントリを超えた場合）
        if (processedActions.size > 100) {
            processedActions.entries.removeIf { it.value < currentTick - 1 }
        }
        return false
    }

    /**
     * ダブルクリックかどうかを判定する。
     *
     * ダブルクリックが検出された場合、タイマーをリセットして
     * 連続クリックが全てダブルクリックとして検出されるのを防ぐ。
     *
     * @param player プレイヤー
     * @param drawer ドロワーブロック
     * @return ダブルクリックの場合true
     */
    private fun isDoubleClick(player: Player, drawer: DrawerBlock): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = lastClickTime[player.uniqueId] ?: 0L
        val lastLoc = lastClickLocation[player.uniqueId]
        val currentLoc = drawer.getLocationKey()

        val isDouble = (now - lastTime < DOUBLE_CLICK_THRESHOLD_MS) && (lastLoc == currentLoc)

        // ダブルクリック検出後はタイマーをリセットして、
        // 次のクリックがダブルクリックとして検出されないようにする
        if (isDouble) {
            // 閾値より前の時間に設定することで、次のクリックは新しいシーケンスとなる
            lastClickTime[player.uniqueId] = now - DOUBLE_CLICK_THRESHOLD_MS - 1
        } else {
            lastClickTime[player.uniqueId] = now
        }
        lastClickLocation[player.uniqueId] = currentLoc

        return isDouble
    }

    /**
     * プレイヤーインタラクトイベントを処理する。
     *
     * 以下の処理を実行:
     * 1. インタラクトされたブロックがドロワーかを確認
     * 2. ブロックフェイスがドロワーの正面かを確認
     * 3. クリック位置からスロットを特定
     * 4. アクションに応じて挿入または取り出しを実行
     *
     * @param event プレイヤーインタラクトイベント
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // Only handle main hand interactions to avoid duplicate processing
        if (event.hand != EquipmentSlot.HAND) {
            return
        }

        val block = event.clickedBlock ?: return
        val action = event.action
        val player = event.player
        val blockFace = event.blockFace

        // Only handle block interactions
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return
        }

        // Step 1: Check if block is a drawer
        val drawer = repository.findByLocation(block.location)
        if (drawer == null) {
            return
        }

        // ドロワーブロックの場合、右クリックでは樽GUIを開かないようにする
        // 左クリックはブロック破壊を許可するためキャンセルしない
        // Cancel event to prevent opening barrel GUI (only for right-click)
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY)
            event.setUseItemInHand(Event.Result.DENY)
        }

        // Step 2: Check if clicked face matches drawer facing
        if (blockFace != drawer.facing) {
            // Player clicked on a side/back/top/bottom of the drawer, not the front
            // GUIは開かないが、ドロワー操作は行わない
            return
        }

        // Step 2.5: For left-click on front face, check if click is on the edge
        // エッジクリックはブロック破壊を許可し、中央クリックのみアイテム取り出しを行う
        if (action == Action.LEFT_CLICK_BLOCK && isClickOnFrontEdge(player, drawer, blockFace)) {
            return
        }

        // Step 3: Determine slot index from click position
        val slotIndex = determineSlotIndex(drawer, event, blockFace)
        if (slotIndex == null) {
            return
        }

        // Step 4: Handle action
        when (action) {
            Action.RIGHT_CLICK_BLOCK -> handleRightClick(event, drawer, slotIndex, player)
            Action.LEFT_CLICK_BLOCK -> handleLeftClick(drawer, slotIndex, player)
            else -> { /* Already filtered above */ }
        }
    }

    /**
     * クリック位置からスロットインデックスを決定する。
     *
     * Paper 1.21.xではgetInteractionPoint()を使用してクリック位置を取得できる。
     * これはRIGHT_CLICK_BLOCKでのみ利用可能。
     *
     * @param drawer ドロワーブロック
     * @param event インタラクトイベント
     * @param blockFace クリックされたブロックフェイス
     * @return スロットインデックス、決定できない場合はnull
     */
    private fun determineSlotIndex(
        drawer: DrawerBlock,
        event: PlayerInteractEvent,
        blockFace: BlockFace
    ): Int? {
        // Try to get interaction point (only available for RIGHT_CLICK_BLOCK in Paper)
        val interactionPoint = event.interactionPoint

        if (interactionPoint != null) {
            // Calculate relative position on the block face
            val relativePos = calculateRelativePosition(drawer.location, interactionPoint, blockFace)
            if (relativePos != null) {
                return try {
                    drawer.type.getSlotIndexFromClick(relativePos.first, relativePos.second)
                } catch (e: IllegalArgumentException) {
                    logger.warning("Invalid click position: ${e.message}")
                    null
                }
            }
        }

        // Fallback for LEFT_CLICK_BLOCK or when interaction point is not available
        // Use player's eye location to estimate click position
        return estimateSlotFromPlayerPosition(drawer, event.player)
    }

    /**
     * 正面のクリックがエッジ（縁）部分、または正面以外かを判定する。
     *
     * Why: raytraceでヒットした面が正面(drawer.facing)でない場合はtrueを返し、
     * ブロック破壊を許可する。正面でもエッジ(外周15%)ならtrueを返す。
     * 中央部分のみfalseを返し、アイテム操作を許可する。
     *
     * @param player プレイヤー
     * @param drawer ドロワーブロック
     * @param blockFace ドロワーの正面方向
     * @return エッジまたは正面以外の場合true（破壊許可）、中央の場合false（アイテム操作）
     */
    private fun isClickOnFrontEdge(player: Player, drawer: DrawerBlock, blockFace: BlockFace): Boolean {
        val rayResult = player.rayTraceBlocks(6.0) ?: return true

        // raytraceが正面に当たっているか検証
        val hitBlock = rayResult.hitBlock
        val hitFace = rayResult.hitBlockFace
        if (hitBlock == null || hitFace != blockFace ||
            hitBlock.location.blockX != drawer.location.blockX ||
            hitBlock.location.blockY != drawer.location.blockY ||
            hitBlock.location.blockZ != drawer.location.blockZ) {
            // 正面以外をクリック → 破壊を許可
            return true
        }

        val hitPos = rayResult.hitPosition
        val hitLocation = Location(drawer.location.world, hitPos.x, hitPos.y, hitPos.z)

        val relPos = calculateRelativePosition(drawer.location, hitLocation, blockFace)
            ?: return true  // 計算できない場合は破壊を許可

        val (relX, relY) = relPos

        return relX < FRONT_EDGE_MARGIN || relX > (1f - FRONT_EDGE_MARGIN) ||
               relY < FRONT_EDGE_MARGIN || relY > (1f - FRONT_EDGE_MARGIN)
    }

    /**
     * ブロック面上の相対位置を計算する。
     *
     * @param blockLocation ブロックの位置
     * @param hitLocation クリックされた正確な位置
     * @param blockFace クリックされたブロックフェイス
     * @return (relativeX, relativeY) のペア、計算できない場合はnull
     */
    private fun calculateRelativePosition(
        blockLocation: Location,
        hitLocation: Location,
        blockFace: BlockFace
    ): Pair<Float, Float>? {
        // Calculate position relative to block origin
        val relX = hitLocation.x - blockLocation.blockX
        val relY = hitLocation.y - blockLocation.blockY
        val relZ = hitLocation.z - blockLocation.blockZ

        // Map 3D position to 2D face coordinates based on block face
        // The coordinate system for drawer slots:
        // - X: left (0) to right (1) when looking at the face
        // - Y: top (0) to bottom (1)
        return when (blockFace) {
            BlockFace.NORTH -> {
                // Looking at north face: X goes left to right (1-relX), Y goes top to bottom (1-relY)
                Pair((1 - relX).toFloat(), (1 - relY).toFloat())
            }
            BlockFace.SOUTH -> {
                // Looking at south face: X goes left to right (relX), Y goes top to bottom (1-relY)
                Pair(relX.toFloat(), (1 - relY).toFloat())
            }
            BlockFace.EAST -> {
                // Looking at east face: X goes left to right (1-relZ), Y goes top to bottom (1-relY)
                Pair((1 - relZ).toFloat(), (1 - relY).toFloat())
            }
            BlockFace.WEST -> {
                // Looking at west face: X goes left to right (relZ), Y goes top to bottom (1-relY)
                Pair(relZ.toFloat(), (1 - relY).toFloat())
            }
            else -> null // Only horizontal faces are valid for drawers
        }
    }

    /**
     * プレイヤーの位置からスロットを推定する。
     *
     * クリック位置が取得できない場合のフォールバック。
     * プレイヤーの視線方向からスロットを推定する。
     *
     * @param drawer ドロワーブロック
     * @param player プレイヤー
     * @return 推定されたスロットインデックス
     */
    private fun estimateSlotFromPlayerPosition(drawer: DrawerBlock, player: Player): Int {
        val playerEye = player.eyeLocation
        val blockCenter = drawer.location.clone().add(0.5, 0.5, 0.5)

        // Get relative position of player to block
        val relX: Float
        val relY: Float = (0.5 - (playerEye.y - blockCenter.y + 0.5)).toFloat().coerceIn(0f, 1f)

        when (drawer.facing) {
            BlockFace.NORTH -> {
                relX = (0.5 + (playerEye.x - blockCenter.x)).toFloat().coerceIn(0f, 1f)
            }
            BlockFace.SOUTH -> {
                relX = (0.5 - (playerEye.x - blockCenter.x)).toFloat().coerceIn(0f, 1f)
            }
            BlockFace.EAST -> {
                relX = (0.5 + (playerEye.z - blockCenter.z)).toFloat().coerceIn(0f, 1f)
            }
            BlockFace.WEST -> {
                relX = (0.5 - (playerEye.z - blockCenter.z)).toFloat().coerceIn(0f, 1f)
            }
            else -> {
                relX = 0.5f
            }
        }

        return try {
            drawer.type.getSlotIndexFromClick(relX, relY)
        } catch (e: IllegalArgumentException) {
            // Default to first slot if estimation fails
            0
        }
    }

    /**
     * 右クリック操作を処理する（アイテム挿入）。
     *
     * 操作モード:
     * - Drawer Keyを持っている場合: スロットのロック/アンロック
     * - ダブルクリック: インベントリ内の全ての同種アイテムを一括挿入
     * - スニーク+クリック: 手持ちのスタック全体を挿入
     * - 通常クリック: 手持ちアイテムを1つだけ挿入
     *
     * @param event インタラクトイベント
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     */
    private fun handleRightClick(
        event: PlayerInteractEvent,
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player
    ) {
        // 同一tick内の重複処理を防止
        if (isAlreadyProcessedThisTick(player, drawer, "right")) return

        val itemInHand = player.inventory.itemInMainHand

        // 右クリック処理を記録（onPlayerAnimation での誤検出防止）
        itemFrameInteractionListener?.recordRightClick(player)

        // Track right-click interaction
        MetricsCollector.incrementRightClickInteractions()

        // Handle Drawer Key for lock/unlock
        if (DrawerKeyFactory.isDrawerKey(itemInHand)) {
            handleDrawerKeyInteraction(drawer, slotIndex, player)
            return
        }

        // Void drawer: consume items with special effect
        if (drawer.type == DrawerType.VOID) {
            handleVoidInsert(drawer, slotIndex, player, itemInHand)
            return
        }

        // Check if player has items to insert
        if (itemInHand.type == Material.AIR || itemInHand.amount <= 0) {
            // 空の手の場合: ドロワーに格納されているアイテムタイプと同じアイテムをインベントリから検索して挿入
            val slot = drawer.getSlot(slotIndex)
            // storedMaterial または lockedMaterial（ロックされた空スロット用）を使用
            val targetMaterial = slot.storedMaterial ?: slot.lockedMaterial
            if (targetMaterial != null) {
                handleEmptyHandInsert(drawer, slotIndex, player, targetMaterial, slot.storedItemTemplate)
            }
            return
        }

        // ダブルクリック判定（アイテムを持っている場合のみ）
        val isDoubleClicked = isDoubleClick(player, drawer)
        val material = itemInHand.type

        try {
            if (isDoubleClicked) {
                // Double-click: insert ALL matching items from entire inventory
                handleBulkInsertFromInventory(drawer, slotIndex, player, material)
            } else if (player.isSneaking) {
                // Shift+click: insert entire hand stack
                handleSingleInsert(drawer, slotIndex, player, itemInHand)
            } else {
                // Normal click: insert one item only
                handleSingleItemInsert(drawer, slotIndex, player, itemInHand)
            }
        } catch (e: Exception) {
            logger.warning("Error handling right-click for player ${player.name}: ${e.message}")

        }
    }

    /**
     * Drawer Keyによるロック/アンロック操作を処理する。
     *
     * - スロットがロックされている場合: アンロック
     * - スロットにアイテムがある場合: そのアイテムタイプにロック
     * - スロットが空でロックされていない場合: 何もしない
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     */
    private fun handleDrawerKeyInteraction(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player
    ) {
        // Track drawer key operation
        MetricsCollector.incrementDrawerKeyOperations()

        val slot = drawer.getSlot(slotIndex)

        val messages = MessageManager.getInstance()

        if (slot.isLocked) {
            // Unlock the slot
            val updatedDrawer = drawer.unlockSlot(slotIndex)
            repository.save(updatedDrawer)
            displayManager.updateSlotDisplay(updatedDrawer, slotIndex)

            player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 0.7f, 1.2f)
            player.sendMessage(messages.getDrawerKeyUnlocked())
        } else if (slot.storedMaterial != null) {
            // Lock to current item type
            val material = slot.storedMaterial
            val updatedDrawer = drawer.lockSlot(slotIndex, material)
            repository.save(updatedDrawer)
            displayManager.updateSlotDisplay(updatedDrawer, slotIndex)

            val materialName = material.name.lowercase().replace('_', ' ')
            player.playSound(player.location, Sound.BLOCK_CHEST_LOCKED, 0.7f, 0.8f)
            player.sendMessage(messages.getDrawerKeyLocked(materialName))
        } else {
            // Empty slot, nothing to lock
            player.sendMessage(messages.getCannotLockEmpty())
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.5f, 1.0f)
        }
    }

    /**
     * 1つのアイテムのみを挿入する（通常クリック用）。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     * @param itemInHand プレイヤーの手持ちアイテム（キャッシュからのクローンの場合あり）
     */
    private fun handleSingleItemInsert(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        itemInHand: ItemStack
    ) {
        // ItemStack をクローンして数量を1に設定（エンチャント等を保持）
        val singleItem = itemInHand.clone().apply { amount = 1 }

        val (result, updatedDrawer) = drawer.insertItemStack(slotIndex, singleItem)

        if (result.success) {
            // ドロワーを先に保存（保存失敗時のアイテム消失を防ぐ）
            repository.save(updatedDrawer)

            // プレイヤーの実際のインベントリを更新（クローンして変更し、setで反映）
            val actualHandItem = player.inventory.itemInMainHand
            if (actualHandItem.isSimilar(itemInHand)) {
                val newAmount = actualHandItem.amount - result.insertedCount
                if (newAmount <= 0) {
                    player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                } else {
                    val updatedItem = actualHandItem.clone().apply { amount = newAmount }
                    player.inventory.setItemInMainHand(updatedItem)
                }
            } else {
                // 手持ちアイテムが変わっている場合、インベントリから同じアイテムを探して減らす
                reduceItemFromInventory(player, itemInHand, result.insertedCount)
            }

            // Update display
            displayManager.updateSlotDisplay(updatedDrawer, slotIndex)

            // Play sound
            player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.5f, 1.0f)

            // Track metrics
            MetricsCollector.incrementItemsInserted(result.insertedCount)
        }
    }

    /**
     * 単一アイテム挿入を処理する（Shift+クリック：スタック全体を挿入）。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     * @param itemInHand プレイヤーの手持ちアイテム（キャッシュからのクローンの場合あり）
     */
    private fun handleSingleInsert(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        itemInHand: ItemStack
    ) {
        // クローンしてドメインモデルに渡す（インベントリ参照の汚染を防ぐ）
        val clonedItem = itemInHand.clone()
        val (result, updatedDrawer) = drawer.insertItemStack(slotIndex, clonedItem)

        if (result.success) {
            // ドロワーを先に保存（保存失敗時のアイテム消失を防ぐ）
            repository.save(updatedDrawer)

            // プレイヤーの実際のインベントリを更新（クローンして変更し、setで反映）
            val actualHandItem = player.inventory.itemInMainHand
            if (actualHandItem.isSimilar(itemInHand)) {
                val newAmount = actualHandItem.amount - result.insertedCount
                if (newAmount <= 0) {
                    player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                } else {
                    val updatedItem = actualHandItem.clone().apply { amount = newAmount }
                    player.inventory.setItemInMainHand(updatedItem)
                }
            } else {
                // 手持ちアイテムが変わっている場合、インベントリから同じアイテムを探して減らす
                reduceItemFromInventory(player, itemInHand, result.insertedCount)
            }

            // Update display
            displayManager.updateSlotDisplay(updatedDrawer, slotIndex)

            // Play sound
            player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.5f, 1.0f)

            // Track metrics
            MetricsCollector.incrementItemsInserted(result.insertedCount)
        }
    }

    /**
     * プレイヤーのインベントリから指定アイテムを減らす。
     *
     * 手持ちアイテムがキャッシュと異なる場合に使用する。
     *
     * @param player プレイヤー
     * @param item 減らすアイテム（テンプレート）
     * @param amount 減らす数量
     * @return 実際に減らした数量
     */
    private fun reduceItemFromInventory(player: Player, item: ItemStack, amount: Int): Int {
        var remaining = amount
        val inventory = player.inventory

        // まず手持ちスロットをチェック
        val heldSlot = inventory.heldItemSlot
        val heldItem = inventory.getItem(heldSlot)
        if (heldItem != null && heldItem.isSimilar(item)) {
            val reduceAmount = minOf(heldItem.amount, remaining)
            remaining -= reduceAmount
            val newAmount = heldItem.amount - reduceAmount
            if (newAmount <= 0) {
                inventory.setItem(heldSlot, null)
            } else {
                // クローンして新しい数量を設定（ライブ参照のミューテーションを防ぐ）
                val updatedItem = heldItem.clone().apply { this.amount = newAmount }
                inventory.setItem(heldSlot, updatedItem)
            }
        }

        // 残りがあればインベントリ全体を検索
        if (remaining > 0) {
            for (i in 0 until inventory.size) {
                if (remaining <= 0) break
                val slotItem = inventory.getItem(i) ?: continue
                if (!slotItem.isSimilar(item)) continue

                val reduceAmount = minOf(slotItem.amount, remaining)
                remaining -= reduceAmount
                val newAmount = slotItem.amount - reduceAmount
                if (newAmount <= 0) {
                    inventory.setItem(i, null)
                } else {
                    // クローンして新しい数量を設定（ライブ参照のミューテーションを防ぐ）
                    val updatedItem = slotItem.clone().apply { this.amount = newAmount }
                    inventory.setItem(i, updatedItem)
                }
            }
        }

        val actualReduced = amount - remaining
        if (remaining > 0) {
            logger.warning("[ITEM BUG] Could not reduce $remaining x ${item.type} from ${player.name}'s inventory - items may have been duplicated!")
        }

        return actualReduced
    }

    /**
     * インベントリ全体から一括アイテム挿入を処理する（ダブルクリック用）。
     *
     * プレイヤーのインベントリ内の全スロットから、同種のアイテム（isSimilar で判定）を全て挿入する。
     * エンチャントやNBTタグが異なるアイテムは別物として扱われる。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     * @param material 挿入するマテリアル（型チェック用）
     */
    private fun handleBulkInsertFromInventory(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        material: Material
    ) {
        val inventory = player.inventory
        var totalInserted = 0
        var currentDrawer = drawer

        // 手持ちアイテムをテンプレートとして使用（isSimilar 比較用）
        val templateItem = player.inventory.itemInMainHand.clone()

        // Phase 1: ドロワー状態を計算し、インベントリ変更を記録する（まだ適用しない）
        // Pair<slotIndex, remaining amount (null = remove)>
        val inventoryChanges = mutableListOf<Pair<Int, Int?>>()

        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            // isSimilar を使用してエンチャント等も含めて比較
            if (!item.isSimilar(templateItem)) continue

            // クローンしてドメインモデルに渡す（インベントリ参照の汚染を防ぐ）
            val clonedItem = item.clone()
            val (result, updatedDrawer) = currentDrawer.insertItemStack(slotIndex, clonedItem)

            if (result.success) {
                totalInserted += result.insertedCount
                currentDrawer = updatedDrawer

                val remaining = item.amount - result.insertedCount
                if (remaining <= 0) {
                    inventoryChanges.add(Pair(i, null))
                } else {
                    inventoryChanges.add(Pair(i, remaining))
                }

                // If slot is full, stop inserting
                if (result.remainingCount > 0) {
                    break
                }
            }
        }

        if (totalInserted > 0) {
            // Phase 2: ドロワーを先に保存（保存失敗時のアイテム消失を防ぐ）
            repository.save(currentDrawer)

            // Phase 3: インベントリを更新（ドロワー保存成功後）
            for ((slotIdx, remaining) in inventoryChanges) {
                if (remaining == null) {
                    inventory.setItem(slotIdx, null)
                } else {
                    val originalItem = inventory.getItem(slotIdx)
                    if (originalItem != null) {
                        val updatedItem = originalItem.clone().apply { amount = remaining }
                        inventory.setItem(slotIdx, updatedItem)
                    }
                }
            }

            // Update display
            displayManager.updateSlotDisplay(currentDrawer, slotIndex)

            // Play sound
            player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.5f, 1.0f)

            // Track metrics
            MetricsCollector.incrementItemsInserted(totalInserted)
            MetricsCollector.incrementBulkInsertOperations()
        }
    }

    /**
     * 空の手で右クリックした場合、インベントリからドロワーに格納可能なアイテムを挿入する。
     *
     * ドロワーに既に格納されているアイテムタイプと同じアイテムをインベントリから検索し、
     * 1つだけ挿入する（スニーク時は1スタック）。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     * @param targetMaterial 挿入対象のマテリアル
     */
    private fun handleEmptyHandInsert(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        targetMaterial: Material,
        template: ItemStack? = null
    ) {
        val inventory = player.inventory

        // インベントリから対象マテリアルを持つ最初のスロットを検索
        var foundSlotIndex: Int? = null
        var foundItem: ItemStack? = null

        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            // テンプレートがある場合はisSimilarで比較（エンチャント・NBT含む）
            // テンプレートがない場合はマテリアルのみで比較（後方互換性）
            val matches = if (template != null) {
                template.isSimilar(item)
            } else {
                item.type == targetMaterial
            }
            if (matches) {
                foundSlotIndex = i
                foundItem = item
                break
            }
        }

        if (foundItem == null || foundSlotIndex == null) {
            return
        }

        val originalAmount = foundItem.amount

        try {
            val insertItem = if (player.isSneaking) {
                // Shift+空手クリック: 見つかったスタック全体を挿入
                foundItem.clone()
            } else {
                // 通常の空手クリック: 1つだけ挿入
                foundItem.clone().apply { amount = 1 }
            }

            val (result, updatedDrawer) = drawer.insertItemStack(slotIndex, insertItem)

            if (result.success && result.insertedCount > 0) {
                // ドロワーを先に保存（保存失敗時のアイテム消失を防ぐ）
                repository.save(updatedDrawer)

                val newAmount = originalAmount - result.insertedCount
                if (newAmount <= 0) {
                    inventory.setItem(foundSlotIndex, null)
                } else {
                    val updatedItem = foundItem.clone().apply { amount = newAmount }
                    inventory.setItem(foundSlotIndex, updatedItem)
                }

                displayManager.updateSlotDisplay(updatedDrawer, slotIndex)
                player.playSound(player.location, Sound.BLOCK_BARREL_CLOSE, 0.5f, 1.0f)
                MetricsCollector.incrementItemsInserted(result.insertedCount)
            }
        } catch (e: Exception) {
            logger.warning("Error handling empty hand insert for player ${player.name}: ${e.message}")

        }
    }

    /**
     * 左クリック操作を処理する（アイテム取り出し）。
     *
     * - 通常クリック: 1アイテムを取り出し
     * - スニーク+クリック: 1スタックを取り出し
     *
     * エンチャントやNBTタグを含む完全な ItemStack を取り出す。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex 操作対象のスロットインデックス
     * @param player プレイヤー
     */
    private fun handleLeftClick(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player
    ) {
        // 同一tick内の重複処理を防止
        if (isAlreadyProcessedThisTick(player, drawer, "left")) return

        // Void drawer: nothing to extract
        if (drawer.type == DrawerType.VOID) {
            return
        }

        // Track left-click interaction
        MetricsCollector.incrementLeftClickInteractions()

        val slot = drawer.getSlot(slotIndex)

        // Check if slot has items
        if (slot.isEmpty()) {
            return
        }

        val material = slot.storedMaterial
        if (material == null) {
            return
        }

        // Determine extraction amount
        val extractAmount = if (player.isSneaking) {
            // Extract one stack
            minOf(material.maxStackSize, slot.itemCount)
        } else {
            // Extract one item
            1
        }

        try {
            // extractItemStack を使用してエンチャント等を含む完全な ItemStack を取り出す
            val (extractedItemStack, updatedDrawer) = drawer.extractItemStack(slotIndex, extractAmount)

            if (extractedItemStack != null) {
                // ドロワーを先に保存（保存失敗時のアイテム増殖を防ぐ）
                repository.save(updatedDrawer)

                // Add items to player inventory（エンチャント等が保持された ItemStack）
                val leftover = player.inventory.addItem(extractedItemStack)

                // If player's inventory is full, drop the leftover items
                if (leftover.isNotEmpty()) {
                    for (item in leftover.values) {
                        player.world.dropItemNaturally(player.location, item)
                    }
                }

                // Update display
                displayManager.updateSlotDisplay(updatedDrawer, slotIndex)

                // Play sound
                player.playSound(player.location, Sound.BLOCK_BARREL_OPEN, 0.5f, 1.0f)

                // Track metrics
                MetricsCollector.incrementItemsExtracted(extractedItemStack.amount)
            }
        } catch (e: Exception) {
            logger.warning("Error handling left-click for player ${player.name}: ${e.message}")

        }
    }

    /**
     * ボイドドロワーへのアイテム投入を処理する。
     * アイテムは消滅し、復元不可能。
     */
    private fun handleVoidInsert(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        itemInHand: ItemStack
    ) {
        if (itemInHand.type == Material.AIR || itemInHand.amount <= 0) return

        val consumeAmount = if (player.isSneaking) {
            itemInHand.amount  // Shift+click: consume entire stack
        } else {
            1  // Normal click: consume one item
        }

        val actualHandItem = player.inventory.itemInMainHand
        val newAmount = actualHandItem.amount - consumeAmount
        if (newAmount <= 0) {
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        } else {
            // クローンして新しい数量を設定（ライブ参照のミューテーションを防ぐ）
            val updatedItem = actualHandItem.clone().apply { amount = newAmount }
            player.inventory.setItemInMainHand(updatedItem)
        }

        // Play void consume effect
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.3f, 1.5f)

        MetricsCollector.incrementItemsInserted(consumeAmount)
    }

    /**
     * プレイヤー切断時にクリーンアップする。
     *
     * Why: プレイヤーごとのマップがメモリリークしないように、
     * 切断時にエントリを削除する。
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        lastClickTime.remove(uuid)
        lastClickLocation.remove(uuid)
        processedActions.entries.removeIf { it.key.startsWith(uuid.toString()) }
    }

    // ========================================
    // Public API for ItemFrame interaction
    // ========================================

    /**
     * ItemFrame経由でのドロワーインタラクションを処理する。
     *
     * ItemFrameInteractionListenerから呼び出され、
     * ItemFrameをクリックした際にドロワーの操作を実行する。
     *
     * @param player プレイヤー
     * @param drawer ドロワーブロック
     * @param isRightClick 右クリックかどうか（falseの場合は左クリック）
     * @param capturedItemInHand イベント発火時にキャプチャした手持ちアイテム（右クリック時のみ使用）
     */
    fun handleItemFrameInteraction(
        player: Player,
        drawer: DrawerBlock,
        isRightClick: Boolean,
        capturedItemInHand: ItemStack? = null
    ) {
        // スロットは常に0（1x1ドロワーの場合）
        // 将来的に複数スロットをサポートする場合は、クリック位置からスロットを判定する
        val slotIndex = 0

        if (isRightClick) {
            // 右クリック: アイテム挿入
            handleItemFrameRightClick(drawer, slotIndex, player, capturedItemInHand)
        } else {
            // 左クリック: エッジ判定を行い、エッジクリックは破壊を許可する
            if (isClickOnFrontEdge(player, drawer, drawer.facing)) {
                return
            }
            handleLeftClick(drawer, slotIndex, player)
        }
    }

    /**
     * ItemFrame経由での右クリック操作を処理する。
     *
     * handleRightClickと同様だが、PlayerInteractEventを必要としない。
     * イベント発火時にキャプチャしたアイテムを使用する。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex スロットインデックス
     * @param player プレイヤー
     * @param capturedItemInHand イベント発火時にキャプチャした手持ちアイテム
     */
    private fun handleItemFrameRightClick(
        drawer: DrawerBlock,
        slotIndex: Int,
        player: Player,
        capturedItemInHand: ItemStack?
    ) {
        // 同一tick内の重複処理を防止
        if (isAlreadyProcessedThisTick(player, drawer, "right")) return

        // キャプチャされたアイテムを使用（なければ現在のインベントリから取得）
        val itemInHand = capturedItemInHand ?: player.inventory.itemInMainHand

        // Track right-click interaction
        MetricsCollector.incrementRightClickInteractions()

        // Handle Drawer Key for lock/unlock
        if (DrawerKeyFactory.isDrawerKey(itemInHand)) {
            handleDrawerKeyInteraction(drawer, slotIndex, player)
            return
        }

        // Void drawer: consume items with special effect
        if (drawer.type == DrawerType.VOID) {
            handleVoidInsert(drawer, slotIndex, player, itemInHand)
            return
        }

        // Check if player has items to insert
        if (itemInHand.type == Material.AIR || itemInHand.amount <= 0) {
            // 空の手の場合: ドロワーに格納されているアイテムタイプと同じアイテムをインベントリから検索して挿入
            val slot = drawer.getSlot(slotIndex)
            val targetMaterial = slot.storedMaterial ?: slot.lockedMaterial
            if (targetMaterial != null) {
                handleEmptyHandInsert(drawer, slotIndex, player, targetMaterial, slot.storedItemTemplate)
            }
            return
        }

        // ダブルクリック判定
        val isDoubleClicked = isDoubleClick(player, drawer)

        try {
            if (isDoubleClicked) {
                // Double-click: insert ALL matching items from entire inventory
                handleBulkInsertFromInventory(drawer, slotIndex, player, itemInHand.type)
            } else if (player.isSneaking) {
                // Shift+click: insert entire hand stack
                handleSingleInsert(drawer, slotIndex, player, itemInHand)
            } else {
                // Normal click: insert one item only
                handleSingleItemInsert(drawer, slotIndex, player, itemInHand)
            }
        } catch (e: Exception) {
            logger.warning("Error handling ItemFrame right-click for player ${player.name}: ${e.message}")

        }
    }
}
