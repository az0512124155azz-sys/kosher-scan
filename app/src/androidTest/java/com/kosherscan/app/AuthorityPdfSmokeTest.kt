package com.kosherscan.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AuthorityPdfSmokeTest {
    @Test fun actualOfficialCertificateDecodesOnAndroidAndChecksExpiry() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val pages = StarPdfText.pages(context.assets.open("star-certificate.pdf").use { it.readBytes() })
        assertEquals(6, pages.size)
        val now = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse("2026-10-05")!!.time
        val rows = AuthoritySources.parseStar(pages, "ZGCOQH37", now)
        assertTrue("Decoded rows=${rows.size}", rows.size > 50)
        assertTrue(rows.any { it.name == "Lemon Sorbet" && it.eligible })
        assertFalse(rows.first { it.name == "Black Raspberry Chip Pie" }.eligible)
        val result = AuthoritySources.resolve(Product("12345678", "Lemon Sorbet", "Graeter's Sorbet"), rows, "STAR-K")
        assertEquals(KosherStatus.KOSHER, result.verdict.status)
    }
}
