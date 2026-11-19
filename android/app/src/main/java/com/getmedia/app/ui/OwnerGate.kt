package com.getmedia.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.getmedia.app.data.local.MediaKind

/**
 * 完整版能力接缝。
 *
 * `main` 只依赖这个接口，具体实现在风味源码集里：
 * - `free`：只弹"暂未开放"，不 import 任何重型功能的类；
 * - `full`：打开真实的博主批量页面、服务器提取面板。
 *
 * 这样做是为了让免费版产物里**不存在**重型功能的代码（反编译可验证），
 * 而不是"有代码但把入口藏起来"。
 */
interface OwnerGate {
    /** 当前风味是否具备博主批量能力。 */
    val batchExtract: Boolean

    /** 当前风味是否具备"链接交给服务器提取"能力（P0-1）。免费版为 false，提取页不出现这个开关。 */
    val serverExtract: Boolean

    @Composable
    fun BatchExtract(onBack: () -> Unit)

    /**
     * 服务器提取面板：输入、四段进度、队列与失败卡片都在实现里。
     * 调用方只负责把它放进提取页的滚动容器，并复用自己的 snackbar。
     *
     * @param onKindChange 面板里切了音频/视频要报上来，提取页的标题得跟着当前模式的选择走。
     * @param onUseLocal 服务器侧原因失败时的出路：切回本机提取，并把这条链接带过去。
     * @param onGoLogin 未登录、或账号无权时的出路：切到"我的"页打开登录。
     */
    @Composable
    fun ServerExtract(
        snackbar: SnackbarHostState,
        onKindChange: (MediaKind) -> Unit,
        onUseLocal: (String) -> Unit,
        onGoLogin: () -> Unit,
    )
}
