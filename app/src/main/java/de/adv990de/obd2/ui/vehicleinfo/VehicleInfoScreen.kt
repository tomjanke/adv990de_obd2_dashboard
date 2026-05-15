package de.adv990de.obd2.ui.vehicleinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.theme.asLiveValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleInfoScreen(navController: NavController, vm: VehicleInfoViewModel = hiltViewModel()) {
    val vin by vm.vin.collectAsState()
    val error by vm.error.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vehicle_info_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dashboard_config_back_desc))
                    }
                }
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.vehicle_info_label_vin), style = MaterialTheme.typography.labelLarge)
            val currentError = error
            when {
                currentError != null -> {
                    Text(currentError, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                }
                isLoading -> Text(stringResource(R.string.vehicle_info_loading))
                vin.isNotEmpty() -> LiveValueText(
                    value = vin,
                    style = MaterialTheme.typography.titleLarge.asLiveValue(),
                    color = MaterialTheme.colorScheme.onSurface,
                    animated = true,
                )
                else -> Text(stringResource(R.string.vehicle_info_empty))
            }
        }
    }
}
