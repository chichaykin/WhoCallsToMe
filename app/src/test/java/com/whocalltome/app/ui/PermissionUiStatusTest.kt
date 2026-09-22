package com.whocalltome.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionUiStatusTest {
    @Test
    fun grantedTakesPriorityOverPreviousRequestState() {
        assertEquals(
            PermissionUiStatus.GRANTED,
            permissionUiStatus(granted = true, requestedBefore = true, canShowRationale = false),
        )
    }

    @Test
    fun firstRequestAndDeniedRequestHaveDifferentActions() {
        assertEquals(
            PermissionUiStatus.NOT_REQUESTED,
            permissionUiStatus(granted = false, requestedBefore = false, canShowRationale = false),
        )
        assertEquals(
            PermissionUiStatus.DENIED,
            permissionUiStatus(granted = false, requestedBefore = true, canShowRationale = true),
        )
        assertEquals(
            PermissionUiStatus.SETTINGS_REQUIRED,
            permissionUiStatus(granted = false, requestedBefore = true, canShowRationale = false),
        )
    }
}
