package com.kosherscan.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live catalogue snapshots across six brands. These are records, not scanned barcode coverage. */
class OuCatalogueTest {
    private fun fixture(brand: String) = JSONObject(javaClass.getResource("/ou-catalogue/$brand.json")!!.readText().removePrefix("\uFEFF"))
    @Test fun officialCatalogueAnnotationsDoNotDiscardYearRoundCertification() {
        var count = 0; var annotations = 0
        val rows = mutableListOf<String>()
        for (brand in listOf("Oreo", "Heinz", "Kellogg", "Barilla", "Quaker", "Twinings")) {
            val json = fixture(brand); val records = json.getJSONArray("results")
            for (i in 0 until records.length()) {
                val row = OuRecords.parse(records.getJSONObject(i))
                val product = Product("12345678", row.name, row.brand)
                val verdict = KosherPolicy.resolve(product, listOf(row))
                assertEquals("$brand/${row.id}: ${row.officialStatus}", KosherStatus.KOSHER, verdict.status)
                assertFalse(ResultCopy.text(LookupResult(product, verdict)).contains("יש לבדוק"))
                // Baseline's status+conditions concatenation rejected these supplemental annotations.
                if (row.dairyEquipment || row.yoshon.isNotBlank()) annotations++
                rows += "$brand\t${row.id}\t${row.name}\t${row.dairyEquipment}\t${row.yoshon}\t${verdict.status}"
                count++
            }
        }
        assertEquals(459, count)
        assertTrue(annotations > 50)
        File("build/reports/ou-catalogue").mkdirs()
        File("build/reports/ou-catalogue/records.tsv").writeText(rows.joinToString("\n"))
        File("build/reports/ou-catalogue/summary.txt").writeText("Recorded OU rows: $count\nSupplemental DE/Yoshon rows: $annotations\nNo barcode or package coverage claim.\n")
    }

    private val base = OuRecord("1", "Chocolate Hazelnut Spread", "Brand", listOf("OU-D"), "Symbol required. Not Kosher for Passover.")
    private val product = Product("12345678", "Hazelnut Chocolate Spread 400g", "Brand")
    @Test fun wordOrderAndPackagingDoNotDiscardIdenticalIdentity() { assertTrue(KosherPolicy.strongMatch(product, base)) }
    @Test fun flavorSugarFreeAndVarietyAreNeverDiscarded() {
        for (variant in listOf("sugar free", "vanilla", "original", "variety pack"))
            assertFalse(KosherPolicy.strongMatch(product.copy(name = product.name + " " + variant), base))
    }
    @Test fun brandPunctuationAndCompositeFieldsHaveWholeTokenIdentity() {
        assertTrue(KosherPolicy.strongMatch(product.copy(brand = "Brand, Parent"), base.copy(brand = "Parent Brand")))
        assertFalse(KosherPolicy.strongMatch(product, base.copy(brand = "Brand Other")))
        assertTrue(KosherPolicy.strongMatch(product.copy(brand = "Kelloggs"), base.copy(brand = "Kellogg's")))
    }
    @Test fun supplementalAnnotationsRequireMatchingStructuredFields() {
        val status = base.conditions + " *Dairy Equipment"
        assertFalse(KosherPolicy.strongMatch(product, base.copy(officialStatus = status)))
        assertTrue(KosherPolicy.strongMatch(product, base.copy(officialStatus = status, dairyEquipment = true)))
        for (bad in listOf("Revoked", "Only lot 42", "Until January 2025", "Not certified"))
            assertFalse(KosherPolicy.strongMatch(product, base.copy(officialStatus = base.conditions + bad)))
        assertFalse(KosherPolicy.strongMatch(product, base.copy(officialStatus = base.conditions + " Revoked", yoshon = "Revoked")))
    }
    @Test fun ordinaryCertificationRestrictionsAreNotBypassedByAnnotations() {
        assertFalse(KosherPolicy.strongMatch(product, base.copy(conditions = base.conditions + " Only lot 42.",
            officialStatus = base.conditions + " Only lot 42. *Dairy Equipment", dairyEquipment = true)))
    }
    @Test fun cerealDescriptorNeedsActualProductCategory() {
        val row = base.copy(name = "Corn Flakes Cereal")
        val p = product.copy(name = "Corn Flakes", categories = listOf("en:breakfast-cereals"))
        assertTrue(KosherPolicy.strongMatch(p, row))
        assertFalse(KosherPolicy.strongMatch(p.copy(categories = emptyList()), row))
        assertFalse(KosherPolicy.strongMatch(p.copy(name = "Corn Flakes Honey"), row))
    }
}
