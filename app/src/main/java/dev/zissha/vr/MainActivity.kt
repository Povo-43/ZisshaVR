package dev.zissha.vr

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zissha.ui.theme.ZisshaVRTheme
import dev.zissha.vr.core.Engine
import dev.zissha.vr.ui.MainScreen
import dev.zissha.vr.ui.RecordingsScreen
import dev.zissha.vr.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val engine by lazy { Engine.get() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // start deferred subsystems (BLE peripheral / camera) once permissions are in
        if (granted.values.any { it } || granted.isEmpty()) {
            engine.onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()
        setContent {
            ZisshaVRTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    LaunchedEffect(Unit) {
                        engine.start(this@MainActivity, this@MainActivity)
                    }
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                engine = engine,
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenRecordings = { navController.navigate("recordings") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(engine = engine, onBack = { navController.popBackStack() })
                        }
                        composable("recordings") {
                            RecordingsScreen(engine = engine, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            engine.onPermissionsGranted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Engine threads stop with the activity to keep the pipeline lifecycle simple.
        if (isFinishing) {
            engine.stop()
        }
    }
}
