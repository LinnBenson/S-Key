package io.secure.keyboard.ui

import io.secure.keyboard.diagnostics.checkedMain
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.LinearLayout

@Suppress("DEPRECATION")
class IndentedKeyRowLifecycleTest : InstrumentationTestCase() {
    fun testRemovedKeysReleaseCacheAndRestoreInsetsBeforeReuse() {
        instrumentation.checkedMain {
            val context = instrumentation.targetContext
            val row = IndentedKeyRow(context)
            val key = KeyContainer(context).apply { setPadding(3, 4, 5, 6) }
            val background = android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)
            key.background = background
            row.addView(View(context), LinearLayout.LayoutParams(20, 40))
            row.addView(key, LinearLayout.LayoutParams(100, 40))
            row.addView(View(context), LinearLayout.LayoutParams(20, 40))
            val cache = IndentedKeyRow::class.java.getDeclaredField("cells").apply { isAccessible = true }
            repeat(20) {
                row.measure(View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
                row.layout(0, 0, 140, 40)
                assertEquals(1, (cache.get(row) as Map<*, *>).size)
                assertEquals(23, key.paddingLeft)
                row.removeView(key)
                assertTrue((cache.get(row) as Map<*, *>).isEmpty())
                assertSame(background, key.background)
                assertEquals(3, key.paddingLeft)
                assertEquals(5, key.paddingRight)
                row.addView(key, 1, LinearLayout.LayoutParams(100, 40))
            }
            row.removeAllViews()
            assertTrue((cache.get(row) as Map<*, *>).isEmpty())
        }
    }
}
