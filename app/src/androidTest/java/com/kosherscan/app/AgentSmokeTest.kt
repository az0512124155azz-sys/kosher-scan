package com.kosherscan.app
import android.Manifest
import android.graphics.*
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.test.core.app.*
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AgentSmokeTest {
 @get:Rule val camera = GrantPermissionRule.grant(Manifest.permission.CAMERA)
 @Test fun croppedEvidenceIsAJpegAndExcludesTheRestOfTheFrame() {
  val bitmap=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED)
  Canvas(bitmap).drawRect(80f,70f,220f,140f,Paint().apply{color=Color.WHITE})
  val photo=BarcodePhoto.crop(bitmap,Rect(80,70,220,140))!!
  val decoded=BitmapFactory.decodeByteArray(photo,0,photo.size)
  assertEquals(140,decoded.width);assertEquals(70,decoded.height);assertTrue(Color.red(decoded.getPixel(1,1))>240)
  assertTrue(Color.green(decoded.getPixel(1,1))>240);assertNull(BarcodePhoto.crop(bitmap,Rect(400,400,500,500)))
  decoded.recycle();bitmap.recycle()
 }
 @Test fun onlyUnknownIsQueuedAndUploadCarriesRealPhotoAndMetadata()=runBlocking {
  MockWebServer().use {server->
   server.start();server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
   val context=ApplicationProvider.getApplicationContext<android.content.Context>()
   val outbox=AgentOutbox(context,AgentConnection(server.url("/").toString(),"test-app-connection-code-0000000000"))
   val p=Product("3017620422003","Nutella","Ferrero",imageUrl="https://images.openfoodfacts.org/product.jpg")
   assertNull(outbox.enqueue(p.barcode,"IL",LookupResult(p,Verdict(KosherStatus.KOSHER,"")),null))
   val b=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE)
   val photo=BarcodePhoto.crop(b,Rect(0,0,200,100))!!;b.recycle()
   val id=outbox.enqueue(p.barcode,"IL",LookupResult(p,Verdict(KosherStatus.UNKNOWN,"")),photo)!!
   val req=withContext(Dispatchers.IO){server.takeRequest(20,TimeUnit.SECONDS)}!!
   assertEquals("/api/cases",req.path)
   val json=JSONObject(req.body.readUtf8());assertEquals(p.barcode,json.getString("barcode"));assertEquals("Nutella",json.getJSONObject("product").getString("name"));assertTrue(json.getString("barcodePhoto").startsWith("/9j/"))
   withTimeout(10000){while(outbox.state(id)!=androidx.work.WorkInfo.State.SUCCEEDED)delay(100)}
   assertFalse(java.io.File(context.noBackupFilesDir,"agent-outbox/$id.json").exists())
  }
 }
 @Test fun reviewedReplyUpdatesTheNativeUnknownCard():Unit=runBlocking {
  MockWebServer().use {server->
   val reviewed=java.util.concurrent.atomic.AtomicBoolean(false)
   val expiry=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date(System.currentTimeMillis()+86400000L*30))
   server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
    if(request.path=="/api/cases")return MockResponse().setResponseCode(202).setBody("{}")
    return MockResponse().setBody(JSONObject().put("approved",reviewed.get()).put("phase",if(reviewed.get())"approved" else "review")
     .put("barcode","12345678").put("market","IL").put("reviewedAt",System.currentTimeMillis()).put("expiresAt",expiry)
     .put("status","kosher").put("details","פרווה.").toString())
   }}
   server.start()
   val context=ApplicationProvider.getApplicationContext<android.content.Context>()
   val connection=AgentConnection(server.url("/").toString(),"test-app-connection-code-0000000000")
   ActivityScenario.launch(MainActivity::class.java).use {scenario->
    lateinit var model:ScanModel
    scenario.onActivity{activity->
     model=androidx.lifecycle.ViewModelProvider(activity)[ScanModel::class.java]
     model.lookup("12345678",object:ProductLookup {override suspend fun lookup(code:String)=LookupResult(Product("12345678","מוצר בדיקה","מותג"),Verdict(KosherStatus.UNKNOWN,"")) },
      agentApi=AgentApi(connection),outbox=AgentOutbox(context,connection),market="IL")
    }
    withTimeout(10000){while(model.state.value.result==null)delay(100)}
    assertEquals(KosherStatus.UNKNOWN,model.state.value.result!!.verdict.status)
    reviewed.set(true)
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    // The production model polls automatically; no user action or agent button is needed.
    withTimeout(10000){while(model.state.value.result!!.verdict.status!=KosherStatus.KOSHER)delay(100)}
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    scenario.onActivity{activity->assertTrue(activity.findViewById<android.widget.TextView>(R.id.statusTitle).text.contains("✓"));assertEquals("מוצר בדיקה",model.state.value.result!!.product!!.name)}
   }
  }
  Unit
 }
}
