package com.visor.archivos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.visor.archivos.BuildConfig
import com.visor.archivos.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REPO = "https://github.com/mosqueteroweb/visor"

/** Autoría, versión, enlaces y licencias; mismo formato que en el resto de apps de Mosqueteroweb. */
@Composable
fun AuthorshipDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var licenses by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    LaunchedEffect(Unit) {
        licenses = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty().sorted().map { file ->
                // En trozos: un texto de licencia entero en un solo bloque tarda en dibujarse
                file.removeSuffix(".txt").replace('_', ' ') to
                    context.assets.open("licenses/$file").bufferedReader().use { it.readText().chunked(3000) }
            }
        }
        loading = false
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).semantics { paneTitle = "Autoría" },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Autoría", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Image(painterResource(R.drawable.ic_mosqueteroweb), contentDescription = "Mosqueteroweb", modifier = Modifier.size(48.dp))
                        Text("Visor ${BuildConfig.VERSION_NAME} · Mosqueteroweb", style = MaterialTheme.typography.titleMedium)
                        Text("Aplicación de código libre · GNU GPLv3")
                        TextButton(onClick = { openLink(context, REPO) }) { Text("Código fuente y contacto") }
                        TextButton(onClick = { openLink(context, "$REPO/releases") }) { Text("Versiones publicadas") }
                        Text("Bibliotecas y licencias", style = MaterialTheme.typography.titleLarge)
                        Text("Los avisos y textos completos siguientes se incluyen en la aplicación y pueden consultarse sin conexión.")
                    }
                    if (loading) item { Text("Cargando licencias…") }
                    licenses.forEach { (title, sections) ->
                        item(key = title) {
                            TextButton(onClick = { expanded[title] = expanded[title] != true }, modifier = Modifier.fillMaxWidth()) {
                                Text((if (expanded[title] == true) "Ocultar " else "Ver ") + title)
                            }
                        }
                        if (expanded[title] == true) {
                            items(sections.size, key = { "$title-$it" }) { index ->
                                Text(sections[index], style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item(key = "$title-divider") { HorizontalDivider() }
                    }
                }
            }
        }
    }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay ningún navegador para abrir el enlace.", Toast.LENGTH_SHORT).show()
    }
}
