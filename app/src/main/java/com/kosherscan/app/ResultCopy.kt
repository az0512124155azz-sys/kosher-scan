package com.kosherscan.app

/** Source names and matching diagnostics are not user-facing explanations. */
object ResultCopy {
    fun text(result: LookupResult): String {
        val verdict = result.verdict
        // An optional image/metadata failure does not replace a known verdict or its conditions.
        if (verdict.status != KosherStatus.UNKNOWN || result.issue == null) {
            return verdict.displayText.ifBlank {
                when (verdict.status) {
                    KosherStatus.KOSHER -> "המוצר מסומן ככשר."
                    KosherStatus.NOT_KOSHER -> "המוצר מסומן כלא כשר."
                    KosherStatus.UNKNOWN -> "לא נמצא מידע מספיק כדי לקבוע אם המוצר כשר."
                }
            }
        }
        return when (result.issue) {
            LookupIssue.NOT_FOUND -> "המוצר לא נמצא. אין מידע על הכשרות שלו."
            LookupIssue.OFFLINE -> "אין חיבור לאינטרנט. התחברו ונסו שוב."
            LookupIssue.NETWORK -> "לא ניתן להשלים את הבדיקה עקב תקלה בתקשורת. נסו שוב."
            LookupIssue.TIMEOUT -> "הבדיקה לא הושלמה בזמן. אפשר לסרוק שוב."
            LookupIssue.SERVICE_UNAVAILABLE -> "שירות הבדיקה אינו זמין כרגע. נסו שוב מאוחר יותר."
            LookupIssue.INVALID_RESPONSE -> "לא התקבלה תשובה תקינה. נסו שוב מאוחר יותר."
        }
    }
}
