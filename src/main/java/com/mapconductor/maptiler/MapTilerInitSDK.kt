package com.mapconductor.maptiler

import com.maptiler.maptilersdk.MTConfig
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * MapTiler SDK のグローバル初期化を担うヘルパ。
 *
 * MapTiler は [MTConfig.apiKey] に API キーを設定してから地図を初期化する必要がある。
 * キーはアプリの `AndroidManifest.xml` の `<meta-data android:name="MAPTILER_API_KEY" .../>`
 * から読み取る（Secrets Gradle Plugin により `secrets.properties` の `MAPTILER_API_KEY` が注入される）。
 *
 * アプリ側で直接 [MTConfig.apiKey] を設定している場合はそちらを優先する。
 */
object MapTilerInitSDK {
    private const val META_DATA_KEY = "MAPTILER_API_KEY"
    private const val TAG = "MapTilerInitSDK"

    /**
     * API キーが未設定なら Manifest の meta-data から読み込んで設定する。
     *
     * @param context アプリ／アクティビティのコンテキスト。
     * @param apiKey 明示的に指定する API キー（省略時は Manifest から取得）。
     * @return キーが設定できた場合 true。
     */
    fun ensureInitialized(
        context: Context,
        apiKey: String? = null,
    ): Boolean {
        if (MTConfig.apiKey.isNotBlank()) return true

        val resolved = apiKey?.takeIf { it.isNotBlank() } ?: readApiKeyFromManifest(context)
        if (resolved.isNullOrBlank()) {
            Log.w(
                TAG,
                "MapTiler API key not found. Set MTConfig.apiKey directly or add a " +
                    "<meta-data android:name=\"$META_DATA_KEY\" .../> entry to your AndroidManifest.",
            )
            return false
        }

        MTConfig.apiKey = resolved
        return true
    }

    private fun readApiKeyFromManifest(context: Context): String? =
        runCatching {
            val appInfo =
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA,
                )
            appInfo.metaData?.getString(META_DATA_KEY)
        }.getOrNull()
}
