package com.relay.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE = "com.relay.auth"
private const val ACCOUNT = "session"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTokenStore : TokenStore {

    private val serviceRef: CFTypeRef? = CFBridgingRetain(NSString.create(string = SERVICE))
    private val accountRef: CFTypeRef? = CFBridgingRetain(NSString.create(string = ACCOUNT))

    override suspend fun save(tokens: StoredTokens) {
        deleteEntry()
        val payload = Json.encodeToString(StoredTokens.serializer(), tokens)
        val data = CFBridgingRetain(
            NSString.create(string = payload).dataUsingEncoding(NSUTF8StringEncoding)
        )
        withDictionary(
            baseQueryEntries() + listOf(
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock
            )
        ) { SecItemAdd(it, null) }
        data?.let { CFRelease(it) }
    }

    override suspend fun load(): StoredTokens? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withDictionary(
            baseQueryEntries() + listOf(
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne
            )
        ) { SecItemCopyMatching(it, result.ptr) }
        if (status != errSecSuccess) return@memScoped null
        val nsData = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        val text = NSString.create(nsData, NSUTF8StringEncoding)?.toString() ?: return@memScoped null
        runCatching { Json.decodeFromString(StoredTokens.serializer(), text) }.getOrNull()
    }

    override suspend fun clear() {
        deleteEntry()
    }

    private fun deleteEntry() {
        withDictionary(baseQueryEntries()) { SecItemDelete(it) }
    }

    private fun baseQueryEntries(): List<Pair<CFTypeRef?, CFTypeRef?>> = listOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to serviceRef,
        kSecAttrAccount to accountRef
    )

    private inline fun <T> withDictionary(
        entries: List<Pair<CFTypeRef?, CFTypeRef?>>,
        block: (CFDictionaryRef?) -> T
    ): T {
        val dictionary = CFDictionaryCreateMutable(
            null,
            entries.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
        entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        val outcome = block(dictionary)
        dictionary?.let { CFRelease(it) }
        return outcome
    }
}
