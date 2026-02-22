package de.adv990de.obd2.ui.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(navController: NavController, vm: DtcViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dtc_title)) }, scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            val currentError = state.error
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.weight(1f)
            ) {
                when {
                    currentError != null -> Text(
                        currentError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding() + 16.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                    )
                    state.dtcs.isEmpty() && !state.isLoading -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.dtc_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.dtcs, key = { it.code }) { dtc -> DtcRow(dtc) }
                    }
                }
            }
            HorizontalDivider()
            TextButton(
                onClick = { navController.navigate(Screen.FreezeFrame.route) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dtc_freeze_frame_link)) }
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
