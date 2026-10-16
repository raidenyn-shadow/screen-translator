package com.raidenyn.screentranslator.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raidenyn.screentranslator.R

/**
 * Settings page for starting and stopping the current sharing session.
 */
@Composable
fun SetupScreen(sharing: Boolean, onStartSharing: () -> Unit, onStopSharing: () -> Unit) {
    val context = LocalContext.current

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                ServiceStatusChip(on = sharing)
                Button(
                    onClick = if (sharing) onStopSharing else onStartSharing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (sharing) R.string.setup_stop_sharing else R.string.setup_start_sharing))
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_battery_settings))
                }
                Text(
                    text = stringResource(R.string.setup_how_to_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.setup_how_to),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.setup_sharing_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Pill stating whether the capture service is currently bound. */
@Composable
private fun ServiceStatusChip(on: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (on) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Text(
            text = stringResource(
                if (on) R.string.setup_service_status_on else R.string.setup_service_status_off
            ),
            color = if (on) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
