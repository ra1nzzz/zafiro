package com.niki914.zafiro.app.notification

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.runtime.service.AgentRuntimeService
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 常驻通知生命周期与授权决议管理器。
 *
 * 核心设计：
 * - 对应悬浮球 [FloatingBallOverlayManager]，作为常驻通知状态载体的控制中枢；
 * - 实现 [Approver] 并向 [AgentControl] 注册，与悬浮球、Compose 授权对话框并发协作；
 * - 驱动 [AgentRuntimeService] 的前台服务启停与实时状态/操作同步。
 */
object ResidentNotificationManager {

    private const val TAG = "niki914_zafiro_ResidentNotificationManager"

    private data class PendingApproval(
        val request: ApprovalRequest,
        val binding: ApprovalBinding,
        val cont: CancellableContinuation<ApprovalDecision>,
    )

    private val sessionId = UUID.randomUUID().toString()
    private val pendingApproval = AtomicReference<PendingApproval?>(null)
    val activeApprovalRequest: ApprovalRequest? get() = pendingApproval.get()?.request

    internal fun bindingFor(request: ApprovalRequest): ApprovalBinding? =
        pendingApproval.get()?.takeIf { it.request === request && it.cont.isActive }?.binding

    private var approverRegistered = false

    internal val residentApprover = object : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            return suspendCancellableCoroutine { cont ->
                val pending = PendingApproval(request, ApprovalBinding.create(sessionId, request), cont)
                val previous = pendingApproval.getAndSet(pending)
                previous?.cont?.resume(ApprovalDecision.Abstain)
                cont.invokeOnCancellation {
                    if (pendingApproval.compareAndSet(pending, null)) {
                        AgentRuntimeService.notifyUpdate()
                    }
                }
                AgentRuntimeService.notifyUpdate()
            }
        }
    }

    internal fun resolveApproval(binding: ApprovalBinding, decision: ApprovalDecision): Boolean {
        val pending = pendingApproval.get()
        if (pending == null || pending.binding != binding ||
            binding.operationDigest != ApprovalBinding.digest(pending.request) ||
            !pending.cont.isActive || !pendingApproval.compareAndSet(pending, null)
        ) {
            Logger.w(TAG, "Approval ignored: stale, mismatched or consumed requestId=${binding.requestId}")
            return false
        }
        pending.cont.resume(decision)
        AgentRuntimeService.notifyUpdate()
        return true
    }

    fun start(context: Context) {
        Logger.i(TAG, "Starting resident notification")
        if (!approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.addApprover(residentApprover)
            approverRegistered = true
        }

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_START_RESIDENT
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to startForegroundService for AgentRuntimeService", e)
        }
    }

    fun stop(context: Context) {
        Logger.i(TAG, "Stopping resident notification")
        if (approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        pendingApproval.getAndSet(null)?.cont?.cancel()

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_STOP_RESIDENT
        }
        try {
            context.startService(intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to send stop action to AgentRuntimeService", e)
        }
    }

    internal fun resetForTest() {
        val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
        if (approverRegistered) {
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        pendingApproval.getAndSet(null)?.cont?.cancel()
    }
}
