package com.kosherscan.app
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AgentBootstrapTest {
    @Test fun disabledRoutingDoesNotPretendTheServiceIsLive() {
        assertNull(AgentBootstrap.parse(JSONObject("""{"enabled":false}""")).connection)
    }
    @Test fun enabledRoutingAutomaticallyProvidesTheLimitedAppConnection() {
        val setup=AgentBootstrap.parse(JSONObject("""{"enabled":true,"url":"https://service.example/","appCode":"limited-app-code-0000000000"}"""))
        assertEquals("https://service.example/",setup.connection!!.url)
    }
    @Test fun insecureMalformedOrMissingRoutingCannotEnableUploads() {
        for(value in listOf("http://service.example/","https://user:password@service.example/","https://service.example/path")) {
            try { AgentBootstrap.parse(JSONObject().put("enabled",true).put("url",value).put("appCode","limited-app-code-0000000000"));fail("Invalid service enabled") }
            catch (_:IllegalArgumentException) { }
        }
    }
}
