package com.getmedia.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.ui.screen.LibraryToolbar
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.ui.screen.LibraryToolbarTags
import com.getmedia.app.ui.screen.SortField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 媒体库顶部操作条的渲染回归：定时关闭的倒计时不能挤掉排序控件。
 *
 * 跑在 JVM（Robolectric）上，不依赖模拟器：320dp / 360dp / 600dp（折叠展开）/ 横屏、
 * 字体 100% 与 200% 都能参数化。渲染结果存到 build/reports/toolbar-shots 做前后对比。
 *
 * 修复前的实测（320dp、字体 200%、定时开启）：排序与导入的 bounds 都是 0，
 * 用户看到的就是「排序按钮没了」。所以断言不是"节点存在"就算过，
 * 而是要求完整宽度（≥80dp）与 48dp 触控高度。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryToolbarLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(timer: String?, fontScale: Float = 1f, width: Dp = 320.dp, dark: Boolean = false) {
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                    Box(Modifier.width(width).background(MaterialTheme.colorScheme.surface)) {
                        LibraryToolbar(
                            timer = timer,
                            sortField = SortField.MANUAL,
                            sortAsc = false,
                            importing = false,
                            kind = MediaKind.AUDIO,
                            onPickKind = {},
                            onToggleDirection = {},
                            onPickField = {},
                            onImport = {},
                            onCancelTimer = {},
                        )
                    }
                }
            }
        }
    }

    private fun saveShot(name: String) {
        // 截图只是证据；渲染失败不能让布局断言失去意义。
        runCatching {
            val dir = File("build/reports/toolbar-shots").apply { mkdirs() }
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    /** 排序必须"完整可用"：放得下图标 + 字段名 + 下拉箭头，触控高 ≥48dp。 */
    private fun assertSortFullyUsable() {
        rule.onNodeWithTag(LibraryToolbarTags.SORT).assertIsDisplayed()
        rule.onNodeWithTag(LibraryToolbarTags.SORT).assertWidthIsAtLeast(80.dp)
        rule.onNodeWithTag(LibraryToolbarTags.SORT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun timerOnAt320dpKeepsSortAndImportVisible() {
        render(timer = "14:59")
        saveShot("after-320-timer")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
        rule.onNodeWithTag(LibraryToolbarTags.TIMER).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun noTimerAt320dpStillFine() {
        render(timer = null)
        saveShot("after-320-notimer")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun timerOnWithFontScale200KeepsSortUsable() {
        render(timer = "14:59", fontScale = 2f)
        saveShot("after-320-font200")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun timerOnWithFontScale200At360dp() {
        render(timer = "14:59", fontScale = 2f, width = 360.dp)
        saveShot("after-360-font200")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    /** 深色模式：颜色全靠主题色，布局与浅色完全一致；这里确认没有"深色下看不见"的问题。 */
    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun timerOnInDarkTheme() {
        render(timer = "14:59", dark = true)
        saveShot("after-320-dark")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    /** 折叠展开的宽屏（600dp）。 */
    @Test
    @Config(sdk = [33], qualifiers = "w600dp-h800dp-xhdpi")
    fun timerOnAtExpanded600dp() {
        render(timer = "1:30:00", width = 600.dp)
        saveShot("after-600-timer")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    /** 横屏：宽 640dp、高 320dp。 */
    @Test
    @Config(sdk = [33], qualifiers = "w640dp-h320dp-xhdpi")
    fun timerOnInLandscape() {
        render(timer = "14:59", width = 640.dp)
        saveShot("after-640-land-timer")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
    }

    /**
     * 定时开启 → 倒计时跳秒 → 取消定时：排序控件的位置与宽度不能变。
     * 这是「不抖动」的量化断言（bounds 逐值比较），不是靠肉眼看。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun timerStateChangesDoNotShiftSort() {
        var timer by mutableStateOf<String?>("14:59")
        rule.setContent {
            MaterialTheme {
                LibraryToolbar(
                    timer = timer,
                    sortField = SortField.MANUAL,
                    sortAsc = false,
                    importing = false,
                    kind = MediaKind.AUDIO,
                    onPickKind = {},
                    onToggleDirection = {},
                    onPickField = {},
                    onImport = {},
                    onCancelTimer = {},
                )
            }
        }
        val running = rule.onNodeWithTag(LibraryToolbarTags.SORT).fetchSemanticsNode().boundsInRoot
        // 倒计时跳一秒：只有文字变，布局不该动。
        timer = "14:58"
        rule.waitForIdle()
        val ticked = rule.onNodeWithTag(LibraryToolbarTags.SORT).fetchSemanticsNode().boundsInRoot
        // 取消定时（定时条整行消失）。
        timer = null
        rule.waitForIdle()
        val cancelled = rule.onNodeWithTag(LibraryToolbarTags.SORT).fetchSemanticsNode().boundsInRoot

        assert(running.left == ticked.left && running.width == ticked.width) {
            "倒计时跳秒让排序控件移动了：$running -> $ticked"
        }
        assert(running.left == cancelled.left && running.width == cancelled.width) {
            "取消定时让排序控件移动了：$running -> $cancelled"
        }
        assertSortFullyUsable()
    }

    /**
     * 定时「播完后停止」状态：定时条上的文案是「定时：播完后停止」（比倒计时长得多），
     * 200% 字体下最容易被挤成省略号。这里同时验证两件事：
     * ①排序仍然完整可用；②排序在定时条下方，没有被压住（纵向不重叠）。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun waitingForTrackEndKeepsSortUsableAndNotCovered() {
        render(timer = "播完后停止", fontScale = 2f)
        saveShot("after-320-waiting-font200")
        assertSortFullyUsable()
        rule.onNodeWithTag(LibraryToolbarTags.IMPORT).assertIsDisplayed()
        val timerBounds = rule.onNodeWithTag(LibraryToolbarTags.TIMER).fetchSemanticsNode().boundsInRoot
        val sortBounds = rule.onNodeWithTag(LibraryToolbarTags.SORT).fetchSemanticsNode().boundsInRoot
        assert(sortBounds.top >= timerBounds.bottom - 0.5f) {
            "排序被定时条压住了：定时底 ${timerBounds.bottom}，排序顶 ${sortBounds.top}"
        }
    }

    /** 五个排序字段在菜单里都完整可见、触控高度 ≥48dp，并且点击能回调。 */
    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun everySortOptionIsVisibleAndClickable() {
        val picked = mutableListOf<SortField>()
        rule.setContent {
            MaterialTheme {
                LibraryToolbar(
                    timer = "14:59",
                    sortField = SortField.MANUAL,
                    sortAsc = false,
                    importing = false,
                    kind = MediaKind.AUDIO,
                    onPickKind = {},
                    onToggleDirection = {},
                    onPickField = { picked += it },
                    onImport = {},
                    onCancelTimer = {},
                )
            }
        }
        rule.onNodeWithTag(LibraryToolbarTags.SORT_MENU).performClick()
        for (f in SortField.entries) {
            rule.onNodeWithTag(LibraryToolbarTags.option(f)).assertIsDisplayed()
            rule.onNodeWithTag(LibraryToolbarTags.option(f)).assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(LibraryToolbarTags.option(SortField.NAME)).performClick()
        assert(picked.single() == SortField.NAME) { "点击「名称」应回调 NAME，实际 $picked" }
    }

    /**
     * 类型改成下拉：按钮上只有当前类型，点开才有音频/视频两项。
     * 选当前项不做任何事；选另一项要回调。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h640dp-xhdpi")
    fun kindDropdownListsBothAndPicks() {
        val picked = mutableListOf<MediaKind>()
        rule.setContent {
            MaterialTheme {
                LibraryToolbar(
                    timer = null,
                    sortField = SortField.MANUAL,
                    sortAsc = false,
                    importing = false,
                    kind = MediaKind.AUDIO,
                    onPickKind = { picked += it },
                    onToggleDirection = {},
                    onPickField = {},
                    onImport = {},
                    onCancelTimer = {},
                )
            }
        }
        // 默认不展开：两个选项都不在界面上（这正是省下来的宽度）。
        rule.onNodeWithTag(LibraryToolbarTags.kind(MediaKind.VIDEO)).assertDoesNotExist()
        rule.onNodeWithTag(LibraryToolbarTags.KIND).assertIsDisplayed()
        rule.onNodeWithTag(LibraryToolbarTags.KIND).performClick()
        rule.onNodeWithTag(LibraryToolbarTags.kind(MediaKind.AUDIO)).assertIsDisplayed()
        rule.onNodeWithTag(LibraryToolbarTags.kind(MediaKind.VIDEO)).assertIsDisplayed()
        // 点当前项：菜单关掉，不回调。
        rule.onNodeWithTag(LibraryToolbarTags.kind(MediaKind.AUDIO)).performClick()
        assert(picked.isEmpty()) { "点当前类型不该回调，实际 $picked" }
        // 再点开，选视频。
        rule.onNodeWithTag(LibraryToolbarTags.KIND).performClick()
        rule.onNodeWithTag(LibraryToolbarTags.kind(MediaKind.VIDEO)).performClick()
        assert(picked == listOf(MediaKind.VIDEO)) { "应只回调一次 VIDEO，实际 $picked" }
    }

    /**
     * 切音频/视频时「排序」和「导入」的横向位置必须一模一样（用户提的"同一个位置"），
     * 且都在同一行、「导入」贴最右；多出来的「显示画面」插在排序左边。
     *
     * 宽度取 **384dp**：手机 2（1080×2400、density 450）的可用宽度就是 384dp。
     * 早先按 393dp 断言是过的，装到 384dp 的真机上「不显示画面」换到了第二行——
     * 回归用例的宽度必须照真机来，否则等于没测。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w384dp-h854dp-xhdpi")
    fun sortAndImportKeepSamePlaceWhenSwitchingKind() {
        var kind by mutableStateOf(MediaKind.AUDIO)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(384.dp)) {
                    LibraryToolbar(
                        timer = null,
                        sortField = SortField.MANUAL,
                        sortAsc = false,
                        importing = false,
                        kind = kind,
                        onPickKind = { kind = it },
                        onToggleDirection = {},
                        onPickField = {},
                        onImport = {},
                        onCancelTimer = {},
                        videoDisplayEnabled = true,
                        onToggleVideoDisplay = {},
                    )
                }
            }
        }
        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

        val audioSort = bounds(LibraryToolbarTags.SORT)
        val audioImport = bounds(LibraryToolbarTags.IMPORT)
        rule.onNodeWithTag(LibraryToolbarTags.VIDEO_DISPLAY).assertDoesNotExist()

        saveShot("after-384-audio")
        kind = MediaKind.VIDEO
        rule.waitForIdle()
        saveShot("after-384-video")

        val videoSort = bounds(LibraryToolbarTags.SORT)
        val videoImport = bounds(LibraryToolbarTags.IMPORT)
        val videoDisplay = bounds(LibraryToolbarTags.VIDEO_DISPLAY)

        assert(audioSort.left == videoSort.left && audioSort.width == videoSort.width) {
            "切视频后排序按钮移位了：$audioSort -> $videoSort"
        }
        assert(audioImport.left == videoImport.left && audioImport.width == videoImport.width) {
            "切视频后导入按钮移位了：$audioImport -> $videoImport"
        }
        // 同一行：三个按钮的纵向中心线一致（换行就会差一整行高）。
        val sortCenter = (videoSort.top + videoSort.bottom) / 2
        val importCenter = (videoImport.top + videoImport.bottom) / 2
        val displayCenter = (videoDisplay.top + videoDisplay.bottom) / 2
        assert(kotlin.math.abs(sortCenter - importCenter) < 1f && kotlin.math.abs(sortCenter - displayCenter) < 1f) {
            "视频模式下换行了：排序 $videoSort，导入 $videoImport，显示画面 $videoDisplay"
        }
        // 顺序：类型 … 显示画面 · 排序 · 导入。多出来的按钮插在排序左边，不推走排序和导入。
        assert(videoDisplay.right <= videoSort.left + 0.5f) {
            "「显示画面」应在排序左侧：显示画面 $videoDisplay，排序 $videoSort"
        }
        // 导入贴最右：右边缘 = 384dp - 12dp 内边距（允许 1px 抗锯齿误差）。
        val density = rule.density.density
        val contentRight = 384.dp.value * density - 12.dp.value * density
        assert(kotlin.math.abs(videoImport.right - contentRight) <= 1f) {
            "「导入」应贴最右 $contentRight，实际 ${videoImport.right}"
        }
        assertSortFullyUsable()
    }
}
