package com.niki914.zafiro.app.notification

import com.niki914.zafiro.api.model.ApprovalRequest
import java.security.MessageDigest
import java.util.UUID

internal data class ApprovalBinding(
    val sessionId: String,
    val requestId: String,
    val operationDigest: String,
) {
    companion object {
        fun create(sessionId: String, request: ApprovalRequest): ApprovalBinding = ApprovalBinding(
            sessionId = sessionId,
            requestId = UUID.randomUUID().toString(),
            operationDigest = digest(request),
        )

        fun digest(request: ApprovalRequest): String {
            val operation = when (request) {
                is ApprovalRequest.ToolExecution -> request.command
                ApprovalRequest.ScreenControlConsent -> "ScreenControlConsent"
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(operation.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
