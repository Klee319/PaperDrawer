package com.example.paperdrawers.infrastructure.display

import com.example.paperdrawers.domain.model.DrawerBlock
import com.example.paperdrawers.domain.model.DrawerType
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ドロワーのアイテム数を表示する TextDisplay エンティティを管理するヘルパークラス。
 *
 * Why: アイテム表示（ItemDisplay）の下にアイテム数を表示することで、
 * プレイヤーが格納されているアイテム数を一目で確認できるようにする。
 *
 * 表示フォーマット:
 * - 1,000 未満: そのまま表示（例: "64", "999"）
 * - 1,000 以上 1,000,000 未満: "K" 形式（例: "1.5K", "999K"）
 * - 1,000,000 以上 1,000,000,000 未満: "M" 形式（例: "1.2M"）
 * - 1,000,000,000 以上: "B" 形式（例: "2.1B"）
 *
 * 表示位置:
 * - ItemDisplay の下に配置
 * - facing 方向に応じて正しい向きで表示
 * - Java版: 0.3ブロック前方、0.5ブロック下
 * - 統合版（Geyser）: 0.4ブロック前方
 *
 * @property plugin プラグインインスタンス（NamespacedKey 生成に使用）
 * @property logger ログ出力用のロガー
 * @property isBedrockServerMode Geyser/Floodgateがインストールされているか（統合版プレイヤーがいる可能性がある）
 */
class TextDisplayHelper(
    private val plugin: Plugin,
    private val logger: Logger,
    private val isBedrockServerMode: Boolean = false
) {

    /** ドロワー ID を保存するための PDC キー */
    private val drawerIdKey: NamespacedKey = NamespacedKey(plugin, "text_display_drawer_id")

    /** スロットインデックスを保存するための PDC キー */
    private val slotIndexKey: NamespacedKey = NamespacedKey(plugin, "text_display_slot_index")

    /**
     * ドロワースロットのアイテム数表示を作成する。
     *
     * スロットが空の場合は null を返し、エンティティは作成されない。
     *
     * @param drawer 表示対象のドロワーブロック
     * @param slotIndex 作成するスロットのインデックス
     * @param useCompactFormat コンパクトフォーマット（K, M, B 形式）を使用するか
     * @return 作成された TextDisplay エンティティの UUID、スロットが空の場合は null
     */
    fun createCountDisplay(
        drawer: DrawerBlock,
        slotIndex: Int,
        useCompactFormat: Boolean = true
    ): UUID? {
        val slot = drawer.getSlot(slotIndex)

        // スロットが空の場合は表示を作成しない
        if (slot.isEmpty()) {
            return null
        }

        val world = drawer.location.world ?: run {
            logger.warning("Cannot create text display: drawer world is null")
            return null
        }

        // 表示位置を計算（ItemDisplay の下）
        val displayLocation = calculateTextDisplayLocation(drawer, slotIndex)

        return try {
            val textDisplay = world.spawn(displayLocation, TextDisplay::class.java) { entity ->
                // テキストを設定
                entity.text(formatCount(slot.itemCount, useCompactFormat))

                // 変換（回転、スケール）を設定
                // Note: BILLBOARDモードでは回転は無視されるが、スケールは適用される
                entity.transformation = Transformation(
                    Vector3f(0f, 0f, 0f),  // translation
                    Quaternionf(),         // leftRotation (identity - no rotation needed for billboard)
                    Vector3f(SCALE_SINGLE, SCALE_SINGLE, SCALE_SINGLE), // scale
                    Quaternionf()          // rightRotation (identity)
                )

                // ビルボードモード: プレイヤーに常に向く（デバッグ用に CENTER を使用）
                // CENTER = テキストが常にプレイヤーの方を向く
                entity.billboard = Display.Billboard.CENTER

                // 背景色を設定（半透明の黒）
                entity.backgroundColor = BACKGROUND_COLOR

                // テキスト設定
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.isSeeThrough = false
                entity.isDefaultBackground = false
                entity.lineWidth = 200  // 十分な幅を確保

                // 表示範囲と影を設定
                entity.viewRange = 64.0f  // 視認距離を増加
                entity.shadowRadius = 0.0f
                entity.shadowStrength = 0.0f

                // 明るさを最大に設定（暗い場所でも見えるように）
                entity.brightness = Display.Brightness(15, 15)

                // 非永続化設定
                entity.isPersistent = false

                // PDC にドロワー ID とスロットインデックスを保存
                entity.persistentDataContainer.set(
                    drawerIdKey,
                    PersistentDataType.STRING,
                    drawer.id.toString()
                )
                entity.persistentDataContainer.set(
                    slotIndexKey,
                    PersistentDataType.INTEGER,
                    slotIndex
                )
            }

            logger.fine("Created TextDisplay for drawer ${drawer.id}, slot $slotIndex at $displayLocation, count=${slot.itemCount}")
            textDisplay.uniqueId
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to create TextDisplay for drawer ${drawer.id}", e)
            null
        }
    }

    /**
     * 既存の TextDisplay のカウントを更新する。
     *
     * @param entityId 更新対象の TextDisplay エンティティ UUID
     * @param count 新しいアイテム数
     * @param useCompactFormat コンパクトフォーマットを使用するか
     */
    fun updateCountDisplay(entityId: UUID, count: Int, useCompactFormat: Boolean = true) {
        val entity = org.bukkit.Bukkit.getEntity(entityId) as? TextDisplay ?: run {
            logger.fine("TextDisplay entity $entityId not found for update")
            return
        }

        if (count <= 0) {
            entity.remove()
            logger.fine("Removed TextDisplay $entityId because count is 0")
            return
        }

        entity.text(formatCount(count, useCompactFormat))
    }

    /**
     * 既存の TextDisplay をドロワーのスロット情報から更新する。
     *
     * @param drawer ドロワーブロック
     * @param slotIndex スロットインデックス
     * @param entityId 更新対象の TextDisplay エンティティ UUID
     * @param useCompactFormat コンパクトフォーマットを使用するか
     */
    fun updateCountDisplay(
        drawer: DrawerBlock,
        slotIndex: Int,
        entityId: UUID,
        useCompactFormat: Boolean = true
    ) {
        val slot = drawer.getSlot(slotIndex)
        updateCountDisplay(entityId, slot.itemCount, useCompactFormat)
    }

    /**
     * TextDisplay エンティティを削除する。
     *
     * @param entityId 削除対象のエンティティ UUID
     */
    fun removeCountDisplay(entityId: UUID) {
        val entity = org.bukkit.Bukkit.getEntity(entityId) ?: run {
            logger.fine("Entity $entityId not found for removal")
            return
        }

        if (entity is TextDisplay) {
            entity.remove()
            logger.fine("Removed TextDisplay $entityId")
        }
    }

    /**
     * アイテム数をフォーマットする。
     *
     * コンパクトフォーマット使用時:
     * - 1,000 未満: そのまま
     * - 1K, 1.5K, 999K など
     * - 1M, 1.5M, 999M など
     * - 1B, 1.5B など
     *
     * 通常フォーマット:
     * - カンマ区切りの数値（例: "1,234,567"）
     *
     * @param count アイテム数
     * @param useCompact コンパクトフォーマットを使用するか
     * @return フォーマットされた文字列の Component
     */
    private fun formatCount(count: Int, useCompact: Boolean): net.kyori.adventure.text.Component {
        val text = if (useCompact) {
            formatCompact(count)
        } else {
            NUMBER_FORMAT.format(count)
        }

        return net.kyori.adventure.text.Component.text(text)
            .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
    }

    /**
     * コンパクトフォーマットで数値を文字列に変換する。
     *
     * @param count 数値
     * @return フォーマットされた文字列
     */
    private fun formatCompact(count: Int): String {
        return when {
            count < THOUSAND -> count.toString()
            count < MILLION -> formatWithSuffix(count, THOUSAND, "K")
            count < BILLION -> formatWithSuffix(count, MILLION, "M")
            else -> formatWithSuffix(count, BILLION, "B")
        }
    }

    /**
     * 接尾辞付きでフォーマットする。
     *
     * @param count 数値
     * @param divisor 除数
     * @param suffix 接尾辞
     * @return フォーマットされた文字列
     */
    private fun formatWithSuffix(count: Int, divisor: Int, suffix: String): String {
        val value = count.toDouble() / divisor
        return if (value >= 100) {
            // 100以上の場合は小数点以下なし
            "${value.toInt()}$suffix"
        } else if (value >= 10) {
            // 10以上の場合は小数点以下1桁
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.') + suffix
        } else {
            // 10未満の場合は小数点以下1桁
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.') + suffix
        }
    }

    /**
     * TextDisplay の表示位置を計算する。
     *
     * ItemDisplay の下に配置するため、Y 座標を下げる。
     * Java版: 0.3ブロック前方、0.5ブロック下
     * 統合版（Geyser）: 0.4ブロック前方
     *
     * @param drawer ドロワーブロック
     * @param slotIndex スロットインデックス
     * @return TextDisplay を配置する位置
     */
    private fun calculateTextDisplayLocation(drawer: DrawerBlock, slotIndex: Int): Location {
        val blockCenter = drawer.location.clone().add(0.5, 0.5, 0.5)
        val slotPositions = drawer.type.getSlotPositions()
        val slotPos = slotPositions[slotIndex]

        // プラットフォームに応じたオフセットを選択
        val faceOffset = if (isBedrockServerMode) FACE_OFFSET_BEDROCK else FACE_OFFSET_JAVA

        // 正規化座標（0-1）を -0.5 から 0.5 の範囲に変換
        val relativeX = slotPos.x - 0.5f
        // Y座標を下げて ItemDisplay の下に配置
        val relativeY = -(slotPos.y - 0.5f) - getTextOffsetY(drawer.type)

        // facing 方向に基づいて位置を計算
        val (offsetX, offsetY, offsetZ) = when (drawer.facing) {
            BlockFace.NORTH -> Triple(
                -relativeX.toDouble(),
                relativeY.toDouble(),
                -0.5 - faceOffset
            )
            BlockFace.SOUTH -> Triple(
                relativeX.toDouble(),
                relativeY.toDouble(),
                0.5 + faceOffset
            )
            BlockFace.EAST -> Triple(
                0.5 + faceOffset,
                relativeY.toDouble(),
                -relativeX.toDouble()
            )
            BlockFace.WEST -> Triple(
                -0.5 - faceOffset,
                relativeY.toDouble(),
                relativeX.toDouble()
            )
            else -> Triple(0.0, relativeY.toDouble(), -0.5 - faceOffset)
        }

        return blockCenter.add(offsetX, offsetY, offsetZ)
    }

    /**
     * DrawerType に基づいてテキスト表示の Y オフセットを取得する。
     *
     * Java版: 下方オフセットなし
     * 統合版: 少し下に配置
     *
     * @param type ドロワーの種類
     * @return Y 方向のオフセット値
     */
    @Suppress("UNUSED_PARAMETER")
    private fun getTextOffsetY(type: DrawerType): Float {
        return if (isBedrockServerMode) TEXT_OFFSET_Y_BEDROCK else TEXT_OFFSET_Y_JAVA
    }

    companion object {
        /** ブロック面からのオフセット距離（Java版: ストレージにがっつり寄せる） */
        private const val FACE_OFFSET_JAVA = 0.05

        /** ブロック面からのオフセット距離（統合版: 0.1ブロック） */
        private const val FACE_OFFSET_BEDROCK = 0.1

        /** シングルドロワーのテキスト Y オフセット（Java版用） */
        private const val TEXT_OFFSET_Y_JAVA = 0.18f

        /** シングルドロワーのテキスト Y オフセット（統合版用） */
        private const val TEXT_OFFSET_Y_BEDROCK = 0.4f

        /** シングルドロワーのスケール */
        private const val SCALE_SINGLE = 0.6f

        /** 数値フォーマットの定数 */
        private const val THOUSAND = 1_000
        private const val MILLION = 1_000_000
        private const val BILLION = 1_000_000_000

        /** 通常の数値フォーマット（カンマ区切り） */
        private val NUMBER_FORMAT: NumberFormat = NumberFormat.getNumberInstance(Locale.US)

        /** 背景色（半透明の黒、透明度 200/255） */
        private val BACKGROUND_COLOR: Color = Color.fromARGB(200, 0, 0, 0)
    }
}
