// 保护：只有会话、请求与操作摘要匹配时才结算一次；取消、超时和替换后旧按钮无效。
package com.niki914.zafiro.app.notification

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.app.util.SilentLoggerRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResidentNotificationManagerTest {
    @get:Rule val silentLogger = SilentLoggerRule()

    @Before
    @After
    fun cleanUp() {
        ResidentNotificationManager.resetForTest()
    }

    private fun toolRequest(command: String = "echo hello") =
        ApprovalRequest.ToolExecution("shell", command, "rule", "pattern")

    @Test
    fun matchingApprovalAllowsExactlyOnce() = runTest {
        val request = toolRequest()
        val result = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request) }
        testScheduler.runCurrent()
        val binding = requireNotNull(ResidentNotificationManager.bindingFor(request))

        assertTrue(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Allow))
        assertFalse(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Deny))
        assertEquals(ApprovalDecision.Allow, result.await())
        assertNull(ResidentNotificationManager.activeApprovalRequest)
    }

    @Test
    fun matchingDeclineDeniesTheOperation() = runTest {
        val request = toolRequest()
        val result = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request) }
        testScheduler.runCurrent()
        val binding = requireNotNull(ResidentNotificationManager.bindingFor(request))

        assertTrue(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Deny))
        assertEquals(ApprovalDecision.Deny, result.await())
    }

    @Test
    fun mismatchedSessionRequestOrDigestCannotSettleTheCurrentApproval() = runTest {
        val request = toolRequest()
        val result = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request) }
        testScheduler.runCurrent()
        val binding = requireNotNull(ResidentNotificationManager.bindingFor(request))
        val invalid = listOf(
            binding.copy(sessionId = "another-session"),
            binding.copy(requestId = "another-request"),
            binding.copy(operationDigest = ApprovalBinding.digest(toolRequest("different command"))),
        )

        assertTrue(invalid.all { !ResidentNotificationManager.resolveApproval(it, ApprovalDecision.Allow) })
        assertFalse(result.isCompleted)
        ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Deny)
        assertEquals(ApprovalDecision.Deny, result.await())
    }

    @Test
    fun cancellationRevokesBothDecisionsEvenWhenTheSameRequestIsAskedAgain() = runTest {
        val request = toolRequest()
        val first = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request) }
        testScheduler.runCurrent()
        val oldBinding = requireNotNull(ResidentNotificationManager.bindingFor(request))
        first.cancelAndJoin()
        val second = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request.copy()) }
        testScheduler.runCurrent()

        assertFalse(ResidentNotificationManager.resolveApproval(oldBinding, ApprovalDecision.Allow))
        assertFalse(ResidentNotificationManager.resolveApproval(oldBinding, ApprovalDecision.Deny))
        assertFalse(second.isCompleted)
        second.cancelAndJoin()
    }

    @Test
    fun timeoutRevokesThePendingApproval() = runTest {
        val request = toolRequest()
        val result = async {
            withTimeoutOrNull(1000) { ResidentNotificationManager.residentApprover.decide(request) }
        }
        testScheduler.runCurrent()
        val binding = requireNotNull(ResidentNotificationManager.bindingFor(request))
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()

        assertNull(result.await())
        assertFalse(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Allow))
        assertFalse(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Deny))
    }

    @Test
    fun aNewApprovalRevokesTheOldOneAndRemainsResolvable() = runTest {
        val firstRequest = toolRequest()
        val first = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(firstRequest) }
        testScheduler.runCurrent()
        val oldBinding = requireNotNull(ResidentNotificationManager.bindingFor(firstRequest))
        val nextRequest = toolRequest("echo next")
        val second = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(nextRequest) }
        testScheduler.runCurrent()
        val nextBinding = requireNotNull(ResidentNotificationManager.bindingFor(nextRequest))

        assertEquals(ApprovalDecision.Abstain, first.await())
        assertFalse(ResidentNotificationManager.resolveApproval(oldBinding, ApprovalDecision.Allow))
        ResidentNotificationManager.resolveApproval(nextBinding, ApprovalDecision.Allow)
        assertEquals(ApprovalDecision.Allow, second.await())
    }

    @Test
    fun screenConsentAlsoUsesBoundOneTimeDecisions() = runTest {
        val request = ApprovalRequest.ScreenControlConsent
        val result = backgroundScope.async { ResidentNotificationManager.residentApprover.decide(request) }
        testScheduler.runCurrent()
        val binding = requireNotNull(ResidentNotificationManager.bindingFor(request))

        assertTrue(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Allow))
        assertFalse(ResidentNotificationManager.resolveApproval(binding, ApprovalDecision.Allow))
        assertEquals(ApprovalDecision.Allow, result.await())
    }
}
