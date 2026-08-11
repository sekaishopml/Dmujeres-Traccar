package com.dmujeres.gps.update

import org.json.JSONObject

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val minVersionCode: Int,
    val mandatory: Boolean
) {
    companion object {
        fun fromJson(json: String): UpdateInfo {
            val obj = JSONObject(json)
            return UpdateInfo(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                url = obj.getString("url"),
                minVersionCode = obj.getInt("minVersionCode"),
                mandatory = obj.optBoolean("mandatory", false)
            )
        }
    }
}
