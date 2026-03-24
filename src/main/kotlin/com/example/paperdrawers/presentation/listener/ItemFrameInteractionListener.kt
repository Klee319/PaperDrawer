package com.example.paperdrawers.presentation.listener

import com.example.paperdrawers.domain.repository.DrawerRepository
import com.example.paperdrawers.infrastructure.display.DisplayManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * ドロワー表示用ItemFrameへのインタラクションを処理するリスナー。
 *
 * ItemFrameへのクリックをドロワーブロックへのクリックとして転送し、
 * ユーザーがItemFrameを直接操作できないようにする。
 *
 * @property plugin プラグインインスタンス
 * @property repository ドロワーデータの永続化を担当するリポジトリ
 * @property displayManager ドロワーの視覚的表示を管理するマネージャー
 * @property drawerInteractionListener ドロワーインタラクションを処理するリスナー
 * @property logger ログ出力用のロガー
 */
class ItemFrameInteractionListener(
    private val plugin: Plugin,
    private val repository: DrawerRepository,
    private val displayManager: DisplayManager,
    private val drawerInteractionListener: DrawerInteractionListener,
    private val logger: Logger
) : Listener {

    /** PDC キー: ドロワー ID */
    private val keyDrawerId: NamespacedKey = NamespacedKey(plugin, "display_drawer_id")

    /** PDC キー: スロットインデックス */
    private val keySlotIndex: NamespacedKey = NamespacedKey(plugin, "display_slot_index")

    /** PDC キー: ドロワーの位置（world:x:y:z形式） */
    private val keyDrawerLocation: NamespacedKey = NamespacedKey(plugin, "display_drawer_location")

    /**
     * プレイヤーごとの手持ちアイテムキャッシュ。
     * PlayerInteractEventで更新し、PlayerInteractEntityEvent時に参照する。
     * Key: PlayerのUUID
     * Value: Pair<アイテム, キャッシュ時刻(ms)>
     */
    private val playerHandCache: MutableMap<UUID, Pair<ItemStack, Long>> = ConcurrentHashMap()

    /** キャッシュの有効期限（ミリ秒） */
    private val CACHE_EXPIRY_MS = 100L

    /**
     * 右クリック処理中のプレイヤーを追跡（重複処理防止用）。
     * Key: PlayerのUUID
     * Value: 最後の右クリック時刻(ms)
     */
    private val recentRightClicks: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** 右クリック処理の重複防止時間（ミリ秒） */
    private val RIGHT_CLICK_COOLDOWN_MS = 200L

    /**
     * 左クリック処理中のプレイヤーを追跡（重複処理防止用）。
     * Key: PlayerのUUID
     * Value: 最後の左クリック時刻(ms)
     */
    private val recentLeftClicks: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** 左クリック処理の重複防止時間（ミリ秒） */
    private val LEFT_CLICK_COOLDOWN_MS = 100L

    /**
     * 右クリック処理を記録する（外部から呼び出し可能）。
     *
     * DrawerInteractionListener がブロックへの右クリックを処理した際に呼び出され、
     * onPlayerAnimation での誤検出を防ぐ。
     *
     * @param player プレイヤー
     */
    fun recordRightClick(player: Player) {
        recentRightClicks[player.uniqueId] = System.currentTimeMillis()
    }

    /**
     * 左クリック処理を記録する。
     */
    private fun recordLeftClick(player: Player) {
        recentLeftClicks[player.uniqueId] = System.currentTimeMillis()
    }

    /**
     * 最近左クリック処理済みかを判定する。
     */
    private fun isRecentlyLeftClicked(player: Player): Boolean {
        val lastClick = recentLeftClicks[player.uniqueId] ?: return false
        return System.currentTimeMillis() - lastClick < LEFT_CLICK_COOLDOWN_MS
    }

    /**
     * PlayerInteractEvent（ブロック/空気クリック）を処理する。
     * - 右クリック: 手持ちアイテムをキャッシュ
     * - 左クリック: レイトレースでItemFrameを検出し、取り出し処理を実行
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onPlayerInteract(event: org.bukkit.event.player.PlayerInteractEvent) {
        val player = event.player
        val action = event.action

        when (action) {
            org.bukkit.event.block.Action.RIGHT_CLICK_AIR,
            org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> {
                // 右クリック: アイテムをキャッシュ
                val itemInHand = player.inventory.itemInMainHand
                if (itemInHand.type != Material.AIR) {
                    playerHandCache[player.uniqueId] = Pair(itemInHand.clone(), System.currentTimeMillis())
                }
            }
            org.bukkit.event.block.Action.LEFT_CLICK_AIR -> {
                // 左クリック（空気）: レイトレースでItemFrameを検出
                handleLeftClickRayTrace(player)
            }
            org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                // 左クリック（ブロック）: クリックしたブロックの前面にあるItemFrameを検索
                val clickedBlock = event.clickedBlock
                val blockFace = event.blockFace
                if (clickedBlock != null) {
                    handleLeftClickOnBlock(player, clickedBlock.location, blockFace)
                }
            }
            else -> {}
        }
    }

    /**
     * ブロックをクリックした際に、そのブロックに関連するドロワーItemFrameを検索する。
     *
     * ドロワー（樽）ブロックをクリックした場合、その前面にあるItemFrameを検出して
     * 取り出し処理を実行する。
     */
    private fun handleLeftClickOnBlock(player: Player, blockLocation: Location, blockFace: BlockFace) {
        if (isRecentlyLeftClicked(player)) return

        // まずクリックしたブロックがドロワーかチェック
        val drawer = repository.findByLocation(blockLocation)
        if (drawer != null && blockFace == drawer.facing) {
            recordLeftClick(player)
            drawerInteractionListener.handleItemFrameInteraction(player, drawer, isRightClick = false)
            return
        }

        // ブロックの前面付近にあるItemFrameを検索
        val searchLocation = blockLocation.clone().add(0.5, 0.5, 0.5)
        val nearbyEntities = searchLocation.world?.getNearbyEntities(searchLocation, 1.5, 1.5, 1.5) { entity ->
            entity is ItemFrame
        } ?: return

        for (entity in nearbyEntities) {
            if (entity !is ItemFrame) continue

            val drawerLocationString = entity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
            if (drawerLocationString == null) continue

            val drawerLocation = parseLocationKey(drawerLocationString) ?: continue
            val foundDrawer = repository.findByLocation(drawerLocation) ?: continue

            // このItemFrameがクリックしたブロックに関連しているかチェック
            // ブロック直接クリック時は正面のみ許可、隣接ブロック経由は前面判定
            if ((drawerLocation == blockLocation && blockFace == foundDrawer.facing) ||
                isItemFrameInFrontOf(entity, blockLocation, blockFace)) {

                recordLeftClick(player)
                drawerInteractionListener.handleItemFrameInteraction(player, foundDrawer, isRightClick = false)
                return
            }
        }
    }

    /**
     * ItemFrameがブロックの指定された面の前にあるかチェックする。
     */
    private fun isItemFrameInFrontOf(itemFrame: ItemFrame, blockLocation: Location, blockFace: BlockFace): Boolean {
        val frameLocation = itemFrame.location
        val blockX = blockLocation.blockX
        val blockY = blockLocation.blockY
        val blockZ = blockLocation.blockZ
        val frameX = frameLocation.blockX
        val frameY = frameLocation.blockY
        val frameZ = frameLocation.blockZ

        return when (blockFace) {
            BlockFace.NORTH -> frameX == blockX && frameY == blockY && frameZ == blockZ - 1
            BlockFace.SOUTH -> frameX == blockX && frameY == blockY && frameZ == blockZ + 1
            BlockFace.EAST -> frameX == blockX + 1 && frameY == blockY && frameZ == blockZ
            BlockFace.WEST -> frameX == blockX - 1 && frameY == blockY && frameZ == blockZ
            BlockFace.UP -> frameX == blockX && frameY == blockY + 1 && frameZ == blockZ
            BlockFace.DOWN -> frameX == blockX && frameY == blockY - 1 && frameZ == blockZ
            else -> false
        }
    }

    /**
     * レイトレースを使用してプレイヤーの視線先にあるドロワーItemFrameを検出し、
     * 取り出し処理を実行する。
     *
     * isVisible=false のItemFrameはヒットボックスが縮小されるため、
     * EntityDamageByEntityEventだけでは中心部分をクリックできない。
     */
    private fun handleLeftClickRayTrace(player: Player) {
        if (isRecentlyLeftClicked(player)) return

        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction
        val maxDistance = 5.0

        // レイトレースでエンティティを検出
        val rayTraceResult = player.world.rayTraceEntities(
            eyeLocation,
            direction,
            maxDistance,
            1.0  // エンティティの検出半径をさらに広げる
        ) { entity ->
            // ItemFrameのみを対象
            entity is ItemFrame
        }

        val hitEntity = rayTraceResult?.hitEntity
        if (hitEntity !is ItemFrame) {
            return
        }

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = hitEntity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            return
        }

        // 位置情報をパース
        val drawerLocation = parseLocationKey(drawerLocationString)
        if (drawerLocation == null) {
            logger.warning("Invalid drawer location in ItemFrame: $drawerLocationString")
            return
        }

        // ドロワーを取得
        val drawer = repository.findByLocation(drawerLocation)
        if (drawer == null) {
            return
        }

        // ドロワーから取り出し処理を実行
        recordLeftClick(player)
        drawerInteractionListener.handleItemFrameInteraction(player, drawer, isRightClick = false)
    }

    /**
     * プレイヤーの手持ちアイテムが変更されたときにキャッシュを更新する。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val newItem = player.inventory.getItem(event.newSlot) ?: ItemStack(Material.AIR)
        if (newItem.type != Material.AIR) {
            playerHandCache[player.uniqueId] = Pair(newItem.clone(), System.currentTimeMillis())
        }
    }

    /**
     * プレイヤーが腕を振った（左クリック）ときにItemFrameを検出する。
     *
     * PlayerInteractEvent がエンティティクリック時に発火しない場合のフォールバック。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerAnimation(event: PlayerAnimationEvent) {
        // ARM_SWING = 左クリック時の腕振りアニメーション
        if (event.animationType != PlayerAnimationType.ARM_SWING) {
            return
        }

        val player = event.player

        // 右クリック処理直後の場合はスキップ（重複処理防止）
        val lastRightClick = recentRightClicks[player.uniqueId]
        if (lastRightClick != null && System.currentTimeMillis() - lastRightClick < RIGHT_CLICK_COOLDOWN_MS) {
            return
        }

        // 最近左クリック処理済みの場合はスキップ
        if (isRecentlyLeftClicked(player)) return

        // プレイヤーの視線先にあるエンティティを検索
        val targetEntity = getTargetItemFrame(player)
        if (targetEntity == null) {
            return
        }

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = targetEntity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            return
        }

        // 位置情報をパース
        val drawerLocation = parseLocationKey(drawerLocationString)
        if (drawerLocation == null) {
            logger.warning("Invalid drawer location in ItemFrame: $drawerLocationString")
            return
        }

        // ドロワーを取得
        val drawer = repository.findByLocation(drawerLocation)
        if (drawer == null) {
            return
        }

        // ドロワーから取り出し処理を実行
        recordLeftClick(player)
        drawerInteractionListener.handleItemFrameInteraction(player, drawer, isRightClick = false)
    }

    /**
     * プレイヤーの視線先にあるItemFrameを取得する。
     */
    private fun getTargetItemFrame(player: Player): ItemFrame? {
        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction
        val maxDistance = 5.0

        // 近くのエンティティを検索
        val nearbyEntities = player.world.getNearbyEntities(
            eyeLocation,
            maxDistance,
            maxDistance,
            maxDistance
        ) { entity -> entity is ItemFrame }

        // 視線方向に最も近いItemFrameを見つける
        var closestFrame: ItemFrame? = null
        var closestDistance = Double.MAX_VALUE

        for (entity in nearbyEntities) {
            if (entity !is ItemFrame) continue

            val toEntity = entity.location.toVector().subtract(eyeLocation.toVector())
            val distance = toEntity.length()

            // 距離が遠すぎる場合はスキップ
            if (distance > maxDistance) continue

            // 視線方向との角度をチェック
            val angle = toEntity.normalize().angle(direction)
            if (angle > Math.toRadians(15.0)) continue  // 15度以内

            if (distance < closestDistance) {
                closestDistance = distance
                closestFrame = entity
            }
        }

        return closestFrame
    }

    /**
     * キャッシュからアイテムを取得する（有効期限内のみ）。
     */
    private fun getCachedItem(player: Player): ItemStack? {
        val cached = playerHandCache[player.uniqueId] ?: return null
        val (item, timestamp) = cached

        // 有効期限チェック
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) {
            return null
        }

        return item
    }

    /**
     * ItemFrameへの右クリック（アイテム挿入操作）を処理する。
     *
     * ドロワー表示用のItemFrameがクリックされた場合、イベントをキャンセルし、
     * 対応するドロワーブロックへのインタラクションとして処理する。
     *
     * 重要: イベント発火時点で手持ちアイテムがすでにAIRになっている場合があるため、
     * ItemFrameに置かれたアイテムを検出して回復する。
     *
     * @param event PlayerInteractEntityEvent
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked

        // ItemFrame以外は無視
        if (entity !is ItemFrame) {
            return
        }

        val player = event.player
        val hand = event.hand

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = entity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            // ドロワー表示用でない通常のItemFrame
            return
        }

        // イベントをキャンセル（ItemFrameのアイテム操作を防ぐ）
        event.isCancelled = true

        // メインハンドのみ処理（オフハンドは無視）
        if (hand != org.bukkit.inventory.EquipmentSlot.HAND) {
            return
        }

        // 位置情報をパース（world:x:y:z形式）
        val drawerLocation = parseLocationKey(drawerLocationString)
        if (drawerLocation == null) {
            logger.warning("Invalid drawer location in ItemFrame: $drawerLocationString")
            entity.remove()
            return
        }

        // ドロワーを取得
        val drawer = repository.findByLocation(drawerLocation)
        if (drawer == null) {
            entity.remove()
            return
        }

        // 手持ちアイテムを検出（複数の方法を試す）
        var itemToInsert: ItemStack? = null

        // 方法1: PlayerInteractEventでキャッシュしたアイテムをチェック（最優先）
        val cachedItem = getCachedItem(player)
        if (cachedItem != null && cachedItem.type != Material.AIR) {
            itemToInsert = cachedItem.clone()
        }

        // 方法2: 現在の手持ちアイテムをチェック
        if (itemToInsert == null) {
            val currentHandItem = player.inventory.itemInMainHand
            if (currentHandItem.type != Material.AIR && currentHandItem.amount > 0) {
                itemToInsert = currentHandItem.clone()
            }
        }

        // 方法3: ItemFrameにプレイヤーのアイテムが置かれていないかチェック
        if (itemToInsert == null) {
            val slot = drawer.getSlot(0)
            val expectedMaterial = slot.storedMaterial
            val frameItem = entity.item

            if (frameItem.type != Material.AIR && frameItem.type != expectedMaterial) {
                // ItemFrameにドロワーのアイテムと異なるアイテムがある = プレイヤーのアイテム
                itemToInsert = frameItem.clone()

                // ItemFrameを元の状態に戻す
                val originalItem = if (expectedMaterial != null) {
                    slot.storedItemTemplate?.clone()?.apply { amount = 1 }
                        ?: ItemStack(expectedMaterial, 1)
                } else {
                    ItemStack(Material.AIR)
                }
                entity.setItem(originalItem, false)
            }
        }

        // 右クリック処理中であることを記録（PlayerAnimationEventで重複処理を防ぐ）
        recentRightClicks[player.uniqueId] = System.currentTimeMillis()

        // キャプチャしたアイテムを渡してドロワーインタラクションを処理
        drawerInteractionListener.handleItemFrameInteraction(player, drawer, isRightClick = true, itemToInsert)
    }

    /**
     * ItemFrameへの左クリック（攻撃/アイテム取り出し操作）を処理する。
     *
     * ドロワー表示用のItemFrameが攻撃された場合、イベントをキャンセルし、
     * 対応するドロワーブロックへのインタラクションとして処理する。
     *
     * @param event EntityDamageByEntityEvent
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val entity = event.entity

        // ItemFrame以外は無視
        if (entity !is ItemFrame) {
            return
        }

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = entity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            // ドロワー表示用でない通常のItemFrame
            return
        }

        // イベントをキャンセル（ItemFrameへのダメージを防ぐ）
        event.isCancelled = true

        val damager = event.damager
        if (damager !is Player) {
            return
        }

        val player = damager

        // 最近処理済みの場合はスキップ
        if (isRecentlyLeftClicked(player)) return

        // 位置情報をパース（world:x:y:z形式）
        val drawerLocation = parseLocationKey(drawerLocationString)
        if (drawerLocation == null) {
            logger.warning("Invalid drawer location in ItemFrame: $drawerLocationString")
            entity.remove()
            return
        }

        // ドロワーを取得
        val drawer = repository.findByLocation(drawerLocation)
        if (drawer == null) {
            // 孤立したItemFrameを削除
            entity.remove()
            return
        }

        // ドロワーインタラクションリスナーを直接呼び出す
        recordLeftClick(player)
        drawerInteractionListener.handleItemFrameInteraction(player, drawer, isRightClick = false)
    }

    /**
     * ItemFrame（吊り下げエンティティ）がプレイヤーによって破壊されるのを防ぐ。
     *
     * ItemFrameはHangingエンティティなので、プレイヤーが攻撃すると
     * HangingBreakByEntityEventが発火する。これをキャンセルしないと
     * ItemFrameが壊れてしまう。
     *
     * @param event HangingBreakByEntityEvent
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onHangingBreakByEntity(event: HangingBreakByEntityEvent) {
        val entity = event.entity

        // ItemFrame以外は無視
        if (entity !is ItemFrame) {
            return
        }

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = entity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            // ドロワー表示用でない通常のItemFrame
            return
        }

        // イベントをキャンセル（ItemFrameの破壊を防ぐ）
        event.isCancelled = true
    }

    /**
     * ItemFrameが物理的な要因（爆発、ピストンなど）で破壊されるのを防ぐ。
     *
     * @param event HangingBreakEvent
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onHangingBreak(event: HangingBreakEvent) {
        val entity = event.entity

        // ItemFrame以外は無視
        if (entity !is ItemFrame) {
            return
        }

        // ドロワー表示用のItemFrameかチェック
        val drawerLocationString = entity.persistentDataContainer.get(keyDrawerLocation, PersistentDataType.STRING)
        if (drawerLocationString == null) {
            // ドロワー表示用でない通常のItemFrame
            return
        }

        // ENTITY以外の破壊原因（爆発、物理など）の場合のみキャンセル
        // ENTITYの場合はHangingBreakByEntityEventで処理される
        if (event.cause != HangingBreakEvent.RemoveCause.ENTITY) {
            event.isCancelled = true
        }
    }

    /**
     * プレイヤー切断時にクリーンアップする。
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        playerHandCache.remove(uuid)
        recentRightClicks.remove(uuid)
        recentLeftClicks.remove(uuid)
    }

    /**
     * 位置キー文字列をLocationオブジェクトにパースする。
     *
     * @param locationKey "world:x:y:z" 形式の文字列
     * @return Locationオブジェクト、パースに失敗した場合はnull
     */
    private fun parseLocationKey(locationKey: String): Location? {
        return try {
            val parts = locationKey.split(":")
            if (parts.size != 4) return null

            val worldName = parts[0]
            val x = parts[1].toInt()
            val y = parts[2].toInt()
            val z = parts[3].toInt()

            val world = Bukkit.getWorld(worldName) ?: return null
            Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        } catch (e: Exception) {
            null
        }
    }
}
