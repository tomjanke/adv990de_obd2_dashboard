package de.adv990de.obd2.ui.vehicleinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.ui.components.BottomNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VehicleInfoViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _vin = MutableStateFlow("")
    val vin: StateFlow<String> = _vin

    fun load() {
        viewModelScope.launch { _vin.value = repo.readVin() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleInfoScreen(navController: NavController, vm: VehicleInfoViewModel = hiltViewModel()) {
    val vin by vm.vin.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Fahrzeuginfo") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("FIN (VIN)", style = MaterialTheme.typography.labelLarge)
            Text(vin.ifEmpty { "Wird gelesen…" })
        }
    }
}
