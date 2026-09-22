package com.whocalltome.app.ui

enum class PermissionUiStatus {
    GRANTED,
    NOT_REQUESTED,
    DENIED,
    SETTINGS_REQUIRED,
}

internal fun permissionUiStatus(
    granted: Boolean,
    requestedBefore: Boolean,
    canShowRationale: Boolean,
): PermissionUiStatus = when {
    granted -> PermissionUiStatus.GRANTED
    !requestedBefore -> PermissionUiStatus.NOT_REQUESTED
    canShowRationale -> PermissionUiStatus.DENIED
    else -> PermissionUiStatus.SETTINGS_REQUIRED
}
