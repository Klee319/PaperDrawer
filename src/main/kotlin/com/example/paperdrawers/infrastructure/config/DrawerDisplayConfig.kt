package com.example.paperdrawers.infrastructure.config

import com.example.paperdrawers.domain.model.DrawerType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.ConfigurationSection
import java.util.logging.Logger

/**
 * ドロワーアイテムの表示テキスト設定を管理するクラス。
 *
 * Why: config.ymlからドロワーの表示名とloreを読み込むことで、
 * サーバー管理者がプレイヤーに表示されるテキストをカスタマイズ可能にする。
 * レガシーカラーコード（&記法）とプレースホルダーをサポートする。
 *
 * @property configSection drawer-displayセクションの ConfigurationSection（null許容）
 * @property logger ログ出力用ロガー
 */
class DrawerDisplayConfig(
    private val configSection: ConfigurationSection?,
    private val logger: Logger
) {
    companion object {
        /** レガシーカラーコードのシリアライザ（& 記法） */
        private val LEGACY_SERIALIZER: LegacyComponentSerializer =
            LegacyComponentSerializer.legacyAmpersand()

        // プレースホルダー定数
        private const val PLACEHOLDER_SLOTS = "{slots}"
        private const val PLACEHOLDER_CAPACITY_PER_SLOT = "{capacity_per_slot}"
        private const val PLACEHOLDER_TOTAL_CAPACITY = "{total_capacity}"
    }

    /**
     * 指定されたドロワータイプの表示名を取得する。
     *
     * Why: config.ymlの設定を優先し、設定がない場合はデフォルト値にフォールバックする。
     * これによりサーバー管理者のカスタマイズと安定動作を両立する。
     *
     * @param type 対象のドロワータイプ
     * @return 表示名のComponentオブジェクト（BOLDデコレーション付き、ITALIC無効）
     */
    fun getDisplayName(type: DrawerType, isSorting: Boolean = false): Component {
        val tierKey = when {
            type == DrawerType.VOID -> "void"
            isSorting -> "sorting-tier-${type.tier}"
            else -> "tier-${type.tier}"
        }
        val nameString = configSection?.getConfigurationSection(tierKey)?.getString("name")

        if (nameString == null) {
            val fallbackName = if (isSorting) type.displayName.replace("ドロワー", "仕分けドロワー") else type.displayName
            logger.fine("No display name config for $tierKey, using default: $fallbackName")
            return buildDefaultDisplayName(type, isSorting)
        }

        return LEGACY_SERIALIZER.deserialize(nameString)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
    }

    /**
     * 指定されたドロワータイプのlore（説明文）を取得する。
     *
     * Why: プレースホルダーを実際の値に置換してからコンポーネントに変換する。
     * 空文字列は Component.empty() として扱い、適切な行間を維持する。
     *
     * @param type 対象のドロワータイプ
     * @return lore行のComponentリスト（各行 ITALIC無効）
     */
    fun getLore(type: DrawerType, isSorting: Boolean = false): List<Component> {
        val tierKey = when {
            type == DrawerType.VOID -> "void"
            isSorting -> "sorting-tier-${type.tier}"
            else -> "tier-${type.tier}"
        }
        val loreStrings = configSection?.getConfigurationSection(tierKey)?.getStringList("lore")

        if (loreStrings.isNullOrEmpty()) {
            logger.fine("No lore config for $tierKey, using default lore.")
            return buildDefaultLore(type)
        }

        val slotCount = type.getSlotCount()
        val capacityPerSlot = DrawerCapacityConfig.getCapacity(type)
        val totalCapacity = slotCount * capacityPerSlot

        return loreStrings.map { line ->
            if (line.isEmpty()) {
                Component.empty()
            } else {
                val replaced = line
                    .replace(PLACEHOLDER_SLOTS, slotCount.toString())
                    .replace(PLACEHOLDER_CAPACITY_PER_SLOT, capacityPerSlot.toString())
                    .replace(PLACEHOLDER_TOTAL_CAPACITY, totalCapacity.toString())

                LEGACY_SERIALIZER.deserialize(replaced)
                    .decoration(TextDecoration.ITALIC, false)
            }
        }
    }

    /**
     * デフォルトの表示名コンポーネントを構築する。
     *
     * Why: config.ymlに設定がない場合のフォールバック。
     * DrawerType.displayName をそのまま使用する。
     */
    private fun buildDefaultDisplayName(type: DrawerType, isSorting: Boolean = false): Component {
        val name = if (isSorting) type.displayName.replace("ドロワー", "仕分けドロワー") else type.displayName
        return Component.text(name)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
    }

    /**
     * デフォルトのloreコンポーネントリストを構築する。
     *
     * Why: config.ymlに設定がない場合のフォールバック。
     * ハードコードされた基本情報を提供する。
     */
    private fun buildDefaultLore(type: DrawerType): List<Component> {
        val slotCount = type.getSlotCount()
        val capacityPerSlot = DrawerCapacityConfig.getCapacity(type)
        val totalCapacity = slotCount * capacityPerSlot

        return listOf(
            Component.empty(),
            LEGACY_SERIALIZER.deserialize("&7スロット数: &f$slotCount")
                .decoration(TextDecoration.ITALIC, false),
            LEGACY_SERIALIZER.deserialize("&7スロット容量: &f$capacityPerSlot スタック")
                .decoration(TextDecoration.ITALIC, false),
            LEGACY_SERIALIZER.deserialize("&7総容量: &f$totalCapacity スタック")
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            LEGACY_SERIALIZER.deserialize("&8設置してストレージドロワーを作成")
                .decoration(TextDecoration.ITALIC, false)
        )
    }
}
