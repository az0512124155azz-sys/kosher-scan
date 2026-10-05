package com.kosherscan.app

import org.json.JSONObject

object OuRecords {
    fun parse(r: JSONObject): OuRecord {
        val symbols = r.optJSONArray("symbol")
        return OuRecord(r.optString("agencyUniqueId"), r.optString("productName"), r.optString("brandName"),
            (0 until (symbols?.length() ?: 0)).map { symbols!!.getString(it) },
            r.optString("conditions").ifBlank { r.optString("status") },
            r.optString("status"), r.optBoolean("isDairyEquipment"), r.optString("yoshon"))
    }
}
