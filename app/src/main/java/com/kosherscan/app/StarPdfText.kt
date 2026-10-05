package com.kosherscan.app

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object StarPdfText {
    fun pages(bytes: ByteArray): List<String> = PDDocument.load(bytes).use { doc ->
        // Public certificates can use PDF owner encryption while opening with
        // no password. PDDocument.load still rejects a required user password.
        require(doc.numberOfPages in 1..30)
        (1..doc.numberOfPages).map { page ->
            PDFTextStripper().apply { sortByPosition = true; startPage = page; endPage = page }.getText(doc)
        }
    }
}
