package com.nyantv.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nyantv.ui.utils.NetworkStatusContent
import com.nyantv.ui.widgets.HomeSections
import com.nyantv.viewmodel.AppViewModel

@Composable
fun HomeScreen(vm: AppViewModel, navController: NavController, onDetailClick: (String) -> Unit) {
    val loggedIn     by vm.isLoggedIn.collectAsStateWithLifecycle()
    val profile      by vm.profile.collectAsStateWithLifecycle()
    val networkState by vm.networkState.collectAsStateWithLifecycle()
    val serviceType  by vm.serviceType.collectAsStateWithLifecycle()

    NetworkStatusContent(
        state       = networkState,
        serviceName = serviceType.name.lowercase().replaceFirstChar { it.uppercase() },
        onRetry     = { vm.retryLoad() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedHomeText(
                    text = "Hey ${if (loggedIn) profile?.name ?: "" else "Guest"}",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
                OutlinedHomeText(
                    text = "What are we watching today?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            HomeSections(vm = vm, navController = navController, onDetailClick)
        }
    }
}

@Composable
private fun OutlinedHomeText(text: String, style: TextStyle) {
    Box {
        Text(
            text = text,
            style = style.copy(drawStyle = Stroke(width = 4f, join = PathJoin.Round)),
            color = Color.Black
        )
        Text(text = text, style = style, color = Color.White)
    }
}
