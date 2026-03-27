package com.example.paperdrawers.infrastructure.cache

import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

/**
 * ドロワーの位置をインメモリで管理するレジストリ。
 *
 * Why: HopperPullTask が全チャンクを走査する代わりに、
 * 既知のドロワー位置のみを処理できるようにする。
 * これにより O(全チャンク) → O(ドロワー数) に計算量が削減される。
 *
 * 更新タイミング:
 * - 設置時: DrawerPlaceListener → register
 * - 破壊時: DrawerBreakListener → unregister
 * - 起動時: restoreAllDrawerDisplays → registerAll
 * - チャンクロード時: ChunkLoadListener → registerAll
 */
object DrawerLocationRegistry {

    /** 全ドロワー位置（HopperPullTask 用） */
    private val locations = ConcurrentHashMap<String, Location>()

    /** 仕分けドロワー位置のみ（SortingDrawerPullTask 用） */
    private val sortingLocations = ConcurrentHashMap<String, Location>()

    fun register(location: Location, isSorting: Boolean = false) {
        val key = toKey(location)
        locations[key] = location.clone()
        if (isSorting) {
            sortingLocations[key] = location.clone()
        }
    }

    fun unregister(location: Location) {
        val key = toKey(location)
        locations.remove(key)
        sortingLocations.remove(key)
    }

    /** 全ドロワー位置を返す */
    fun getAllLocations(): Collection<Location> = locations.values

    /** 仕分けドロワー位置のみ返す */
    fun getSortingLocations(): Collection<Location> = sortingLocations.values

    fun size(): Int = locations.size

    fun sortingSize(): Int = sortingLocations.size

    fun clear() {
        locations.clear()
        sortingLocations.clear()
    }

    private fun toKey(location: Location): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }
}
