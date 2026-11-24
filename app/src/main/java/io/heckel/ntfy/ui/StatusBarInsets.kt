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

/** One inset owner per screen. In particular, the one-topic screen does not
 * let the hidden app-bar scrolling behavior or parent padding shrink its body.
 */
@Suppress("DEPRECATION")
internal fun AppCompatActivity.keepContentBelowStatusBar() {
    val edgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    // On Android 15/16 target 35+ edge-to-edge is forced: opt out of implicit
    // decor insets and position content explicitly instead.
    WindowCompat.setDecorFitsSystemWindows(window, !edgeToEdge)

    val dark = isDarkThemeOn(this)
    val surface = MaterialColors.getColor(this, R.attr.colorSurface,
        if (dark) Color.BLACK else Color.WHITE)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = !dark
    controller.isAppearanceLightNavigationBars = !dark
    if (edgeToEdge) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    } else {
        window.statusBarColor = surface
        window.navigationBarColor = surface
    }

    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
    root.fitsSystemWindows = false
    findViewById<View>(R.id.app_bar_drawer)?.fitsSystemWindows = false

    if (this is DetailActivity) {
        val panel = findViewById<View>(R.id.detail_content_layout)
        val controls = findViewById<View>(R.id.single_fab_stack)
        // With no toolbar, the message panel must occupy all remaining height.
        // The status/nav insets are margins on the panel, never root padding.
        root.setBackgroundColor(surface)
        if (edgeToEdge) {
            val initialPanel = panel.layoutParams as ViewGroup.MarginLayoutParams
            val left0 = initialPanel.leftMargin
            val top0 = initialPanel.topMargin
            val right0 = initialPanel.rightMargin
            val bottom0 = initialPanel.bottomMargin
            val initialControls = controls.layoutParams as ViewGroup.MarginLayoutParams
            val controlsBottom0 = initialControls.bottomMargin
            val controlsEnd0 = initialControls.marginEnd
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bottom = maxOf(bars.bottom, ime.bottom)
                val lp = panel.layoutParams as ViewGroup.MarginLayoutParams
                val top = top0 + bars.top
                val bottomMargin = bottom0 + bottom
                if (lp.leftMargin != left0 + bars.left || lp.topMargin != top ||
                    lp.rightMargin != right0 + bars.right || lp.bottomMargin != bottomMargin) {
                    lp.setMargins(left0 + bars.left, top, right0 + bars.right, bottomMargin)
                    panel.layoutParams = lp
                }
                val fabLp = controls.layoutParams as ViewGroup.MarginLayoutParams
                val targetBottom = controlsBottom0 + bottom
                if (fabLp.bottomMargin != targetBottom ||
                    fabLp.marginEnd != controlsEnd0 + bars.right) {
                    fabLp.bottomMargin = targetBottom
                    fabLp.marginEnd = controlsEnd0 + bars.right
                    controls.layoutParams = fabLp
                }
                // Keep the whole remaining area the same color as the list.
                // The panel's theme-dependent background is set during onCreate.
                panel.background?.constantState?.newDrawable()?.mutate()?.let {
                    root.background = it
                }
                insets
            }
        } else {
            // Pre-Android 15: the framework already places content between bars.
            root.background = panel.background?.constantState?.newDrawable()?.mutate()
        }
    } else {
        root.setBackgroundColor(surface)
        if (edgeToEdge) {
            val left0 = root.paddingLeft
            val top0 = root.paddingTop
            val right0 = root.paddingRight
            val bottom0 = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.updatePadding(left = left0 + bars.left, top = top0 + bars.top,
                    right = right0 + bars.right,
                    bottom = bottom0 + maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
    }
    if (edgeToEdge) root.doOnAttach { ViewCompat.requestApplyInsets(it) }
}
