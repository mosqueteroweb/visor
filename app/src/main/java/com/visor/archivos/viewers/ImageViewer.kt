package com.visor.archivos.viewers

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage

@Composable
fun ImageViewer(uri: Uri, name: String, onViewAsHex: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(uri) { mutableStateOf(false) }

    val loader = remember {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
    }
    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .listener(onError = { _, _ -> failed = true })
            .build()
    }

    Box(modifier.fillMaxSize()) {
        if (failed) {
            CenteredMessage(
                title = "No se puede mostrar la imagen",
                detail = "El archivo está dañado o usa un formato que este dispositivo no decodifica.",
                action = { TextButton(onClick = onViewAsHex) { Text("Ver en hexadecimal") } },
            )
        } else {
            ZoomableAsyncImage(
                model = request,
                imageLoader = loader,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
