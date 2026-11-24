package io.heckel.ntfy.ui

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

/** Make Android 15/16 edge-to-edge look like a conventional, non-overlapping UI.
 * The system bars stay visible, and app content is strictly inside the safe area.
 */
internal fun AppCompatActivity.keepContentBelowStatusBar() {
    val modernEdgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    // Android 15+ (target SDK 35+) ignores decorFits=true. Handle all insets ourselves
    // instead of mixing legacy system fitting with manual padding (which lost the top inset).
    WindowCompat.setDecorFitsSystemWindows(window, !modernEdgeToEdge)

    val darkMode = isDarkThemeOn(this)
    val barBackground = MaterialColors.getColor(
        this, R.attr.colorSurface, if (darkMode) Color.BLACK else Color.WHITE
    )
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    // 'Light status/navigation bar' means DARK icons, appropriate for light backgrounds.
    controller.isAppearanceLightStatusBars = !darkMode
    controller.isAppearanceLightNavigationBars = !darkMode

    // Android 15/16 draws transparent system bars. Color the underlying root instead.
    // Earlier Android versions can color the system bars directly.
    if (modernEdgeToEdge) {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    } else {
        @Suppress("DEPRECATION")
        window.statusBarColor = barBackground
        @Suppress("DEPRECATION")
        window.navigationBarColor = barBackground
    }

    val content = findViewById<ViewGroup>(android.R.id.content)
    val root = content.getChildAt(0) ?: return
    root.setBackgroundColor(barBackground)
    root.fitsSystemWindows = false
    findViewById<View>(R.id.app_bar_drawer)?.fitsSystemWindows = false

    if (modernEdgeToEdge) {
        val originalLeft = root.paddingLeft
        val originalTop = root.paddingTop
        val originalRight = root.paddingRight
        val originalBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = originalLeft + safe.left,
                top = originalTop + safe.top,
                right = originalRight + safe.right,
                bottom = originalBottom + maxOf(safe.bottom, keyboard.bottom)
            )
            // Keep dispatching the same insets to children for any other consumers.
            insets
        }
        // requestApplyInsets() before attachment may do nothing. Re-request on attach.
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}
