package com.niki914.zafiro.runtime.service

import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.RuleSettingsCodec

internal object CallerPolicy {
    enum class Caller { Internal, Host, Untrusted }

    enum class Capability {
        Submit, Cancel, ResetConversation, StoreBinder, StoreRead, StoreWrite, Notification,
    }

    fun identify(uid: Int, ownUid: Int, packages: Set<String>, hostPackages: Set<String>): Caller {
        if (uid == ownUid) return Caller.Internal
        if (uid == 2000) return Caller.Untrusted
        return if (packages.any { it in hostPackages }) Caller.Host else Caller.Untrusted
    }

    fun allows(caller: Caller, capability: Capability, storeId: String? = null): Boolean {
        return when (caller) {
            Caller.Internal -> true
            Caller.Untrusted -> false
            Caller.Host -> when (capability) {
                Capability.Submit, Capability.Cancel, Capability.ResetConversation,
                Capability.StoreBinder, Capability.Notification -> true
                Capability.StoreRead -> storeId == StoreDescriptorRegistry.RULES_TAKEOVER_ID ||
                        storeId == StoreDescriptorRegistry.WEB_SETTINGS_ID
                Capability.StoreWrite -> false
            }
        }
    }

    /** Only host-readable stores have a projection; takeover rules expose declared fields only. */
    fun projectHostStore(storeId: String, json: String): String? {
        return when (storeId) {
            StoreDescriptorRegistry.RULES_TAKEOVER_ID ->
                RuleSettingsCodec.encodeTakeoverSettings(RuleSettingsCodec.parseTakeoverSettings(json))
            StoreDescriptorRegistry.WEB_SETTINGS_ID -> json
            else -> null
        }
    }
}
