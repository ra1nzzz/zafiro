package com.niki914.zafiro.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.service.requireService

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decision = when (intent.action) {
            ResidentNotificationBuilder.ACTION_STOP -> {
                Logger.i(TAG, "Resident notification: Stop clicked")
                requireService<AgentControl>().stop()
                return
            }
            ResidentNotificationBuilder.ACTION_APPROVE -> ApprovalDecision.Allow
            ResidentNotificationBuilder.ACTION_DECLINE -> ApprovalDecision.Deny
            else -> {
                Logger.w(TAG, "Unknown approval action ignored")
                return
            }
        }
        val data = intent.data
        val segments = data?.pathSegments.orEmpty()
        if (data == null || data.scheme != "zafiro-approval" || data.authority != "action" ||
            segments.size != 3 || segments[2] != intent.action
        ) {
            Logger.w(TAG, "Malformed approval action ignored")
            return
        }
        val digest = data.getQueryParameter("digest")
        if (digest.isNullOrBlank()) {
            Logger.w(TAG, "Approval action without operation digest ignored")
            return
        }
        ResidentNotificationManager.resolveApproval(
            ApprovalBinding(segments[0], segments[1], digest), decision,
        )
    }

    private companion object {
        const val TAG = "niki914_zafiro_ApprovalActionReceiver"
    }
}
