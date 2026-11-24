package io.heckel.ntfy.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.heckel.ntfy.R

/** Keep the status bar visible and the page content below it.
 * On Android 15/16 with targetSdk 36 the OS enforces edge-to-edge, so the
 * legacy decorFitsSystemWindows flag alone does not prevent overlap.
 */
internal fun AppCompatActivity.keepContentBelowStatusBar() {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    val content = findViewById<ViewGroup>(android.R.id.content)
    val root = content.getChildAt(0) ?: return

    // Avoid implicit fitsSystemWindows and explicit insets being applied twice.
    root.fitsSystemWindows = false
    findViewById<View>(R.id.app_bar_drawer)?.fitsSystemWindows = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            view.updatePadding(top = initialTop + topInset)
            // Do not consume: existing FAB/list and keyboard inset listeners still need insets.
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
