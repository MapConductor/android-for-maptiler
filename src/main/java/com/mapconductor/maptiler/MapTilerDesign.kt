package com.mapconductor.maptiler

import com.mapconductor.core.map.MapDesignTypeInterface
import com.maptiler.maptilersdk.map.style.MTMapReferenceStyle
import com.maptiler.maptilersdk.map.style.MTMapStyleVariant

/**
 * MapTiler の地図デザイン（リファレンススタイル + バリアント）を表すインターフェース。
 *
 * 他プロバイダの `*MapDesignTypeInterface` と同じく [MapDesignTypeInterface] を実装する。
 * [id] はデザインを一意に識別する文字列で、状態の保存・復元にも用いる。
 */
interface MapTilerMapDesignTypeInterface : MapDesignTypeInterface<String> {
    val referenceStyle: MTMapReferenceStyle
    val variant: MTMapStyleVariant?
}

/**
 * MapTiler Cloud のリファレンススタイルを指す地図デザイン。
 *
 * @property id デザイン識別子。
 * @property referenceStyle MapTiler のリファレンススタイル（STREETS / SATELLITE など）。
 * @property variant スタイルのバリアント（DARK / LIGHT など）。null で既定バリアント。
 */
data class MapTilerDesign(
    override val id: String,
    override val referenceStyle: MTMapReferenceStyle,
    override val variant: MTMapStyleVariant? = null,
) : MapTilerMapDesignTypeInterface {
    override fun getValue(): String = id

    companion object {
        val Streets = MapTilerDesign("Streets", MTMapReferenceStyle.STREETS)
        val StreetsDark = MapTilerDesign("StreetsDark", MTMapReferenceStyle.STREETS, MTMapStyleVariant.DARK)
        val StreetsLight = MapTilerDesign("StreetsLight", MTMapReferenceStyle.STREETS, MTMapStyleVariant.LIGHT)
        val Basic = MapTilerDesign("Basic", MTMapReferenceStyle.BASE)
        val Bright = MapTilerDesign("Bright", MTMapReferenceStyle.BRIGHT)
        val Satellite = MapTilerDesign("Satellite", MTMapReferenceStyle.SATELLITE)
        val Outdoor = MapTilerDesign("Outdoor", MTMapReferenceStyle.OUTDOOR)
        val Winter = MapTilerDesign("Winter", MTMapReferenceStyle.WINTER)
        val Topo = MapTilerDesign("Topo", MTMapReferenceStyle.TOPO)
        val Toner = MapTilerDesign("Toner", MTMapReferenceStyle.TONER)
        val Dataviz = MapTilerDesign("Dataviz", MTMapReferenceStyle.DATAVIZ)
        val Backdrop = MapTilerDesign("Backdrop", MTMapReferenceStyle.BACKDROP)
        val Ocean = MapTilerDesign("Ocean", MTMapReferenceStyle.OCEAN)
        val Landscape = MapTilerDesign("Landscape", MTMapReferenceStyle.LANDSCAPE)
        val Aquarelle = MapTilerDesign("Aquarelle", MTMapReferenceStyle.AQUARELLE)
        val OpenStreetMap = MapTilerDesign("OpenStreetMap", MTMapReferenceStyle.OPENSTREETMAP)

        private val all: List<MapTilerDesign> =
            listOf(
                Streets, StreetsDark, StreetsLight, Basic, Bright, Satellite, Outdoor, Winter,
                Topo, Toner, Dataviz, Backdrop, Ocean, Landscape, Aquarelle, OpenStreetMap,
            )

        /** 保存済みの [id] からデザインを復元する。未知の場合は [Streets] を返す。 */
        fun fromId(id: String?): MapTilerDesign = all.firstOrNull { it.id == id } ?: Streets
    }
}
