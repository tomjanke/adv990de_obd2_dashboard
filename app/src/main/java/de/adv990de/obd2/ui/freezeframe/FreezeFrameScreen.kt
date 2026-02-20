package de.adv990de.obd2.ui.freezeframe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
class FreezeFrameViewModel @Inject constructor(private val repo: ObdRepository) : ViewModel() {
    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())
    val data: StateFlow<Map<String, String>> = _data

    fun load() {
        viewModelScope.launch { _data.value = repo.readFreezeFrame() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeFrameScreen(navController: NavController, vm: FreezeFrameViewModel = hiltViewModel()) {
    val data by vm.data.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Freeze Frame") }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (data.isEmpty()) {
                Text("Keine Freeze Frame Daten verfügbar.")
            } else {
                data.forEach { (k, v) -> Text("$k: $v") }
            }
        }
    }
}
