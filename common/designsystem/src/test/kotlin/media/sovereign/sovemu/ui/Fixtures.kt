package media.sovereign.sovemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The design system's composition locals for a test: the TV form, so the D-pad paths are the
 * ones under test. (`:common:feature`'s `TestApp` adds the API.)
 */
@Composable
fun TestApp(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalForm provides Form(wide = true), content = content)
}
