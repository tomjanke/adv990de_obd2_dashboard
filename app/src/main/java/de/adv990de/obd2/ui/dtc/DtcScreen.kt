package de.adv990de.obd2.ui.dtc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.components.BottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(navController: NavController, vm: DtcViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dtc_title)) }) },
        bottomBar = { BottomNav(navController) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val currentError = state.error
            when {
                currentError != null -> Text(
                    currentError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
                state.dtcs.isEmpty() && !state.isLoading -> Text(
                    stringResource(R.string.dtc_empty),
                    modifier = Modifier.padding(16.dp)
                )
                else -> LazyColumn {
                    items(state.dtcs, key = { it.code }) { dtc -> DtcRow(dtc) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcRow(dtc: DtcEntry) {
    ListItem(
        headlineContent = { Text(dtc.code) },
        supportingContent = { Text(dtc.description) },
        trailingContent = {
            if (dtc.isPending) Badge { Text(stringResource(R.string.dtc_badge_pending)) }
        }
    )
    HorizontalDivider()
}
