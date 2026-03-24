package com.example.paperdrawers.infrastructure.config

import com.example.paperdrawers.domain.model.DrawerType
import org.bukkit.configuration.ConfigurationSection

/**
 * ドロワーのティアごとの容量設定を管理するオブジェクト。
 *
 * Why: config.ymlで容量をカスタマイズ可能にすることで、
 * サーバー管理者がゲームバランスを調整できる。
 * 設定がない場合はDrawerType enumのデフォルト値を使用する。
 */
object DrawerCapacityConfig {

    /** ティアごとの容量オーバーライド（スタック単位） */
    private val capacityOverrides = mutableMapOf<Int, Int>()

    /**
     * config.ymlのdrawer-capacityセクションから容量設定を読み込む。
     *
     * @param configSection drawer-capacityセクション（nullの場合はデフォルト値を使用）
     */
    fun initialize(configSection: ConfigurationSection?) {
        capacityOverrides.clear()
        if (configSection == null) return

        for (tier in 1..7) {
            val key = "tier-$tier"
            if (configSection.contains(key)) {
                val value = configSection.getInt(key)
                if (value > 0) {
                    capacityOverrides[tier] = value
                }
            }
        }
    }

    /**
     * 指定されたドロワータイプの容量（スタック単位）を取得する。
     *
     * Why: config.ymlで設定されていればそちらを優先し、
     * なければDrawerType enumのデフォルト値を返す。
     *
     * @param type ドロワータイプ
     * @return 容量（スタック単位）
     */
    fun getCapacity(type: DrawerType): Int {
        if (type == DrawerType.VOID) return 1  // Void drawer has minimal capacity (items are consumed)
        return capacityOverrides[type.tier] ?: type.getBaseCapacity()
    }
}
