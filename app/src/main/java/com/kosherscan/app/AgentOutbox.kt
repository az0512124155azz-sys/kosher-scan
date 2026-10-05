package com.kosherscan.app

import android.content.Context
import android.util.Base64
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class AgentOutbox(private val context: Context, private val connection: AgentConnection) {
    suspend fun state(id: UUID): WorkInfo.State? = withContext(Dispatchers.IO) {
        try { WorkManager.getInstance(context).getWorkInfoById(id).get(2, TimeUnit.SECONDS)?.state }
        catch (_: java.util.concurrent.TimeoutException) { null }
        catch (_: java.util.concurrent.ExecutionException) { null }
    }
    suspend fun enqueue(code: String, market: String, result: LookupResult, photo: ByteArray?): UUID? = withContext(Dispatchers.IO) {
        if (result.verdict.status != KosherStatus.UNKNOWN || !connection.valid(BuildConfig.DEBUG)) return@withContext null
        val id = UUID.randomUUID()
        val directory = File(context.noBackupFilesDir, "agent-outbox").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 86400000 }?.forEach { it.delete() }
        val payload = JSONObject().put("requestId", id.toString()).put("barcode", code).put("market", market)
            .put("product", JSONObject().put("name", result.product?.name.orEmpty()).put("brand", result.product?.brand.orEmpty())
                .put("imageUrl", result.product?.imageUrl.orEmpty()))
            .put("barcodePhoto", photo?.takeIf { it.size <= 200000 }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())
        val file = File(directory, "$id.json")
        file.writeText(JSONObject().put("url", connection.url).put("token", connection.token).put("payload", payload.toString()).toString())
        val work = OneTimeWorkRequestBuilder<AgentUploadWorker>().setId(id)
            .setInputData(workDataOf("file" to file.name)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("agent-upload").build()
        WorkManager.getInstance(context).enqueueUniqueWork("agent-$id", ExistingWorkPolicy.KEEP, work)
        id
    }
}
class AgentUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString("file") ?: return@withContext Result.failure()
        if (!name.matches(Regex("[a-f0-9-]{36}\\.json"))) return@withContext Result.failure()
        val file = File(File(applicationContext.noBackupFilesDir, "agent-outbox"), name)
        if (!file.isFile) return@withContext Result.failure()
        try {
            val data = JSONObject(file.readText())
            val connection = AgentConnection(data.getString("url"), data.getString("token"))
            if (!connection.valid(BuildConfig.DEBUG)) { file.delete(); return@withContext Result.failure() }
            val status = AgentApi(connection).submit(data.getString("payload"))
            when {
                status == 202 -> { file.delete(); Result.success() }
                status in listOf(400, 401, 403, 413) || runAttemptCount >= 8 -> { file.delete(); Result.failure() }
                else -> Result.retry()
            }
        } catch (_: java.io.IOException) {
            if (runAttemptCount >= 8) { file.delete(); Result.failure() } else Result.retry()
        } catch (_: org.json.JSONException) { file.delete(); Result.failure() }
    }
}
