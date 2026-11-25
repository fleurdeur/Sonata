package com.sonata.feature.stems

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.SonataTheme

/**
 * On-demand feature module: on-device stem separation (vocals, drums, bass,
 * other) with solo/mute per stem, karaoke mode and acapella/instrumental export.
 * Downloaded and installed only when the user opens it.
 */
class StemsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonataTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Stem separation", style = MaterialTheme.typography.displayLarge)
                    Text(
                        "Runs fully on-device. Nothing leaves this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val stems = remember { mutableStateMapOf("Vocals" to true, "Drums" to true, "Bass" to true, "Other" to true) }
                    stems.forEach { (name, on) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name)
                            Switch(
                                checked = on,
                                onCheckedChange = { stems[name] = it },
                                modifier = Modifier.semantics { contentDescription = "$name stem" },
                            )
                        }
                    }
                    var karaoke by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Karaoke mode")
                        Switch(
                            checked = karaoke,
                            onCheckedChange = { karaoke = it },
                            modifier = Modifier.semantics { contentDescription = "Karaoke mode" },
                        )
                    }
                    Text(
                        if (karaoke) "Karaoke: vocals muted, instrumental playing"
                        else "Export as: Acapella or Instrumental",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
