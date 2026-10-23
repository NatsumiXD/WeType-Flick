package dev.natsumi.wetype.markchanger

import android.view.View
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Hides artwork, leaving the original hit targets, listeners and layout untouched. */
internal class LogoAppearance {
    private val originalImageAlpha = WeakHashMap<ImageView, Int>()
    private val originalBadgeAlpha = WeakHashMap<View, Float>()
    private val originalDrawables = WeakHashMap<ImageView, Drawable?>()
    private val injectedDrawables = WeakHashMap<ImageView, WeakReference<Drawable>>()
    private val injectedBitmaps = WeakHashMap<ImageView, Bitmap>()

    fun apply(logo: ImageView, badge: View?, mode: Int, replacement: Bitmap? = null) {
        if (mode == SymbolConfig.LOGO_REPLACE && replacement != null) {
            val owned = injectedDrawables[logo]?.get()
            if (!originalDrawables.containsKey(logo) || logo.drawable !== owned) originalDrawables[logo] = logo.drawable
            if (injectedBitmaps[logo] !== replacement || logo.drawable !== owned) {
                logo.setImageBitmap(replacement)
                injectedDrawables[logo] = WeakReference(logo.drawable)
                injectedBitmaps[logo] = replacement
            }
        } else if (originalDrawables.containsKey(logo)) {
            // Do not overwrite an original drawable just refreshed by WeType (e.g. theme change).
            if (logo.drawable === injectedDrawables[logo]?.get()) logo.setImageDrawable(originalDrawables[logo])
            originalDrawables.remove(logo)
            injectedDrawables.remove(logo)
            injectedBitmaps.remove(logo)
        }
        if (mode == SymbolConfig.LOGO_HIDE) {
            if (!originalImageAlpha.containsKey(logo)) originalImageAlpha[logo] = logo.imageAlpha
            if (logo.imageAlpha != 0) logo.imageAlpha = 0
        } else {
            originalImageAlpha.remove(logo)?.let { logo.imageAlpha = it }
        }
        if (badge != null) {
            // The badge can draw its graphic as a background, so fade its drawing as a whole.
            // Alpha does not remove the view from layout or change its touch handling.
            if (mode != SymbolConfig.LOGO_SHOW) {
                if (!originalBadgeAlpha.containsKey(badge)) originalBadgeAlpha[badge] = badge.alpha
                if (badge.alpha != 0f) badge.alpha = 0f
            } else {
                originalBadgeAlpha.remove(badge)?.let { badge.alpha = it }
            }
        }
    }
}
