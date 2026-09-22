package com.whocalltome.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.whocalltome.app.ui.theme.WhoCallToMeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationNumber = mutableStateOf<String?>(null)
    private var callLogPermissionGranted by mutableStateOf(false)
    private var permissionStatuses by mutableStateOf<Map<String, PermissionUiStatus>>(emptyMap())
    private var roleHeldState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationNumber.value = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        refreshPermissionStatuses()
        roleHeldState = isCallScreeningRoleHeld()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            WhoCallToMeTheme(themeMode) {
                val snackbar = remember { SnackbarHostState() }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    refreshPermissionStatuses()
                    roleHeldState = isCallScreeningRoleHeld()
                    if (result[Manifest.permission.READ_CALL_LOG] == true) {
                        viewModel.refreshSystemCallLog(showResult = true)
                    }
                }
                val roleLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { roleHeldState = isCallScreeningRoleHeld() }

                val createExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    val payload = pendingExport
                    if (uri != null && payload != null) {
                        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                    }
                    pendingExport = null
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        val json = contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                        if (json != null) viewModel.prepareImportData(json)
                    }
                }

                AppContent(
                    viewModel = viewModel,
                    snackbarHostState = snackbar,
                    initialNumber = notificationNumber.value,
                    roleHeld = roleHeldState,
                    onRequestRole = {
                        val manager = getSystemService(RoleManager::class.java)
                        if (manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                            roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                        }
                    },
                    onRequestPermission = { permission ->
                        markPermissionRequested(permission)
                        permissionsLauncher.launch(arrayOf(permission))
                    },
                    onOpenAppSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    },
                    onExport = {
                        viewModel.exportData { json ->
                            pendingExport = json
                            createExportLauncher.launch("WhoCallToMe-backup.json")
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    callLogPermissionGranted = callLogPermissionGranted,
                    permissionStatuses = permissionStatuses,
                    onRequestCallLogPermission = {
                        markPermissionRequested(Manifest.permission.READ_CALL_LOG)
                        permissionsLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationNumber.value = intent.getStringExtra(EXTRA_PHONE_NUMBER)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatuses()
        roleHeldState = isCallScreeningRoleHeld()
        viewModel.refreshSystemCallLog()
    }

    private fun refreshPermissionStatuses() {
        val requested = getSharedPreferences(PERMISSION_REQUESTS_PREFS, MODE_PRIVATE)
            .getStringSet(REQUESTED_PERMISSIONS_KEY, emptySet())
            .orEmpty()
        permissionStatuses = requiredRuntimePermissions().associateWith { permission ->
            permissionUiStatus(
                granted = hasPermission(permission),
                requestedBefore = permission in requested,
                canShowRationale = shouldShowRequestPermissionRationale(permission),
            )
        }
        callLogPermissionGranted = permissionStatuses[Manifest.permission.READ_CALL_LOG] == PermissionUiStatus.GRANTED
    }

    private fun markPermissionRequested(permission: String) {
        val prefs = getSharedPreferences(PERMISSION_REQUESTS_PREFS, MODE_PRIVATE)
        val requested = prefs.getStringSet(REQUESTED_PERMISSIONS_KEY, emptySet()).orEmpty().toSet()
        prefs.edit { putStringSet(REQUESTED_PERMISSIONS_KEY, requested + permission) }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isCallScreeningRoleHeld(): Boolean {
        val manager = getSystemService(RoleManager::class.java)
        return manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requiredRuntimePermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    companion object {
        const val EXTRA_PHONE_NUMBER = "phone_number"
        private const val PERMISSION_REQUESTS_PREFS = "permission_requests"
        private const val REQUESTED_PERMISSIONS_KEY = "requested"
        private var pendingExport: String? = null
    }
}
