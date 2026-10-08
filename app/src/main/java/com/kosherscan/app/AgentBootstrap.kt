package com.kosherscan.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public routing configuration; never contains GitHub, Gemini or Telegram credentials. */
data class AgentSetup(val connection: AgentConnection?)
class AgentBootstrap {
    private val http = BoundedHttp(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())
    suspend fun fetch(): AgentSetup? = try {
        val (status, text) = http.request(Request.Builder().url(CONFIG_URL).header("Cache-Control", "no-cache").build())
        if (status == 200 && text.length <= 4096) parse(JSONObject(text)) else null
    } catch (_: java.io.IOException) { null } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
    companion object {
        const val CONFIG_URL = "https://raw.githubusercontent.com/az0512124155azz-sys/kosher-scan/main/agent/connection.json"
        /**
         * The app connection is public client configuration, not a private service
         * credential. Keep a build-time copy so a slow or blocked GitHub request can
         * never silently disable reviewed results on a user's device.
         */
        fun embedded(): AgentSetup = AgentSetup(
            AgentConnection(BuildConfig.AGENT_URL, BuildConfig.AGENT_APP_CODE).also { require(it.valid(false)) }
        )
        fun parse(json: JSONObject): AgentSetup {
            if (!json.getBoolean("enabled")) return AgentSetup(null)
            val connection = AgentConnection(json.getString("url"), json.getString("appCode"))
            require(connection.valid(false))
            return AgentSetup(connection)
        }
    }
}
