package com.whocalltome.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whocalltome.app.R
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.model.CallerCategory

internal enum class CallIconKind(val icon: Int, val foreground: Int, val container: Int) {
    INCOMING(R.drawable.ic_call_received, R.color.call_incoming_foreground, R.color.call_incoming_container),
    OUTGOING(R.drawable.ic_call_made, R.color.call_outgoing_foreground, R.color.call_outgoing_container),
    MISSED(R.drawable.ic_call_missed, R.color.call_missed_foreground, R.color.call_missed_container),
    SPAM(R.drawable.ic_warning, R.color.call_spam_foreground, R.color.call_spam_container),
    BLOCKED(R.drawable.ic_block, R.color.call_blocked_foreground, R.color.call_blocked_container),
    REJECTED(R.drawable.ic_call_rejected, R.color.call_neutral_foreground, R.color.call_neutral_container),
    VOICEMAIL(R.drawable.ic_voicemail, R.color.call_outgoing_foreground, R.color.call_outgoing_container),
    ANSWERED_ELSEWHERE(R.drawable.ic_call_answered_elsewhere, R.color.call_incoming_foreground, R.color.call_incoming_container),
    UNKNOWN(R.drawable.ic_phone, R.color.call_neutral_foreground, R.color.call_neutral_container),
}

internal fun callIconKind(call: CallRecordEntity): CallIconKind = when {
    call.wasBlocked || call.direction == "BLOCKED" -> CallIconKind.BLOCKED
    call.category == CallerCategory.SPAM -> CallIconKind.SPAM
    call.direction == "INCOMING" -> CallIconKind.INCOMING
    call.direction == "OUTGOING" -> CallIconKind.OUTGOING
    call.direction == "MISSED" -> CallIconKind.MISSED
    call.direction == "REJECTED" -> CallIconKind.REJECTED
    call.direction == "VOICEMAIL" -> CallIconKind.VOICEMAIL
    call.direction == "ANSWERED_EXTERNALLY" -> CallIconKind.ANSWERED_ELSEWHERE
    else -> CallIconKind.UNKNOWN
}

@Composable
internal fun CallIcon(kind: CallIconKind) {
    val colors = MaterialTheme.colorScheme
    val (container, foreground) = when (kind) {
        CallIconKind.SPAM, CallIconKind.MISSED -> colors.errorContainer to colors.onErrorContainer
        CallIconKind.BLOCKED -> colors.tertiaryContainer to colors.onTertiaryContainer
        CallIconKind.INCOMING,
        CallIconKind.ANSWERED_ELSEWHERE,
        CallIconKind.OUTGOING,
        CallIconKind.VOICEMAIL,
        CallIconKind.REJECTED,
        CallIconKind.UNKNOWN -> colors.surfaceContainerLow to colors.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(kind.icon),
                // The row's visible status and warning already provide accessible labels.
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
