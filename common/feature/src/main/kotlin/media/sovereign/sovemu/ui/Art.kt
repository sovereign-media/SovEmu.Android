package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.core.assetCacheKey

/** The API, for the composables that load from the server ([Art], [VideoSnap]). */
val LocalApi = staticCompositionLocalOf<SovEmuApi> { error("no API") }

/**
 * Artwork from the server (relative `/media/…` URL), filling its box; nothing while there is
 * none.
 */
@Composable
fun Art(
    path: String?,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    sizePx: Int? = null,
    /** [FilterQuality.None] for console pixels (a save's screenshot), scaled without smoothing. */
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    onLoaded: ((Boolean) -> Unit)? = null,
    onShape: ((Float) -> Unit)? = null,
) {
    val api = LocalApi.current
    val context = LocalContext.current
    val url = api.media(path) ?: return
    val request = remember(url, sizePx) {
        // Cached by the URL without its signature, which changes hourly: the same picture is
        // fetched once, not again with each new signature.
        val key = assetCacheKey(url) + (sizePx?.let { "#$it" } ?: "")
        ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(assetCacheKey(url))
            .apply { if (sizePx != null) size(sizePx) }.build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = scale,
        alpha = alpha,
        modifier = modifier,
        filterQuality = filterQuality,
        onState = if (
            onLoaded == null && onShape == null
        ) {
            null
        } else {
            { state: AsyncImagePainter.State ->
                onLoaded?.invoke(state is AsyncImagePainter.State.Success)
                // The picture's own shape (width over height), once it has loaded.
                if (state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f &&
                        size.height > 0f
                    ) {
                        onShape?.invoke(size.width / size.height)
                    }
                }
            }
        },
    )
}
