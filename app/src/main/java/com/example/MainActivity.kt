package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.AppDatabase
import com.example.data.model.FormTemplate
import com.example.data.repository.FormRepository
import com.example.ui.screens.FormSolveScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.TemplateDesignerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.isDarkFor
import com.example.ui.viewmodel.FormBuilderViewModel
import com.example.ui.viewmodel.FormBuilderViewModelFactory

class MainActivity : ComponentActivity() {

    // --- App lock (optional, Settings): the phone's own PIN / pattern / fingerprint ---
    private companion object {
        const val RELOCK_AFTER_MS = 60_000L
        // Process-wide so a rotation (activity recreation) does not ask again.
        var unlocked = false
        var lastStoppedAt = 0L
    }

    private var isLocked by mutableStateOf(false)

    private val unlockLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            unlocked = true
            isLocked = false
        }
        // Cancelled: stay on the locked screen; the user can try again from there.
    }

    private fun lockRequired(): Boolean {
        val enabled = getSharedPreferences("logimind_prefs", MODE_PRIVATE).getBoolean("app_lock_enabled", false)
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        return enabled && keyguard.isDeviceSecure
    }

    private fun requestUnlock() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock LogiMind", "Confirm it's you to open your clinical logbook")
        if (intent == null) {
            isLocked = false
        } else {
            unlockLauncher.launch(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!lockRequired()) {
            isLocked = false
            return
        }
        if (unlocked && System.currentTimeMillis() - lastStoppedAt > RELOCK_AFTER_MS && lastStoppedAt != 0L) {
            unlocked = false
        }
        if (!unlocked && !isLocked) {
            isLocked = true
            requestUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && unlocked) lastStoppedAt = System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Room Local Encryption-Ready Databases
        val database = AppDatabase.getDatabase(this)
        val repository = FormRepository(database)
        
        // 2. Instantiate state ViewModel via Factory (application context: the ViewModel outlives this Activity)
        val factory = FormBuilderViewModelFactory(repository, applicationContext)
        val viewModel = ViewModelProvider(this, factory)[FormBuilderViewModel::class.java]

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = isDarkFor(themeMode, isSystemInDarkTheme())
            // Status/navigation bar icons follow the app's theme, including a manual Light/Dark choice.
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            MyApplicationTheme(themeMode = themeMode) {
                // Navigation State: 0 = Home, 1 = Template Designer, 2 = Intake Solver
                var currentScreenIndex by rememberSaveable { mutableIntStateOf(0) }

                if (isLocked) {
                    LockedScreen(onUnlock = { requestUnlock() })
                    return@MyApplicationTheme
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreenIndex) {
                            0 -> {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToDesigner = { template ->
                                        viewModel.selectTemplateForEdit(template)
                                        currentScreenIndex = 1
                                    },
                                    onNavigateToSolve = { template ->
                                        viewModel.selectTemplateForEdit(template)
                                        currentScreenIndex = 2
                                    }
                                )
                            }
                            1 -> {
                                TemplateDesignerScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = {
                                        currentScreenIndex = 0
                                    }
                                )
                            }
                            2 -> {
                                FormSolveScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = {
                                        currentScreenIndex = 0
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_logo),
                contentDescription = "LogiMind logo",
                modifier = Modifier.padding(bottom = 16.dp).size(72.dp)
            )
            androidx.compose.material3.Text("LogiMind is locked", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) {
                androidx.compose.material3.Text("Unlock")
            }
        }
    }
}
