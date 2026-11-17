# 缺陷报告：定时关闭后，首页倒计时挤掉排序控件

**状态：已修复并提交（2026-11-17）；真机验收由用户执行**
**基线**：分支 `wp6-free`

---

## 1. 现象

用户在 GetAudio 里设置「定时关闭」后，首页出现倒计时；倒计时把排序控件（手动 / 时间 / 时长 / 文件 / 名称）挤掉，用户看不到也切不了排序。补充任务要求先判断 GetMedia 是否同样存在，并把 GetAudio、GetVideo 两处一起写进报告。

---

## 2. 复现

### 2.1 复现方法

媒体库首页挂不起来（依赖 Room、`PlayerHolder` 单例，JVM 测试里没有真库），所以用两种证据配合：

1. **代码级对照**：逐行比三处的工具栏代码，确认是否同一个结构（结论：同构）。
2. **渲染复现**：把旧版工具栏那段 `Row`（定时文本 + 取消按钮 + `Spacer(weight(1f))` + 排序 + 导入）**原样复制**成一个仅供截图的一次性 harness，用与生产相同的 Material3 主题、相同 Density（2f）、相同宽度约束渲染，跑在 JVM（Robolectric 4.12.2、SDK 33、qualifiers `w320dp-h640dp-xhdpi` 等）。该 harness 截完图即删除、未入库（旧布局代码不留在测试源里，也避免旧产品名进入禁用词扫描）；`before-*.png` 已归档在 `docs/reports/timer-bug-shots/`。

> 说明：这是"照抄旧布局"的复现，不是从旧 APK 抓的真机截图。旧代码无法脱离整页渲染，取这个折中办法，好处是宽度/字体/深色都能参数化，能反复跑。真机截图列入"未验证"。

### 2.2 复现结果（三处）

| 位置 | 修复前结构 | 320 dp / 100% | 320 dp / 200% | 360 dp / 100% + 云端同步 |
|---|---|---|---|---|
| **GetMedia**（本仓库） | 单行 Row，定时文本 + 取消按钮 + weight 空白 + 排序 + 导入 | 排序还在，**「导入」被裁掉** | **排序与导入全部消失** | 同 200%（排序塌陷） |
| **GetAudio** | 与上面逐行同构，且多一个「云端同步」`AssistChip`（配了服务器就显示） | **排序被压到只剩图标**（"手动"与下拉箭头都没了），导入消失 | 排序与导入全无 | —（本身已含该 chip） |
| **GetVideo** | 与 GetAudio 相同（同源分叉） | 同上 | 同上 | — |

代码位置：

- GetMedia 修复前：`LibraryScreenV2.kt` 里 `Row(...)` 到 `AssistChip("导入")` 那一段（现已被 `LibraryToolbar` 取代，见 §4）。
- GetAudio：`android/app/src/main/java/com/getaudio/app/ui/screen/LibraryScreenV2.kt:409-455`
- GetVideo：`android/app/src/main/java/com/getvideo/app/ui/screen/LibraryScreenV2.kt:409-455`

结论：**三处是同一个缺陷**。GetAudio / GetVideo 因为多一个 chip，触发阈值更低，这也是用户先在 GetAudio 撞上的原因；GetMedia 只是少一个 chip，窄屏或大字体下一样中招。

---

## 3. 根因

四条叠加，不是单一原因：

1. **单行 `Row` 不换行**。`Row` 按顺序测量子项，**不压缩也不换行**。定时文本与「取消定时播放」按钮排在最前，先占宽度；`Spacer(Modifier.weight(1f))` 只能分配"剩余"空间，剩余为 0 时尾部子项拿到的是 0 宽约束。
2. **尾部控件没有最小宽度**。`SortChip` 内部是 32 dp 高、无 `widthIn(min=)` 的 `Row`；被压到 0 后整块不可见（`assertIsDisplayed` 会直接失败，但用户只看到"按钮没了"）。
3. **文案过长**。「取消定时播放」6 个字，字体 200% 时单它就要约 190 dp；320 dp 减去左右 24 dp padding，只剩 296 dp——一个按钮就吃掉三分之二。
4. **触控高度不足**。排序按钮只有 32 dp，点击区等于视觉区，即便"看见"也不满足 48 dp 触控目标，属于第二重体验问题。

附带问题：倒计时每秒重组，`14:59 → 1:30:00` 位数变化会改变文本宽度，同一行尾部控件跟着左右跳（抖动）。

为什么表现成"消失"而不是"变窄"：父宽不够时，Compose 给后续子项的是剩余约束，表现要么被裁剪、要么宽度 0。上面截图里两种都出现了。

---

## 4. 修复方案（已实现并提交）

原则：**倒计时不占用、不覆盖排序区域；排序始终完整可见、可点。**

| # | 改动 | 文件 |
|---|---|---|
| 1 | 抽出 `LibraryToolbar`（定时状态 + 排序 + 导入），让工具栏能脱离整页做渲染回归 | `ui/screen/LibraryScreenV2.kt` |
| 2 | 定时状态改成**独立一行**（`FlowRow`，`heightIn(min = 48.dp)`）：计时器图标 + 「剩余 14:59」（播完再停时是「定时：播完后停止」）+「取消」按钮；按钮视觉写「取消」，读屏 `contentDescription = "取消定时关闭"` | 同上 |
| 3 | 排序 + 导入改成 `FlowRow`（右对齐、间距 8 dp）：宽度不够时「导入」换到下一行，**排序永远拿到它需要的宽度**（`FlowRow` 不压缩子项，装不下就换行） | 同上 |
| 4 | `SortChip` 外包 48 dp 高的 `Box`，内层 `matchParentSize()` 透明点击层：视觉仍是 32 dp（与旁边 chip 齐平），热区 48 dp | 同上 |
| 5 | 加 `LibraryToolbarTags`（`ROOT` / `TIMER` / `SORT` / `SORT_MENU` / `IMPORT` / `option(field)`），回归用例按 tag 定位，改文案不影响断言 | 同上 |
| 6 | 队列 sheet 头部的两个定时 pill 加 `horizontalScroll`（同类问题，一并处理）：大字体下放不下可以滑动，不会被裁 | `ui/screen/QueueBar.kt` |
| 7 | 排序下拉每个选项加 testTag，供"五个选项都可见可点"断言 | `ui/screen/LibraryScreenV2.kt` |

**为什么不是「做成顶栏角标」**：角标要么盖住标题，要么仍与右侧控件抢同一行，本质上还是在赌宽度。独立一行是小屏上唯一不赌宽度的方案；代价是定时开启时下方内容整体下移 48 dp（一次性回流，不遮挡任何控件）。

**为什么保留「取消」而不是「取消定时播放」**：200 dp 级别的宽度差，在 320 dp + 200% 下就是"字被省略"和"完整显示"的区别；完整语义交给读屏。

---

## 5. 前后对比

截图目录：`docs/reports/timer-bug-shots/`（JVM 渲染，2x density；`before-*` 来自 §2.1 的旧布局 harness，`after-*` 来自当前生产代码的 `LibraryToolbar`）。

| 组合 | 修复前 | 修复后 |
|---|---|---|
| 320 dp / 100% / 定时开 | ![before](reports/timer-bug-shots/before-320-timer.png) | ![after](reports/timer-bug-shots/after-320-timer.png) |
| 320 dp / 200% / 定时开 | ![before](reports/timer-bug-shots/before-320-font200.png) | ![after](reports/timer-bug-shots/after-320-font200.png) |
| 320 dp / 200% / 「播完后停止」 | —（旧布局同一行，只会更糟） | ![after](reports/timer-bug-shots/after-320-waiting-font200.png) |
| 320 dp / 100% / 无定时 | ![before](reports/timer-bug-shots/before-320-notimer.png) | ![after](reports/timer-bug-shots/after-320-notimer.png) |
| 360 dp / 100% / 定时 + 云端同步 | ![before](reports/timer-bug-shots/before-360-sync.png) | GetMedia 没有「云端同步」chip，该组合不适用；修复后 360 dp 100% 与 320 dp 100% 同形 |
| 360 dp / 200% / 定时开 | ![before](reports/timer-bug-shots/before-360-font200.png) | ![after](reports/timer-bug-shots/after-360-font200.png) |
| 600 dp / 折叠展开宽屏 / 定时开 | — | ![after](reports/timer-bug-shots/after-600-timer.png) |
| 640×320 dp / 横屏 / 定时开 | — | ![after](reports/timer-bug-shots/after-640-land-timer.png) |
| 320 dp / 深色 / 定时开 | — | ![after](reports/timer-bug-shots/after-320-dark.png) |

读图要点：

- 修复前 320 dp/200%：整个工具栏只剩定时文本与一个换行的「取消定时播放」按钮，排序与导入**都不存在**。
- 修复前 360 dp + 云端同步：排序只剩一个图标，文字和下拉箭头被裁——这就是用户说的"看不到排序按钮"。
- 修复后：定时单独一行，排序 + 导入在第二行右对齐，五个排序字段与导入按钮全部完整；200% 字体下也不省略。

---

## 6. 回归测试

文件：`android/app/src/test/java/com/getmedia/app/ui/LibraryToolbarLayoutTest.kt`（Robolectric + Compose UI test，跑在 JVM，不需要模拟器）。

| # | 用例 | 断言 |
|---|---|---|
| 1 | `timerOnAt320dpKeepsSortAndImportVisible` | 排序可见、宽 ≥80 dp、高 ≥48 dp；导入、定时条可见 |
| 2 | `noTimerAt320dpStillFine` | 无定时时同样完整 |
| 3 | `timerOnWithFontScale200KeepsSortUsable` | 字体 200% 下排序完整 |
| 4 | `timerOnWithFontScale200At360dp` | 360 dp + 200% |
| 5 | `timerOnInDarkTheme` | 深色主题布局一致 |
| 6 | `timerOnAtExpanded600dp` | 折叠展开宽屏，长文案「1:30:00」 |
| 7 | `timerOnInLandscape` | 640×320 横屏 |
| 8 | `timerStateChangesDoNotShiftSort` | 倒计时跳秒、取消定时后排序的 **left 与 width 不变** |
| 9 | `everySortOptionIsVisibleAndClickable` | 五个排序字段 `assertIsDisplayed` + 高 ≥48 dp + 点击回调命中 |
| 10 | `waitingForTrackEndKeepsSortUsableAndNotCovered` | 「定时：播完后停止」（最长文案）+ 320 dp + 200%：排序完整可用，且纵向不与定时条重叠（排序顶 ≥ 定时底） |

断言口径说明：

- 不只断言"节点存在"，而是要求**完整宽度 ≥80 dp**（图标 + 两个字 + 下拉箭头的实测下限）与 **≥48 dp 触控高度**——修复前那种宽度 0 的情况如果只断言存在，会蒙混过关。
- 状态切换只锁 `left/width`，不锁 `top`：取消定时后定时条整行消失，下方内容上移 48 dp 是预期回流，不是抖动。

本次运行结果（Mac，本轮实测）：

| 任务 | 用例数 | 失败 | 错误 |
|---|---|---|---|
| `testFreeDebugUnitTest` | 135（26 个类） | 0 | 0 |
| `testFullDebugUnitTest` | 155（28 个类） | 0 | 0 |

`LibraryToolbarLayoutTest` 在两个风味里各 10 项，全通过。同轮 `assembleFreeDebug` 构建成功（2 s，增量）。

---

## 7. 其他页面同类问题排查

| 位置 | 倒计时/提示条与顶部控件的关系 | 处置 |
|---|---|---|
| 媒体库首页工具栏 | 同一行抢占宽度（本缺陷） | 已修（独立行 + FlowRow） |
| 播放队列 sheet 头部两个 pill（查看定时 / 取消定时） | 同一行，大字体下会被裁 | 已加 `horizontalScroll` |
| 播放队列 sheet 副标题「定时停止：14:59」 | 纯文本副标题，单行 ellipsis，不占控件宽度 | 保留 |
| 定时对话框（`TimerDialog`）「当前定时：…」 | 对话框内一行说明文字，独立成行 | 无问题 |
| 底部播放条（`QueueBar` 88 dp 行） | 不显示倒计时 | 无问题 |
| 类别详情 / 播放列表 sheet | 不显示倒计时 | 无问题 |
| 视频播放面板（`VideoPlaybackPanel`） | 无倒计时 | 无问题 |
| 提取页、我的页、设置页 | 无倒计时条 | 无问题 |

---

## 8. 未验证与风险

1. **真机全部未做**：320 dp 小屏实机、折叠屏展开/折叠切换、系统字体 200% 叠加「显示大小」放大、横竖屏旋转中的状态保持。
2. **深色 + 壁纸**：用例只测纯色深色主题。壁纸是独立背景层，不参与该区域的测量，但"深色壁纸上倒计时与排序的可读性/对比度"需要真机看。
3. **TalkBack 朗读顺序、RTL** 未测（本次只补了取消按钮的 contentDescription）。
4. **「播完后停止」文案**已补用例（第 10 条，320 dp + 200% + 纵向不重叠断言）。
5. **GetAudio / GetVideo 未修**：两仓库只读。移植思路（供另一个 Agent 参考）：抽出工具栏 → 定时独立一行 → 排序与导入改 `FlowRow` → 排序热区 48 dp → 补同一套 10 个用例；注意它们多一个「云端同步」chip，应放进第二行 FlowRow，不要放回定时那一行。
6. 定时本身的行为（到点停止、播完再停）未改一行代码，不受本次改动影响。

---

## 9. 结论

- 缺陷确认存在，三处同源；GetMedia 的严重度低于 GetAudio（少一个 chip），但 200% 字体或 320 dp 下同样会把排序整体吃掉。
- 修复按"倒计时独立成行 + 排序不被压缩 + 48 dp 热区"落地，改动集中在媒体库工具栏与队列 sheet 头部，未触碰播放逻辑。
- 回归用 10 个用例锁住横向位置、宽度、触控高度、纵向不重叠与"五个排序选项都可见可点"，在 free / full 两个风味上通过。
- 已按用户 2026-11-17 的决定提交并推送；「播完后停止」用例已补（第 10 条）；真机验收由用户自行执行。
