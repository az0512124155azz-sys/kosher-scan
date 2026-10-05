package com.kosherscan.app
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentApiTest {
 private fun approved() = JSONObject("""{"barcode":"3017620422003","market":"IL","phase":"approved","approved":true,"status":"kosher","reviewedAt":100,"expiresAt":"2026-12-31","name":"Nutella","brand":"Ferrero","details":"חלבי."}""")
 private val date=java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-10-05")!!.time
 @Test fun exactCurrentReviewedRecordCanReturnKnownStatus() {assertEquals(KosherStatus.KOSHER,AgentApi.parseResult(approved(),"3017620422003","IL",date)!!.verdict.status)}
 @Test fun pendingAiSuggestionNeverBecomesKnown() { assertNull(AgentApi.parseResult(approved().put("phase","review"),"3017620422003","IL",date)); assertNull(AgentApi.parseResult(approved().put("approved",false),"3017620422003","IL",date)) }
 @Test fun differentBarcodeOrMarketNeverMatches() {assertNull(AgentApi.parseResult(approved(),"3017620422004","IL",date));assertNull(AgentApi.parseResult(approved(),"3017620422003","GB",date))}
 @Test fun expiredMalformedOrUnsupportedStatusFailsClosed() {
  for (value in listOf("2000-01-01","2226-12-31","2026-02-31","2026-1-1","")) assertNull(AgentApi.parseResult(approved().put("expiresAt",value),"3017620422003","IL",date))
  assertNull(AgentApi.parseResult(approved().put("status","probably"),"3017620422003","IL",date))
 }
 @Test fun productionConnectionRequiresHttpsAndNeverCredentialsInUrl() {
  assertFalse(AgentConnection("http://example.org/","a".repeat(40)).valid())
  assertFalse(AgentConnection("https://user:pass@example.org/","a".repeat(40)).valid())
  assertFalse(AgentConnection("https://example.org/?key=secret","a".repeat(40)).valid())
  assertTrue(AgentConnection("https://example.org/","a".repeat(40)).valid())
 }
 @Test fun knownOriginalVerdictIsPreservedAndConflictsStayUnknown() {
  val p=Product("3017620422003","Nutella","Ferrero")
  fun r(s:KosherStatus)=LookupResult(p,Verdict(s,""))
  assertEquals(KosherStatus.KOSHER,AgentAwareLookup.combine(r(KosherStatus.KOSHER),null).verdict.status)
  assertEquals(KosherStatus.KOSHER,AgentAwareLookup.combine(r(KosherStatus.UNKNOWN),r(KosherStatus.KOSHER)).verdict.status)
  assertEquals(KosherStatus.UNKNOWN,AgentAwareLookup.combine(r(KosherStatus.NOT_KOSHER),r(KosherStatus.KOSHER)).verdict.status)
 }
 @Test fun uploadSendsBarcodeProductAndOptionalPhotoWithoutGeminiKeys()=runBlocking {
  MockWebServer().use {s->s.start();s.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
   val api=AgentApi(AgentConnection(s.url("/").toString(),"local-token-000000000000000000000"))
   assertEquals(202,api.submit("""{"barcode":"3017620422003","market":"IL","barcodePhoto":"","product":{"name":"Nutella"}}"""))
   val req=s.takeRequest();assertEquals("/api/cases",req.path);assertEquals("Bearer local-token-000000000000000000000",req.getHeader("Authorization"));assertTrue(req.body.readUtf8().contains("Nutella"))
  }
 }
}
