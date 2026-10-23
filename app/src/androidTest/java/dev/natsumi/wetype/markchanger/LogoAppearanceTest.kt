package dev.natsumi.wetype.markchanger

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogoAppearanceTest {
    @Test fun replacementImmediatelyRestoresOriginalWhenDisabledOrCleared() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val logo = ImageView(instrumentation.targetContext)
            val original = ColorDrawable(0xff334455.toInt())
            logo.setImageDrawable(original)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val appearance = LogoAppearance()
            appearance.apply(logo, null, SymbolConfig.LOGO_REPLACE, bitmap)
            assertNotSame(original, logo.drawable)
            appearance.apply(logo, null, SymbolConfig.LOGO_HIDE)
            assertSame(original, logo.drawable)
            assertEquals(0, logo.imageAlpha)
            appearance.apply(logo, null, SymbolConfig.LOGO_SHOW)
            assertEquals(255, logo.imageAlpha)
            appearance.apply(logo, null, SymbolConfig.LOGO_REPLACE, bitmap)
            appearance.apply(logo, null, SymbolConfig.LOGO_REPLACE, null)
            assertSame(original, logo.drawable)
        }
    }

    @Test fun hiddenArtworkPreservesImageButtonAndParentButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val parent = FrameLayout(instrumentation.targetContext)
            val logo = ImageView(instrumentation.targetContext)
            val bounds = FrameLayout.LayoutParams(80, 80)
            parent.addView(logo, bounds)
            var parentClicks = 0
            var imageClicks = 0
            parent.setOnClickListener { parentClicks++ }
            logo.setOnClickListener { imageClicks++ }
            LogoAppearance().apply(logo, null, SymbolConfig.LOGO_HIDE)
            assertEquals(View.VISIBLE, parent.visibility)
            assertEquals(View.VISIBLE, logo.visibility)
            assertSame(bounds, logo.layoutParams)
            assertTrue(parent.isEnabled && parent.isClickable)
            assertTrue(logo.isEnabled && logo.isClickable)
            assertTrue(parent.performClick())
            assertTrue(logo.performClick())
            assertEquals(1, parentClicks)
            assertEquals(1, imageClicks)
            assertEquals(0, logo.imageAlpha)
        }
    }

    @Test fun repeatedHideAndModeChangesRestoreOriginalOpacity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val logo = ImageView(instrumentation.targetContext).apply { imageAlpha = 173 }
            val badge = ImageView(instrumentation.targetContext).apply { alpha = 0.6f }
            val appearance = LogoAppearance()
            repeat(3) { appearance.apply(logo, badge, SymbolConfig.LOGO_HIDE) }
            appearance.apply(logo, badge, SymbolConfig.LOGO_REPLACE)
            assertEquals(173, logo.imageAlpha)
            assertEquals(0f, badge.alpha, 0f)
            appearance.apply(logo, badge, SymbolConfig.LOGO_SHOW)
            assertEquals(173, logo.imageAlpha)
            assertEquals(0.6f, badge.alpha, 0f)
            assertEquals(View.VISIBLE, badge.visibility)
        }
    }
}
