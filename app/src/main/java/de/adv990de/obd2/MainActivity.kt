package de.adv990de.obd2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import de.adv990de.obd2.ui.navigation.AppNavHost
import de.adv990de.obd2.ui.permission.PermissionGate

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PermissionGate {
                    AppNavHost(rememberNavController())
                }
            }
        }
    }
}
