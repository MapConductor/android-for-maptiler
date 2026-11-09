package com.mapconductor.maptiler

import com.mapconductor.core.InternalMapConductorApi
import com.maptiler.maptilersdk.bridge.MTBridge
import com.maptiler.maptilersdk.bridge.MTBridgeReturnType
import com.maptiler.maptilersdk.bridge.MTCommand
import com.maptiler.maptilersdk.bridge.MTCommandExecutable
import com.maptiler.maptilersdk.map.style.MTMapReferenceStyle
import com.maptiler.maptilersdk.map.style.MTStyle
import com.maptiler.maptilersdk.map.style.MTStyleError
import com.maptiler.maptilersdk.map.style.source.MTGeoJSONSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 外したソースを同じ id で足し直せること（ポリゴン・円・ラスター等が「一度外すと戻らない」不具合）。
 *
 * 実物の [MTStyle] に、ページ（WebView）の代わりにコマンドを記録するだけの実行器をつないで確かめる。
 */
@OptIn(InternalMapConductorApi::class)
class MapTilerStyleRemovalTest {
    private val commands = mutableListOf<String>()
    private lateinit var style: MTStyle

    @Before
    fun setUp() {
        val executor =
            object : MTCommandExecutable {
                override suspend fun execute(command: MTCommand): MTBridgeReturnType {
                    commands += command.javaClass.simpleName
                    return MTBridgeReturnType.BoolValue(true)
                }
            }
        style = MTStyle(MTMapReferenceStyle.STREETS)
        // initWorker は SDK の internal。地図の ready で SDK 自身が呼ぶものを、ここでは反射で呼ぶ。
        MTStyle::class.java
            .declaredMethods
            .single { it.name.startsWith("initWorker") }
            .apply { isAccessible = true }
            .invoke(style, MTBridge(executor), CoroutineScope(Dispatchers.Unconfined))
    }

    private fun source() = MTGeoJSONSource.fromJsonString("polygon-source-PG", """{"type":"FeatureCollection","features":[]}""")

    /** 不具合の原因: id で外しても MTStyle の記録が残り、同じ id の add が投げる。 */
    @Test
    fun removeSourceById_leavesTheIdRecorded() {
        style.addSource(source())
        style.removeSourceById("polygon-source-PG")
        assertThrows(MTStyleError.SourceAlreadyExists::class.java) { style.addSource(source()) }
    }

    @Test
    fun removeSourceAndForget_letsTheSameIdBeAddedAgain() {
        val first = source()
        style.addSource(first)
        style.removeSourceAndForget(first)
        style.addSource(source())
        assertEquals(listOf("AddSource", "RemoveSource", "AddSource"), commands.filter { it.contains("Source") })
    }

    /** 記録に無いもの（足していない・記録が遅れている）は id でページから消す。 */
    @Test
    fun removeSourceAndForget_fallsBackToTheIdWhenNotRecorded() {
        style.removeSourceAndForget(source())
        assertTrue(commands.toString(), commands.contains("RemoveSourceById"))
    }
}
