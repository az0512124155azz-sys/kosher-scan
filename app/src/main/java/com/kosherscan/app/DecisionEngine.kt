package com.kosherscan.app

/**
 * One decision boundary for every direct adapter. Transport failures are not
 * negative evidence. Only conflicting evidence of the same strength is unknown.
 * Human review is a fallback and cannot replace a direct authority decision.
 */
object DecisionEngine {
    enum class Strength { CATEGORY, COMMUNITY_LABEL, REVIEW, AUTHORITY_NAME, AUTHORITY_BARCODE }
    data class Evidence(val result: LookupResult, val strength: Strength)

    fun evidence(result: LookupResult): Evidence = Evidence(result, when (result.verdict.sourceLabel) {
        "כושרות" -> Strength.AUTHORITY_BARCODE
        "OU", "OK", "STAR-K", "KLBD", "Rabbanut" -> Strength.AUTHORITY_NAME
        "Reviewed agent" -> Strength.REVIEW
        "Open Food Facts", "" -> Strength.COMMUNITY_LABEL
        else -> Strength.CATEGORY
    })

    fun resolve(product: Product?, results: List<LookupResult>): LookupResult {
        val applicable = results.filter { product == null || it.product == null ||
            IkrRepository.sameBarcode(product.barcode, it.product.barcode) }
        val known = applicable.filter { it.verdict.status != KosherStatus.UNKNOWN }.map(::evidence)
        if (known.isEmpty()) {
            val issues = applicable.mapNotNull { it.issue }
            val issue = listOf(LookupIssue.OFFLINE, LookupIssue.TIMEOUT, LookupIssue.NETWORK,
                LookupIssue.SERVICE_UNAVAILABLE, LookupIssue.INVALID_RESPONSE, LookupIssue.NOT_FOUND)
                .firstOrNull { it in issues }
            val unresolved = applicable.lastOrNull { it.verdict.reason.isNotBlank() }?.verdict
                ?: Verdict(KosherStatus.UNKNOWN, "No applicable certification evidence")
            return LookupResult(product, unresolved, issue)
        }
        val strongest = known.maxOf { it.strength }
        val decisive = known.filter { it.strength == strongest }
        if (decisive.map { it.result.verdict.status }.distinct().size > 1)
            return LookupResult(product, Verdict(KosherStatus.UNKNOWN, "Conflicting certification evidence"))
        val chosen = decisive.first().result
        val matchingDetails = decisive.map { it.result.verdict.displayText }.distinct()
        // Dairy/pareve/Passover disagreement is not a disagreement about ordinary
        // kosher status. Omit disputed secondary details, retain the shared status.
        return chosen.copy(product = product ?: chosen.product, issue = null,
            verdict = chosen.verdict.copy(displayText = matchingDetails.singleOrNull().orEmpty()))
    }
}
