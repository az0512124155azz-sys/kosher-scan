package com.kosherscan.app

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** Regional lists apply where the product is sold. Language, SIM home country
 * and barcode prefixes do not establish that market. */
object MarketScope {
    fun normalize(country: String?): String = country.orEmpty().trim().uppercase(Locale.ROOT)
        .takeIf { it in Locale.getISOCountries().toSet() }.orEmpty()

    fun current(context: Context): String = try {
        normalize(context.getSystemService(TelephonyManager::class.java)?.networkCountryIso)
    } catch (_: UnsupportedOperationException) { "" }
      catch (_: SecurityException) { "" }
}
