// 保护：外部 UID 无法借包名或通用 Store 访问凭据与执行策略；宿主接管所需能力保持可用。
package com.niki914.zafiro.runtime.service

import com.niki914.store.StoreDescriptorRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerPolicyTest {
    private val ownUid = 10101
    private val hosts = setOf("com.heytap.speechassist", "com.miui.voiceassist")

    @Test
    fun ownUidKeepsEveryCapabilityWithoutPackageLookup() {
        val caller = CallerPolicy.identify(ownUid, ownUid, emptySet(), hosts)
        assertTrue(CallerPolicy.Capability.entries.all { CallerPolicy.allows(caller, it) })
    }

    @Test
    fun anotherUidWithApplicationPackageNameIsUntrusted() {
        val caller = CallerPolicy.identify(10102, ownUid, setOf("com.niki914.zafiro"), hosts)
        assertTrue(CallerPolicy.Capability.entries.none { CallerPolicy.allows(caller, it) })
    }

    @Test
    fun shellUidIsUntrustedEvenWithAHostPackage() {
        assertEquals(CallerPolicy.Caller.Untrusted, CallerPolicy.identify(2000, ownUid, hosts, hosts))
    }

    @Test
    fun shizukuAndUnknownClientsAreUntrusted() {
        val packages = setOf("moe.shizuku.privileged.api", "test.shizuku.client")
        assertEquals(CallerPolicy.Caller.Untrusted, CallerPolicy.identify(10102, ownUid, packages, hosts))
    }

    @Test
    fun eachHostKeepsRuntimeAndNotificationCapabilities() {
        val capabilities = listOf(
            CallerPolicy.Capability.Submit, CallerPolicy.Capability.Cancel,
            CallerPolicy.Capability.ResetConversation, CallerPolicy.Capability.StoreBinder,
            CallerPolicy.Capability.Notification,
        )
        hosts.forEach { host ->
            val caller = CallerPolicy.identify(10102, ownUid, setOf(host), hosts)
            assertTrue(capabilities.all { CallerPolicy.allows(caller, it) })
        }
    }

    @Test
    fun hostsCanReadOnlyTheTakeoverAndWebSettingsStores() {
        val readable = StoreDescriptorRegistry.allStatic().filter {
            CallerPolicy.allows(CallerPolicy.Caller.Host, CallerPolicy.Capability.StoreRead, it.id)
        }.map { it.id }.toSet()
        assertEquals(
            setOf(StoreDescriptorRegistry.RULES_TAKEOVER_ID, StoreDescriptorRegistry.WEB_SETTINGS_ID),
            readable,
        )
        assertFalse(CallerPolicy.allows(
            CallerPolicy.Caller.Host, CallerPolicy.Capability.StoreRead, "agent.config.main",
        ))
        assertFalse(CallerPolicy.allows(
            CallerPolicy.Caller.Host, CallerPolicy.Capability.StoreRead, "rules.takeover.extra",
        ))
    }

    @Test
    fun hostsCannotWriteOrMutateAnyStoreIncludingTakeoverRules() {
        assertTrue(StoreDescriptorRegistry.allStatic().none {
            CallerPolicy.allows(CallerPolicy.Caller.Host, CallerPolicy.Capability.StoreWrite, it.id)
        })
    }

    @Test
    fun anUntrustedRecipientOfAStoreBinderStillHasNoStoreAccess() {
        assertFalse(CallerPolicy.allows(
            CallerPolicy.Caller.Untrusted, CallerPolicy.Capability.StoreRead,
            StoreDescriptorRegistry.RULES_TAKEOVER_ID,
        ))
        assertFalse(CallerPolicy.allows(
            CallerPolicy.Caller.Untrusted, CallerPolicy.Capability.StoreWrite,
            StoreDescriptorRegistry.RULES_EXECUTION_ID,
        ))
    }

    @Test
    fun takeoverProjectionRemovesUnknownRootAndNestedFields() {
        val projected = CallerPolicy.projectHostStore(
            StoreDescriptorRegistry.RULES_TAKEOVER_ID,
            """{"default_target":"ZAFIRO","api_key":"test-value","rules":[
                {"id":"voice","name":"Voice","target":"ZAFIRO","enabled":true,
                 "patterns":["hello"],"api_key":"test-value","execution_rules":[]}
            ]}""",
        )
        val root = Json.parseToJsonElement(requireNotNull(projected)).jsonObject
        assertEquals(setOf("default_target", "rules"), root.keys)
        assertEquals(
            setOf("id", "name", "target", "enabled", "patterns"),
            root.getValue("rules").jsonArray.single().jsonObject.keys,
        )
        assertTrue(projected.contains("hello"))
    }

    @Test
    fun webSettingsProjectionReturnsOnlyTheSuppliedDocument() {
        val json = """{"hooks":{"voice":{"enabled":true}}}"""
        assertEquals(json, CallerPolicy.projectHostStore(StoreDescriptorRegistry.WEB_SETTINGS_ID, json))
    }

    @Test
    fun storesOutsideTheHostWhitelistHaveNoProjection() {
        val restrictedIds = StoreDescriptorRegistry.allStatic().map { it.id }
            .filterNot {
                it == StoreDescriptorRegistry.RULES_TAKEOVER_ID ||
                        it == StoreDescriptorRegistry.WEB_SETTINGS_ID
            } + "agent.config.main"
        restrictedIds.forEach { id ->
            assertNull(CallerPolicy.projectHostStore(id, """{"api_key":"test-value"}"""))
        }
    }
}
