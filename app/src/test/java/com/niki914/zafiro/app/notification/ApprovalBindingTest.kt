// 保护：摘要覆盖完整 UTF-8 命令，同一命令的不同审批具有不同身份，防止旧决定复用。
package com.niki914.zafiro.app.notification

import com.niki914.zafiro.api.model.ApprovalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ApprovalBindingTest {
    private fun request(command: String) = ApprovalRequest.ToolExecution("shell", command, "rule", "pattern")

    @Test
    fun commandDigestUsesSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApprovalBinding.digest(request("abc")),
        )
    }

    @Test
    fun digestIncludesUnicodeWhitespaceAndTheEndOfLongCommands() {
        val command = "echo 中文\n" + "a".repeat(8192)
        val digest = ApprovalBinding.digest(request(command))
        assertNotEquals(digest, ApprovalBinding.digest(request(command + " ")))
        assertNotEquals(digest, ApprovalBinding.digest(request(command.replace("中文", "测试"))))
        assertNotEquals(digest, ApprovalBinding.digest(request(command.replace("\n", " "))))
    }

    @Test
    fun repeatedCommandsReceiveDifferentRequestIdentities() {
        val request = request("echo hello")
        val first = ApprovalBinding.create("session", request)
        val second = ApprovalBinding.create("session", request)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(first.operationDigest, second.operationDigest)
    }
}
