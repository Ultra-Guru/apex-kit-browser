# apex-kit-browser

Embedded WebView automation engine with Compose chrome UI — 从
[Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 拆出的浏览器能力库。

[![CI](https://github.com/Ultra-Guru/apex-kit-browser/actions/workflows/ci.yml/badge.svg)](https://github.com/Ultra-Guru/apex-kit-browser/actions/workflows/ci.yml)
![Consumer](https://img.shields.io/badge/consumer-Android--Guru--Agent-blue)

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:browser-core` | 纯 Kotlin JVM（零 Android 依赖） | DOM 模型（`DomElement`/`PageSnapshot`）、注入 JS（`BrowserScript`）、快照解析（`DomParser`）、重试/熔断（`RetryPolicy`/`CircuitBreaker`） |
| `:browser-engine` | Android Library（无 UI） | 无头 WebView 自动化引擎（`BrowserEngine`）：状态机、标签管理、物理触摸注入、加载等待、Cookie 持久化、崩溃恢复 |
| `:browser-chrome` | Android Library（Compose UI + 纯 View 实时窗） | 「给人用的浏览器界面」：地址胶囊即进度条、标签条/总览、页内查找、下载面板、JS 弹窗/权限浮层、全屏接管浮窗（`BrowserOverlay`）、透明实时页面悬浮窗（`LiveViewOverlay`，v1.4.0）、App 内嵌浏览器宿主（`InAppChromeHost` + 承载仲裁，v1.5.0） |

依赖方向：`chrome → engine → core`（`api` 传递，下游引 chrome 即得全套类型）。

## 安全：JS 注入边界（`JsLiteral`）

浏览器 Agent 天生暴露在**间接提示注入**下：网页正文可能被模型当作「元素 ref」原样回传。
因此本库把「外部字符串 → JS 字面量」的编码收敛到唯一入口
**`JsLiteral.string`**（`browser-core/.../core/JsLiteral.kt`），覆盖 `'` `\`、
换行/回车/制表等控制字符，以及 U+2028 / U+2029（JSON 合法但 JS 字面量非法）。

> **v1.0.1 修复（已随 rebase 并入 1.1.0 发布列车）**：v1.0.0 的注入点是 `private fun String.toJsonString() = "'$this'"`，
> 零转义。实测 `ref = "+alert(document.cookie)+"` 可生成
> `document.querySelector('[data-apex-hash='+alert(document.cookie)+']')`
> —— **由 ref 字符串驱动的页内任意 JS 执行**。现所有注入点统一走 `JsLiteral.string`。

两条必须遵守的约定：

1. **新增注入点时必须用 `JsLiteral.string`**，不得自行拼接或手写转义
   （v1.0.0 曾同时存在三套互不一致的转义，其中一套把 `ref` 按 `"` 转义却嵌在
   单引号选择器里）。
2. **ref 定位不经 CSS 解析**：`[data-apex-hash=<ref>]` 会让 CSS 解析器二次解析
   ref，含引号 / 空格 / `]` 的 ref 会抛 `SyntaxError`，并一路冒泡成
   `ElementNotFoundException` + 打开熔断器。故统一走
   「`querySelectorAll('[data-apex-hash]')` + JS `===` 比较属性值」。

## 纯逻辑下沉：页面类型分类（`PageClassifier`）

`pageType` 的信号采集（`BrowserScript.PAGE_TYPE_JS`）与分类逻辑（`PageClassifier`）
已分离。分类策略此前是 `BrowserEngine` 里的 `private` 方法、与 WebView 耦合、
**零测试覆盖**；现下沉到零 Android 依赖的 `:browser-core`，优先级表
（auth > video > form > article > search > list > portal > generic）
由 `PageClassifierTest` 穷举锁定，并对外开放给任何消费方。

> **v1.0.1 移除** `BrowserScript.waitForSelectorJs`：它返回 `Promise`，而
> `evaluateJavascript` 不等待 Promise，回调恒为 `null`，导致
> `browser_navigate(wait_for=…)` 的 `selectorFound` **永远为 false**。
> 已由同步的 `BrowserScript.selectorPresentJs` + Kotlin 侧轮询取代。

## 快照契约（v1.0.1）

| 约定 | 说明 |
|---|---|
| **ref 绝不编造** | `ref` 是 click / input / select 唯一的定位主键，全部经 `data-apex-hash` 反查。缺失时 `ref` 留空并在摘要里标注「不可操作」，而不是回退成 `r1`/`r2` 这类永远解析不到的伪 ref（旧行为会白耗 3 次重试并**打开熔断器**）。 |
| **截断如实上报** | 注入脚本按 `SNAPSHOT_MAX_ELEMENTS` 硬上限截断，现回传 `{v,total,truncated,elements}` 信封；摘要会写明「页面实际匹配 N 个，已达上限」。不告知的话模型会把这一批当成全部。`PageSnapshot.truncated` / `totalCandidateCount` 同步可供程序判断。`DomParser.parse` 仍兼容旧的裸数组形态。 |
| **摘要优先级** | 超 token 预算时按「可交互 > 浅层 > 有标签」决定谁进预算（依赖脚本计算的真实 `depth`，上限 20），但**输出顺序**仍按 `bid` 保持页面自然阅读顺序。 |
| **坐标系统一** | 两条快照路径（`snapshotJs` 与 A11y 降级源）均返回**文档**坐标（加 scroll 偏移），避免物理触摸兜底在 A11y 路径上指向错误位置。 |

## 兼容矩阵（F3：Compose Compiler 版本必须 ≤ 宿主）

| 依赖 | 版本 |
|---|---|
| Kotlin | 2.2.21 |
| AGP | 8.13.2 |
| Gradle | 8.14 |
| Compose BOM | 2025.11.01 |
| Kotlin Coroutines | 1.10.2 |
| kotlinx.serialization | 1.11.0 |
| compileSdk / minSdk | 35 / 26 |

> Requires **Kotlin 2.2.21 + Compose compiler plugin of the same version**（composite build 侧 AGP 必须同为 8.13.2）。

## 消费方式（按阶段递进）

### E1 · 迁移期：Composite Build

宿主 `settings.gradle.kts`：

```kotlin
// 兄弟目录存在时接入（本地开发 / CI 双仓检出）；不存在则走远端坐标
if (file("../apex-kit-browser").isDirectory) {
    includeBuild("../apex-kit-browser")
}
```

宿主 `build.gradle.kts` 依赖照常写远端坐标，Gradle 自动用本地 composite build 替换：

```kotlin
dependencies {
    implementation("com.apex.browser:browser-core:1.9.0")
    implementation("com.apex.browser:browser-engine:1.9.0")
    implementation("com.apex.browser:browser-chrome:1.9.0")
}
```

### E2 · 过渡期：publishToMavenLocal

```bash
./gradlew publishToMavenLocal   # 库侧
# 宿主 repositories 含 mavenLocal() 即可按版本消费
```

### E3 · 稳定期：GitHub Packages

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Ultra-Guru/apex-kit-browser")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```

## DI 接法（本库零 DI 依赖，F1）

库内**不用 Hilt**（避免强绑下游宿主），单例由工厂 object 提供，宿主在自己的
Module 里 provide：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BrowserKitModule {
    @Provides @Singleton
    fun provideBrowserEngine(@ApplicationContext ctx: Context): BrowserEngine =
        BrowserEngineFactory.get(ctx)

    @Provides @Singleton
    fun provideApexChromeWiring(@ApplicationContext ctx: Context, engine: BrowserEngine): ApexChromeWiring =
        ApexChromeWiringFactory.get(ctx, engine)

    @Provides @Singleton
    fun provideBrowserOverlay(
        @ApplicationContext ctx: Context,
        engine: BrowserEngine,
        wiring: ApexChromeWiring,
    ): BrowserOverlay = BrowserOverlayFactory.get(ctx, engine, wiring)
}
```

宿主自己的视觉层（悬浮球等）经 [BrowserVisualHook] 注入，库不感知其存在：

```kotlin
class HostVisualHook(private val neonBall: CyberNeonBallManager) : BrowserVisualHook {
    override fun onAgentDrivingStarted() = neonBall.show()
    override fun onWaitingHuman() = neonBall.pulse()
    override fun onSessionHidden() = neonBall.hide()
}
```

## CI 防线（充分利用 GitHub Actions）

### `ci.yml` — 编译与下游兼容（需要 Android SDK）

1. **build-test**：三模块编译 + 单测（`:browser-core:test` 独立跑，证明纯 JVM）+
   `publishToMavenLocal` 可发布性冒烟（坐标/POM 完整性）。
2. **consumer-check**：每次 push/PR 把宿主 [Android-Guru-Agent] 检出为兄弟目录，
   经 composite build 替换后跑宿主 `:app:compileDebugKotlin` + 单测 ——
   **库一改动坏下游，立刻在本仓库 CI 看到**（F6 跨仓库漂移的解法）。

### `guard-rails.yml` — 结构门禁（**秒级，无需 Android SDK**）

编译能过 ≠ 该合。以下都是「便宜地写下来、贵得重新发现」的不变量：

| Job | 拦什么 |
|---|---|
| Library Invariants | `:browser-core` 混进 `android.*` 依赖；依赖箭头反向（core → engine）；注入点绕过 `JsLiteral.string`；零转义 helper 复活；注入脚本返回 `Promise`；**WebView 沙箱被放松**（见下） |
| Resource Integrity | 资源漏 `browser_` 前缀（AGP 只告警，撞的是**宿主** `R`）；`values` 与 `values-*` 键集/占位符不对齐 |
| Structural Quality | God-file 体积预算；`printStackTrace()`；反射派发；括号失衡（词法感知） |
| Supply Chain | 仓库里出现 credential 字面量（PAT/AK/PEM/`password=` 实值）；Gradle 分发包缺 `distributionSha256Sum`；wrapper JAR 被换成 stub；workflow 里 `curl \| sh`；三模块版本号分裂 / README 消费示例过期 / 兼容矩阵漂移；`api/` 基线外出现 public 删除或签名变更（纯新增放行） |
| **Gate Self-Tests** | 每个门禁都拿**真实注入的违规**去验自己会失败 —— 只跑通过的门禁证明不了任何事 |
| Inventory | 文件数 / 行数 / 模块清单 |

### 为什么 WebView 加固要自己写门禁

CodeQL 里所有 WebView 相关查询（`websettings-file-access`、
`webview-addjavascriptinterface`、`improper-webview-certificate-validation` …）
都属于 **`security-extended` 套件，不在默认套件**；开启该套件是**仓库设置**（PR 改不了），
且 Kotlin 分析需要完整构建。在此之前 `check_webview_hardening.py` 是唯一在检查这些
不变量的人 —— 它把 SSL 处理、file/content 访问、混合内容、远程调试、`addJavascriptInterface`
全部锁成门禁。详见 [SECURITY.md](SECURITY.md)。

**一条容易踩的坑**：`allowFileAccessFromFileURLs` 自 API 30 起被系统忽略，但本库
`minSdk 26`，在 API 26~29 上它的默认值仍是 `true`。因此那行 `= false` 是**真起作用的
安全控制，不能当废弃代码删掉** —— 门禁会拦住删除行为。

`dependency-submission.yml` 另把 Gradle 解析结果喂给 GitHub 依赖图，
使 Dependabot 能对**传递依赖**告警（消费方通过我们的坐标间接依赖它们）。

### 宿主自动同步链路（v1.1.0 起）

本仓库 main 的每次合入都会**自动流入宿主** [Android-Guru-Agent]，全程无人工：

```
本仓库 push main
  → notify-host.yml 等待本提交 CI + Guard Rails 全绿（口径见下）
  → repository_dispatch 宿主（event: browser-kit-updated，HOST_SYNC_TOKEN）
  → 宿主 sync-browser-kit.yml 校验上游门禁全绿后 bump gradle/browser-kit.lock
    （PAT 推送 —— GITHUB_TOKEN 推送不触发 workflow，防递归设计）
  → push main 自动点燃宿主 release 流水线 → 新 APK（含本仓库最新代码）发布
```

门禁口径（双侧一致）：只统计**构建健康**类 check-run（CI 的 Build & Test /
Consumer Check + Guard Rails 全套）；显式排除 Dependabot*（长跑依赖升级）、
Gradle dependency graph（数据洞察链路）、notify-host 自身（自引用）。上游有
失败项时宿主**拒绝同步并显性报错**（fail loud），绝不跟红。另有宿主侧 30 分钟
cron 轮询兜底（防丢事件），`gradle/browser-kit.lock` 保证宿主构建可复现。

### 本地复现

```bash
./test.sh          # 门禁 + 门禁自测 + Gradle 单测
./test.sh gates    # 只跑门禁：纯 stdlib Python，不需要 JDK / Android SDK
```

门禁一律写成**可注入违规的自测**，见 `scripts/tests/`。改门禁后请连自测一起跑 ——
正则腐化会让门禁静默变成永远绿的空壳，这是 CI 门禁最常见的失效方式。

## 留在宿主的部分

- `BrowserAgentTools`（browser_* 工具协议：依赖宿主 `AgentTool`/`StreamingAgentTool`）
- `CyberNeonBallManager` / `NeonRingView`（宿主视觉装饰）
- 宿主 DI 接线模块（Hilt）
- `plugin-web-automation` 插件 APK —— 宿主已将其移除，浏览器自动化改以内置
  `:platform:browser-agent` 模块形态集成（见宿主仓库 PR）

## v1.1.0 变更（Agent 可靠性与高级功能）

### 错误率削减（修复既有缺陷）

1. **点击坐标密度换算修复**：旧实现把 `getBoundingClientRect` 的 CSS 像素直接当
   视图物理像素派发——density>1 设备上点击点系统性偏向左上（误点主因）。现按
   「视图宽 / CSS 视口宽」实测缩放系数换算（同时正确处理页面缩放）；
2. **JS 字面量统一转义**：`toJsonString()` 此前零转义——selector 含 CSS 属性
   选择器引号（`[href='x']`）、文本含撇号/换行都会撕裂 JS（静默失败）。现统一
   转义反斜杠/引号/换行/行分隔符 + `</` 序列；
3. **点击前置 scrollIntoView + 遮挡检测**：折叠线以下元素不再坐标越界；
   `elementFromPoint` 命中测试发现粘性顶栏/模态遮罩拦截时下移重试，仍被遮则
   明确报「被遮挡」而非静默误点；
4. **React/Vue 安全输入**：`el.value = x` 直赋改为原型链 native setter +
   input/change 事件（受控组件不再弹回）；contenteditable 支持；多行文本不再
   撕裂 JS；新增 append / pressEnter 模式；
5. **模糊自愈定位**：ref 失配时用快照缓存的 tag+文本模糊重定位并重打原 ref
   ——SPA 局部刷新不再直接失败；新增 `locateElements()` 供 Agent 主动按文本锚定；
6. **动作后真实 diff**：`PostActionState.urlChanged` 旧实现恒 false、
   `newElementsCount` 是当前计数而非增量——现按动作前后探针计算真实 URL 变化
   与元素增量，并携带 `currentUrl`。

### 新增高级 API

| API | 说明 |
|---|---|
| `pressKey(key)` | 键盘事件注入（Enter/Tab/Escape/方向键…），Enter 携带表单隐式提交语义 |
| `hover(ref)` | mouseover/mouseenter/mousemove 悬停序列（下拉菜单、:hover 样式） |
| `drag(fromRef, toRef)` | 触摸拖拽（DOWN → 插值 MOVE → UP），滑块/排序/拖放 |
| `extractContent(mode)` | 结构化抽取：article 正文 / tables 表格 / links 链接 / meta 元信息 |
| `executeJavaScript(js)` | 原生 JS 逃生舱（带超时），长尾页面逻辑兜底 |
| `getCookies(url?)` | 读取 Cookie（登录态诊断） |
| `networkLog(limit, urlContains)` | 网络日志按 URL 子串过滤 |
| `locateElements(text, tag)` | 按文本模糊查找元素并返回可用 ref |

版本：三模块 1.0.0 → **1.1.0**（additive API + 默认参数扩展，宿主源码兼容）。

## v1.2.0 变更（逻辑层文案注入与发布工程）

### ChromeStrings 注入（已知取舍 #1 消解）

chrome 层有三处用户可见文案产生于**逻辑层**而非 Compose 上下文（`stringResource`
不可达）：`BrowserChromeController` 的 6 条 snackbar、`SuggestionsBuilder` 的
4 条联想副标题、`ApexChromeWiring` 的 3 条 JS 弹窗按钮审计标签 —— v1.0.0 起
硬编码中文。v1.2.0 把这 13 条收敛为 `ChromeStrings` 注入接口：

- `ZhChromeStrings`（默认，`ChromeStrings.DEFAULT`）：与旧硬编码**逐字一致**，
  不注入即行为零变化；
- `EnglishChromeStrings`：词汇与 `values-en` 镜像同一套语感
  （Paste and go / URL / Search / Confirm / Deny / Close …）；
- 任意自译实现（其他语言 / 品牌语气）。

接入是**带默认值的尾参**，现有调用点零改动（源码兼容）：

```kotlin
// 控制器直构（BrowserChrome 的 controller 参数同款注入点）
val controller = BrowserChromeController(gateway, scripts, config, EnglishChromeStrings)

// 接线器工厂（宿主 DI Module 里同款）
val wiring = ApexChromeWiringFactory.get(ctx, engine, EnglishChromeStrings)
```

> `ApexChromeWiringFactory` 是进程级单例：**首次**调用的 `strings` 生效，切换
> 实现请保证首次就传入（或测试里 `reset()`）。`SuggestionsBuilder.build` 的
> `strings` 尾参由控制器自动透传，纯函数调用方也可独立注入。

Compose UI 层文案继续走 `res/values` + `values-en`（`browser_` 前缀强制），
两套机制各管一层：UI 层跟系统语言，逻辑层跟注入实现。契约由
`ChromeStringsTest` 锁定（中文逐字一致 / 两实现 13 条非空 / `tabSwitchHint`
含 host / 接口成员计数防漂移）。

### 发布工程

| 项 | 说明 |
|---|---|
| `tag-release.yml` | push main 自动读三模块 `version =`（分裂即 fail，与 `check_versions.py` RULE 1 同口径），无对应 tag 则 `git tag vX.Y.Z` 并推送（幂等：已存在跳过不报错；版本回退只跳过不强推）。tag push 不会点燃 `notify-host.yml`（其 `on:` 仅 `push.branches=[main]`，且 GITHUB_TOKEN push 本就不触发 workflow，双保险） |
| 版本可追溯 | 此前版本只存在于 `build.gradle.kts`、从未留下 git tag；现在每个发布列车落成 `vTag`，坐标 ↔ 源码状态一一对应 |

版本：三模块 1.1.0 → **1.2.0**（additive API，全部为带默认值参数的尾参扩展，
宿主源码兼容）。

## v1.9.0 变更（Playwright 对标列车：网络拦截 / Storage / Cookie 写 / 表单检测 / 性能 / iframe / UA 仿冒 / 布局下沉）

### 网络拦截（`NetworkInterceptor`，`:browser-engine` 新增）

- **Playwright `route().abort()` 的 WebView 对标面**：按 URL 规则（子串 / 正则）
  屏蔽子资源请求 —— 广告与跟踪器掐流量、图片/字体/媒体按需断供提速、可疑
  第三方域整体熔断；
- **装饰器实现，不动引擎一行**：读出 WebView 当前 client 用 `InterceptingClient`
  包装回装，拦截 `shouldInterceptRequest` 之外逐方法转发（SSL 拒绝 / scheme
  白名单 / 崩溃恢复语义原样保留）；标签切换 / 崩溃重建经 `ActiveTabListener`
  自动重新包装（幂等）；
- **安全底线（代码写死不可配置）**：主框架导航永不屏蔽（过宽规则不能打瞎
  浏览器）；非 GET 永不屏蔽（POST = 静默吞表单结果）；图片屏蔽回传 1×1
  透明 GIF 占位（不撕裂布局）；
- **四预设一键接入**：`blockTrackers()`（30 个主流 + 国产跟踪域）、
  `blockImages()` / `blockFonts()` / `blockMedia()`（正则扩展名匹配）；
- **可观测性**：每规则独立命中计数（`stats()` / `totalBlocked()`）；
  `NetworkInterceptor.forEngine(engine)` 进程级单例（与
  `BrowserEngineFactory` 同款纪律）。

### Web Storage 读写（`StorageAccess.kt`，`:browser-engine` 新增）

- localStorage / sessionStorage 六操作：`getStorageValue` / `setStorageValue` /
  `removeStorageKey` / `storageKeys` / `storageSize` / `clearStorage`
  （Playwright `storage_state` 对标的字面读写面）；
- 统一 `{ok,...}` 信封：storage 被禁用 / QUOTA_EXCEEDED / 隐私模式转成
  结构化错误（`StorageMutation(ok=false, error=...)`），不撕裂 JS；
- 读值按 32k 字符截断并如实标注（`length` / `truncated` —— 截断如实上报
  是库的既定契约）；key/value 全部经 `JsLiteral.string` 编码注入。

### Cookie 写入与域级清除（`CookieControl.kt`，`:browser-engine` 新增）

- `setCookie(url, name, value, path, expires)`：恢复登录态 / 预置会话；
  name/value 拒绝 `;` 与控制字符（撕裂 Set-Cookie 头 = 注入面）；
- `removeCookie`（过期语义删除）+ `clearCookiesForDomain`（域级清除 ——
  重登单站点不动其他站点登录态；全局清除继续走
  `EngineDisplayController.clearBrowsingData`）；
- Secure 属性跟随 URL scheme；`flush()` 保证跨进程持久。

### 表单结构检测（`formDetectJs` + `FormReport`，core + engine）

- **一次采集直达可执行计划**：页面全部 `<form>` + 孤儿字段（SPA 散字段）
  的结构化报告 —— 字段 / 类型 / label / placeholder / required / disabled /
  select 选项 / 提交按钮，**每个字段带与快照同源的 ref**
  （hash 函数 / semanticKey 公式 / 可见性剪枝 / `#n` 去重逐字一致）；
- Agent 拿到报告直接构造 `batchExecute` 序列填提，无需先跑快照对齐；
- **密码字段不回传值**（只报 `hasValue`）：可检测「已记住密码」但读不走内容；
- 不可见字段不编造 ref（照常回传信息 + `visible:false`），与快照契约一致。

### 性能指标（`PERFORMANCE_METRICS_JS` + `PerformanceReport`）

- Navigation Timing 里程碑（DNS / TCP / TTFB / DOMContentLoaded / Load /
  重定向数）+ Resource Timing 按 initiatorType 聚合（数量 / 字节 / 最慢）+
  最慢资源 Top 8；
- 回答 Agent 的三个常见性能问题：慢在哪 / 拉了多少 / 重定向几跳。

### iframe 感知（`FRAME_LIST_JS` + `FrameReport`）

- 嵌入框枚举：src / name / title / 尺寸 / sandbox / 同源判定；
- 快照与表单检测只覆盖顶层文档 —— 本报告把「Agent 根本不知道有 iframe」
  转化为「显式知道框的存在与来源」（框内操作是另一代工程，如实声明）。

### UA 仿冒（`EngineEmulation`，`:browser-engine` 新增）

- `setUserAgent(ua?)` 覆写/恢复 + `DESKTOP_UA` 预设一键桌面模式
  （移动站只发精简版页面 / 绕过「浏览器不受支持」门禁的刚需）；
- 跨标签持久：`ActiveTabListener` 在新 WebView 激活时自动重放覆写；
- 视口仿冒不做（WebView 尺寸由承载容器决定，库层无对等面 —— 如实声明）。

### 布局分析下沉（`layoutCollectJs` + `LayoutAnalysis`，core）

- 宿主 `BrowserLayoutTools` 的百行内联采集脚本 + 格式化器下沉入库：
  脚本进 `BrowserScript.layoutCollectJs(maxElements)`，格式化进纯 JVM 的
  `LayoutAnalysis.formatReport`，宿主 `engine.analyzeLayout()` 一行调用拿
  报告（视口溢出 / landmark / flex/grid / 定位 / 层叠 / 文字截断 /
  小点击目标 / WCAG 对比度 / 暗色探测，与宿主版逐行为对齐）；
- 宿主自维护内联 JS 的形态不可持续（库改脚本宿主不同步）—— 库才是它的家。

### 兑现 v1.7.0 已知取舍 TODO

- `BrowserEngine.clearRecentHistory()`：清空地址栏联想历史源（此前
  `clearBrowsingData(histories)` 只清 WebView 原生历史，联想源是引擎内存
  队列，无清空 API —— 已知取舍里明确记了「后续版本补」）。

版本：三模块 1.8.0 → **1.9.0**（additive：新 API 全部为新类型 / 扩展函数 /
带默认值参数，既有成员面零变更，宿主源码兼容）。

## v1.8.0 变更（提速列车 + 批量动作：自适应等待 / 去重注入 / 自适应轮询 / batchExecute）

### 速度：动作后固定延迟 → 探针轮询早退（`awaitActionSettle`）

- 旧链路点击后固定 `delay(300~800ms 随机)` 再探针——页面秒响应也要睡满；
  现改为「等最小窗（click 120ms）后首查探针，URL/标题/交互元素数已变化
  即返回，否则每 100ms 复查至上限（600ms）」——**典型点击 ~550ms → ~140ms**；
- 全动作覆盖：click / input（enter 与否分档）/ select / pressKey / hover /
  drag，各自定制 min/max 预算；无变化动作（toggle 类）仍以 max 封顶，不劣于
  旧随机上界；
- 探针子系统（`QuickProbe`/`readQuickProbe`/`probePage`/`awaitActionSettle`）
  拆出同包 `ActionProbe.kt`（internal，GATE 1 预算纪律；`evaluateJson`
  随之 private → internal）。

### 速度：snapshot 去重注入 + waitForCondition 自适应轮询

- 每次 snapshot 不再重复执行 `NETWORK_MONITOR_JS`（onPageFinished 已随每次
  导航注入；`Tab.netHooked` 位图去重，导航重置自动复位）；
- 等待轮询前 1.2s 用 100ms 快轮（`pollIntervalFor`），之后回落调用方
  pollMs——「点击后内容秒到」不再多等整拍 300ms；
- `DomParser.buildSummary` 单行渲染按 bid 缓存（选择/输出两循环复用，不再
  重复计算 `lineOf`）。

### 功能：批量动作执行（`batchExecute`，表单填写主战场）

- **`BatchAction`**（`:browser-core` 纯 JVM）+ **`BatchActionValidator`**
  （字段完备性校验，错误提示定位到第几个动作，可单测）；
- 引擎扩展 `BrowserEngine.batchExecute(actions)`（同包 `BatchExecution.kt`）：
  一次调用串行执行 ≤8 个动作（click/input/select/press_key/scroll/wait 七
  类型），逐个带重试/熔断/模糊自愈/动作后验证，**任一失败即停**——把 N 次
  「模型往返 → 工具执行」压缩为 1 次；
- `BatchOutcome.summaryLine()` 面向模型的单行摘要。

### 功能：滚动到边界（`scrollToBoundary`）

- `BrowserEngine.scrollToBoundary(toBottom)` 扩展（`ScrollBoundary.kt`）：
  超长页面一步到底/回顶（`scrollByJs` 受单次上限约束，到底原本需多次调用）。

### 修复：后台视口截图死代码

- `screenshot()` 的提前 return 守卫使 P1-4 手动 measure/layout 兜底永远
  不可达（未挂浮窗的后台 WebView 截图必返回 null）——移除守卫，兜底路径
  重新生效。

版本：三模块 1.7.0 → **1.8.0**（additive：新 API 全部为扩展函数/新类型，
既有成员面零变更，宿主源码兼容）。

## v1.7.0 变更（细节功能与用户体验补全：书签 / 阅读模式 / 下载中心 / 显示控制 / 全页截图 / 干净链接）

### 书签体系（chrome + engine，新增）

- **`BookmarkStore`**（`:browser-engine`，SharedPreferences 持久化）+
  **`BookmarkCore`/`BookmarkCodec`**（`:browser-core` 纯 JVM 规则/编解码，
  MRU 排序 / 文件夹 / 搜索 / 500 条配额淘汰，全部可单测）；
- **去重键走 `UrlCleaner.normalizeKey`**：同一文章的「分享链接（带 utm）」
  与原始链接归一书签——移动端分享场景不再把书签栏刷成同一页面的三四种
  参数形态；重复收藏 = 更新（不报错）；
- **`BookmarksSheet`**（chrome）：搜索框（本地过滤，标题/站点/网址）+
  文件夹片 + 行内删除 + 「收藏当前页」；favicon 经 `FaviconImage` 渲染；
- 接线：`ChromeSessionControls` 能力束（`ApexChromeWiring` 构造时注入
  `NeonChromeWiring` → adapter → `BrowserEngineGateway` additive 成员，
  未接线自动隐藏，`FakeEngineGateway` 零改动）。

### 下载中心（`DownloadCenter`，`:browser-engine` 新增）

- **台账持久化**（`DownloadLedger` + JSON codec，SharedPreferences）：
  系统 DownloadManager 的 ID 跨进程重启可续查——「重启后下载列表为空」
  这一移动端普遍缺口被补上；
- **双下载源吸收**：chrome 人用下载（`onEnqueued` 回报入账）+ Agent 驾驶期
  引擎下载（`lastDownload` 轮询吸收孤儿）；
- **生命周期控制**：pause / resume（API 24+）/ cancel / retry（原 URL 重入队，
  转发会话 Cookie）/ forget / 打开系统下载 UI；
- 活动下载 500ms 轮询、终态即停（省电）；状态语义翻译
  （QUEUED/RUNNING/PAUSED/COMPLETED/FAILED）。

### 阅读模式（chrome + core，新增）

- **`READER_DOCUMENT_JS`**（`:browser-core`）：按文档顺序采集结构块
  （heading 带层级 / paragraph / quote / code 保留换行 / image 带 alt /
  list_item）+ 排版元数据（og:site_name / author / published_time）；
- **`ReaderParser`/`ReaderDocument`**（纯 JVM）：防御性解析 + 目录聚合 +
  `plainText` 结构化拍平（Agent 消费镜像）；
- **`ReaderSheet`**（chrome）：全屏沉浸层——字号 ±（0.8..1.6 线性）、
  目录片点按跳块（LazyColumn 锚点滚动）、阅读时长估算、三态
  （Loading/Ready/Unavailable——工具页属正常降级不是错误）。

### 显示控制（`EngineDisplayController`，`:browser-engine` 新增）

- **文字缩放**（WebSettings.textZoom，50..200）：跨标签保持
  （`ActiveTabListener` 重放）、跨导航幂等；
- **夜间模式**（算法反色 JS 注入，零 androidx.webkit 依赖）：
  `onPageFinished` 重注（chrome wiring 钩子）保持跨页生效；
- **清除浏览数据**（Cookie 全局 / 缓存 / WebView 原生历史）+
  `DisplaySettingsSheet`（chrome）：缩放滑步 + 快速档位 + 双开关 +
  不可逆确认对话框（ChromeScrimDialog 仪式感）。

### 全页截图（`fullPageScreenshot` 扩展，`:browser-engine` 新增）

- 滚动分段 + Canvas 拼接实现 Playwright `fullPage: true` 同语义；
- 分段上限 5（ARGB_8888 位图 OOM 防线：`runCatching` 返回 null 而非崩溃）；
  截后恢复原滚动位置（零副作用退出）。

### URL 卫生（`UrlCleaner`，`:browser-core` 新增）

- 跟踪参数剥离（utm_*/gclid/fbclid/msclkid 等 40+ 精确项 + 16 前缀族；
  **语义两可参数 ref/source/id 不删**——误删即断链）；
- 「复制干净链接」菜单项：剔除数实时入轻提示，无可剔除时回退普通文案
  （不制造虚假的隐私获得感）；
- 手工解析零失败面（`java.net.URI` 对未转义中文/空格抛错——真实分享链接
  的常态）；幂等（书签去重依赖）。

### 真实 favicon（`FaviconLoader`，chrome 新增）

- DuckDuckGo Icons（`icons.duckduckgo.com`，按 host 取图不携路径——把
  「用户在看哪个页面」泄露给图标服务的面积最小化）+ 进程内 LruCache(32)
  + 在途去重 + 失败回退首字母徽标（视觉规格与占位实现一致，调用点零迁移）。

### 脚本面扩展（`BrowserScript`，v1.7.0 段）

- `PAGE_GEOMETRY_JS` / `scrollToYJs`（全页截图几何与可复现分段）；
- `READER_DOCUMENT_JS` / `SELECTION_TEXT_JS` / `elementTextJs(ref)`
  （阅读采集 / 选区文本 / 元素文本读取）；
- `FORCE_DARK_JS` / `FORCE_DARK_OFF_JS`（幂等夜间模式注入与撤除）。

版本：三模块 1.5.0 → **1.7.0**（纯 additive API；连同已合并未发版的
v1.6.0 实时悬浮窗尺寸调整一并过发版列车）。门禁全绿：core 纯度 /
JS 注入边界 / WebView 加固 / 资源中英镜像（84 → 118 键）/ 文件预算
（`BrowserEngine.kt` 保持 1199/1200 非注释行——全部增强走新文件）/
版本单一列车 / API 基线零删改。

## v1.6.0 变更（实时页面悬浮窗尺寸调整，补记发版）

### `LiveViewOverlay` 缩放把手（v1.6.0 已合并、随 1.7.0 列车发版）

- 右下角缩放把手（◢）拖拽调整实时页面悬浮窗尺寸；比例钳制
  （0.35–0.95 宽 / 0.25–0.85 高）+ SharedPreferences 持久化 +
  帧对齐 16ms `updateViewLayout` 节流；
- 自适应轮询节流（加载中 500ms / 空闲 2000ms，`pollIntervalFor` 纯函数）；
  纯函数与钳制由 `LiveViewSizeTest` 锁定。

## v1.5.0 变更（App 内嵌浏览器宿主 / 三承载面互斥仲裁）

### App 内嵌浏览器宿主（`InAppChromeHost`，新增）

- **零悬浮窗权限的完整浏览器**：本库的可视层此前只有系统悬浮窗
  （`TYPE_APPLICATION_OVERLAY`，需 `SYSTEM_ALERT_WINDOW` 特殊权限，无权限时
  静默降级）—— 权限未授予的宿主用户永远看不见浏览器。`InAppChromeHost`
  把引擎活动 WebView 连同完整 `BrowserChrome` 嵌进宿主 **App 自己的窗口**
  （Activity 组合树内 `AndroidView` 容器），浏览器从此**无条件可用**；
- **四动作生命周期**（全主线程）：`attach`（页面进入组合：承载权 claim +
  wiring resync 对齐 chrome 数据源 + 换挂活动 WebView）/ `detach`（页面离开
  组合：WebView 归还引擎无头驱动）/ `pause`（Activity ON_PAUSE：让出承载权，
  悬浮窗可视层可接管）/ `resume`（ON_RESUME：重新换挂）；
- **换挂走引擎多播**：订阅 `BrowserEngine.ActiveTabListener`（而非 wiring 的
  单回调 var，不与 `BrowserOverlay` 抢占）—— 标签切换/新建/崩溃重建自动换页；
- 宿主接法：`InAppChromeHostFactory.get(engine, wiring)` 注册进宿主 DI
  （与另两个工厂同款单例纪律）。

### P1 渲染修复：接管浮窗「有 chrome 无页面」（`BrowserChrome` 页区透明）

- 旧实现根 Column 恒刷不透明 `palette.background`，而真实 WebView 宿主在
  **组合树之外**（同窗口兄弟 View 层）—— 不透明底色把 WebView 整个盖住：
  人工接管浮窗展开后只见地址栏 + 一块纯色页区，**页面内容从未显示过**
  （ChromeBar / TabStrip 自带不透明底，根底色对头部是冗余双重绘制）；
- 修复：`BrowserChrome` 新增 `transparentPageArea: Boolean = false`（默认
  保持旧视觉，页区由 pageSlot 自绘的消费者零影响）；`BrowserOverlay` 传
  true —— 页区透出真实 WebView，`InAppChromeHost` 消费方同款传法。

### 三承载面互斥仲裁（`InAppHostingArbiter`，新增）

- 同一 WebView 同一时刻只能有一个父容器：in-app 容器 / 全屏接管浮窗 /
  实时页面卡片三个承载面需要一个进程级裁决点；
- **in-app 承载期间**：`BrowserOverlay` 的 WAITING_HUMAN 不再自动展开（
  in-app chrome 就是人的 UI），已展开则收起；`LiveViewOverlay` 可见卡片转
  GHOST（页面在 App 内直接可见，换挂/自愈同步被守卫拦下，不抢 WebView）；
- **in-app 退场**（页面离开/Activity 暂停）：接管浮窗按 WAITING_HUMAN 补
  展开、实时卡片按引擎状态机重放 —— 用户离开 App 后悬浮窗体验不变；
- 纯 JVM 无 Android 依赖，契约由 `InAppHostingArbiterTest` 八项断言锁定
  （幂等迁移/通知一次/注销停止/自注销不 CME/异常不传播）。

版本：三模块 1.4.0 → **1.5.0**（additive API；`InAppChromeHost` /
`InAppChromeHostFactory` / `InAppHostingArbiter` 均为新增，两个既有浮窗仅
加守卫分支，无破坏性变更）。

## v1.4.0 变更（实时页面悬浮窗 / 悬浮窗 P0 崩溃修复 / 活动标签广播）

### 透明实时页面悬浮窗（`LiveViewOverlay`，新增）

- **Agent 一用浏览器就自动弹出**：订阅引擎状态机，`AGENT_DRIVING` 即呈现
  透明玻璃卡片浮窗（85 屏宽 × 60 屏高、居中可拖动、PixelFormat.TRANSLUCENT），
  页面区直接承载引擎 active WebView —— 用户全程**看见 Agent 正在开的真实页面**；
- **收起不是断开**：用户「✕」收起后窗口转 GHOST 无头宿主（视图 INVISIBLE +
  `FLAG_NOT_TOUCHABLE`，窗口保活）——WebView 留在视图树内持续被测量布局，
  自动化的 CSS→物理坐标换算与命中测试不因脱离窗口而失真；
- **三态生命周期**（`LiveViewPolicy`，纯 JVM 可测）：VISIBLE 显示 / GHOST
  隐形宿主 / GONE 整窗移除（会话结束自动复位「用户收起」标记，下次自动弹出恢复）；
- **纯 View 实现零 Compose**：刻意避开浮窗内 Compose 的
  ViewTreeLifecycleOwner 系列窗口层级坑（见下条 P0 的同类根源），控制条崩溃面最小；
- 宿主接法：`LiveViewOverlayFactory.get(context, engine)` 注册进宿主 DI
  （与 `BrowserOverlayFactory` 同款单例纪律），悬浮球点击 `toggle()`、
  浏览器功能页「查看实时页面」调 `show()`。

### P0 崩溃修复：`BrowserChrome` 浮窗内 `BackHandler` 主线程崩溃（宿主 #375）

- activity-compose 的 `BackHandler` 在 `enabled=false` 时也会先
  `checkNotNull(LocalOnBackPressedDispatcherOwner)` —— 悬浮窗 ComposeView 挂在
  WindowManager（无 Activity 返回栈），该 Local 恒为 null → 组合即抛
  `IllegalStateException` 杀主线程（crash-1792191618578：浮窗显示后约 20s
  崩溃，用户观感「点击悬浮球闪退 / agent 用浏览器后卡死重启」）；
- 修复：无 dispatcher 时整个 `BackHandler` 不进入组合树（条件组合，而非
  `enabled=false` 短路 —— 旧实现的 `hasBackDispatcher` 守卫防不住这层
  checkNotNull）。

### 引擎：活动标签广播（`ActiveTabListener`，additive API）

- 标签新建/切换/关闭回落/崩溃重建/长会话重建都会换新活动 WebView，但这类
  变化**不产生状态机迁移** —— 只订 `BrowserUiCallback` 的可视层拿不到新实例；
- 新增 `BrowserEngine.addActiveTabListener / removeActiveTabListener`：
  把「当前应展示哪个 WebView」的变化单独广播（主线程），实时页面悬浮窗据此
  换挂，Agent 驾驶中切标签实时画面跟随切换。

版本：三模块 1.3.0 → **1.4.0**（additive API + 一个 P0 修复；`LiveViewOverlay`
/ `LiveViewOverlayFactory` / `LiveViewPolicy` / `ActiveTabListener` 均为新增，
无破坏性变更）。

## v1.3.0 变更（错误语义 / 快照保真度 / 等待谓词 / 可观测性）

### 错误语义拆分与重试契约兑现（:browser-core + 引擎接线）

- 新增 `PermanentActionException`：操作本身不可能成功的语义错误（对非输入元素
  `inputText` / `selectOption` 选项不匹配 / 页面侧回传非预期形态）。`withRetry`
  对其立即上抛——**不烧退避、不计熔断**（旧实现一律抛 `ElementNotFoundException`
  白耗 3 次退避并推动全局熔断，一个坏元素锁死整段会话）；
- `CircuitBreaker` 的 `acquire/onSuccess/onFailure` 全部 `@Synchronized`：旧实现
  `consecutiveFailures++` 非原子，跨线程丢计数导致熔断迟开；
- 快照超时转译为 `java.util.concurrent.TimeoutException`（可重试集合成员）——
  旧实现直接把 `TimeoutCancellationException` 上抛，KDoc 承诺的「快照超时视为
  可重试，熔断保护」从未生效。转译前检查外层协程存活，不破坏结构化取消。

### 快照保真度（:browser-core + 引擎接线）

- **ref 同键去重**：同一 `semanticKey`（列表页同批商品卡/表格同列删除按钮）算出
  同一 hash，撞键后定位永远命中第一个。第 2+ 次出现追加 `#n` 后缀；
- **title 修复**：引擎 WebViewClient 此前从未覆写 `onReceivedTitle`，
  `PageSnapshot.title` 恒为空串；`onPageStarted` 同步复位 `pageFinished`
  （修 goBack/goForward 后的「假就绪」）；
- **快照融合**：`PageSnapshot` 追加 `timestampMs`（生成时刻）与 `dialogNotice`
  （JS 弹窗/SSL 拦截/权限/下载通知，**消费即清**——写入本次快照后随即清空，
  模型不重复收到同一条）；
- **a11y 降级源 total 口径修正**：旧 `total = querySelectorAll('*').length`
  （纯布局节点全计入，`truncated` 几乎恒真），改为循环内语义节点计数。

### 等待谓词扩展（waitForCondition 3 → 7 模式）

| 模式 | 语义 |
|---|---|
| `element_gone` / `text_gone` | 选择器/文本从页面**消失**（弹窗、加载态收起判定） |
| `network_idle` | 在途请求数归零（`NETWORK_MONITOR_JS` 新增 `__apexNetPending` 计数，**连续 2 次为 0** 才算 idle） |
| `selector_count` | 命中数满足谓词：`"<选择器>>=10"` / `"<=5"` / `"==3"` / `"<选择器> 10"`（纯数字缺省 `>=`，要求与选择器间有空白，避免与 `.item2` 歧义） |
| `url`（增强） | 改注入 `CURRENT_URL_JS` 求值：SPA pushState 即时反映到 `location.href`，native `webView.url` 只在导航提交时更新 |

### 新增引擎 API（Agent 直驱路径补齐）

| API | 说明 |
|---|---|
| `reload(timeoutMs)` | 重载 + 等待就绪（此前宿主只能裸调 `webView.reload()`） |
| `stopLoading()` | 停止当前页加载（Agent 逃生舱） |
| `findInPage(query)` | `findAllAsync` + FindListener 桥接，返回命中数 |
| `engine.tracer` | 引擎内建 `BrowserTracer`（见下） |

### 可观测性接线（`BrowserTracer` 从零到一）

- 引擎持有公开 `tracer` 属性；关键动作（navigate/click/input/select/scroll/
  snapshot）经 `tracedAction` 包装记录 成败/参数/耗时/会话状态/URL；
- 系统事件入 trace：渲染进程崩溃 / SSL 拦截 / confirm 自动确认 / 下载入队；
- `BrowserTracer.Entry` 追加 `error` 尾参（失败边界的错误摘要）；新增
  `events: SharedFlow<Entry>` 实时事件流（replay=0，DROP_OLDEST——慢消费者丢旧值
  不阻塞记录路径）；
- 下载管理：DownloadManager 请求转发会话 `Cookie`（登录态后的导出/附件下载
  不再 401）；网络监控随 `onPageFinished` 与 `STEALTH_JS` 合并注入（导航重置
  JS 上下文后不再有监控盲区）。

### 注入边界收敛（安全债清偿）

`locateElements` 的内联 JS（手写转义只覆盖引号，换行 / U+2028 / 控制字符裸拼）
迁移为 `BrowserScript.locateJs`——参数一律 `JsLiteral.string`，与全库唯一编码
入口统一。至此 `:browser-engine` 内不再有任何手拼注入 JS。

版本：三模块 1.2.0 → **1.3.0**（additive API；`PageSnapshot` / `BrowserTracer.Entry`
构造器为带默认值的尾参扩展，宿主源码兼容）。

## 已知取舍（v1.0.0）

- 逻辑层文案硬编码中文（`BrowserChromeController` 5 条 snackbar 与
  `SuggestionsBuilder` 联想副标题，非 Compose 上下文、`stringResource` 不可达）
  —— **v1.2.0 已消解**：连同 `ApexChromeWiring` 的 3 条 JS 弹窗审计标签共
  13 条收敛为 `ChromeStrings` 注入接口，默认 `ZhChromeStrings` 与旧硬编码
  逐字一致，可换 `EnglishChromeStrings` 或自译实现（见「v1.2.0 变更」）。
  chrome UI 层文案始终资源化（`res/values` + `values-en`，`browser_` 前缀强制）。
- 库 manifest 不声明 `SYSTEM_ALERT_WINDOW` —— 由宿主自行声明（并非所有消费者都
  需要浮窗，F5）。
- **v1.3.0**：引擎 `findInPage` 与 chrome 层页内查找共用 WebView 的**单槽**
  FindListener——Agent 侧调用会替换 chrome `ChainFindListener` 的注册，chrome
  的计数回填依赖其下次接线时重设监听器（Agent 侧计数优先的取舍）。
- **v1.3.0**：`locateElements` 迁移到 `BrowserScript.locateJs` 后，
  `check_js_injection.py` GATE 1 的扫描范围仍为 `BrowserScript.kt`（引擎侧注入
  JS 已全部收敛到该文件，规则本身无需扩面）。
- **v1.7.0**：全页截图的 sticky/fixed 元素会在每个分段重复（顶栏在拼接图
  出现 N 次）——Canvas 快照式全页截图的固有形态（Playwright 走 CDP 越过
  渲染层，WebView 无此通道）；分段上限 5 是 OOM 防线不是能力上限。
- **v1.7.0**：夜间模式为算法反色——iframe 内文档与个别 fixed 层可能出现
  「半亮半暗」；追求完美需 androidx.webkit 的 algorithmic darkening（引依赖，
  不做）。清除浏览数据不含地址栏联想历史（引擎 recentHistory 的内存
  ArrayDeque 无清空 API——**v1.9.0 已兑现**：`BrowserEngine.clearRecentHistory()`）。
- **v1.7.0**：favicon 经 DuckDuckGo Icons 按宿主名取图（无 host 网络时静默
  回退首字母徽标）；`DownloadCenter` 的台账只记本库发起的下载，不吞并
  整个系统下载列表（职责边界）。
- **v1.9.0**：`NetworkInterceptor` 只做屏蔽，不做改写/重定向
  （`shouldInterceptRequest` 无法把请求头修改后转发 —— 需自建网络栈，
  破坏 Cookie/Referer 语义）；装饰器转发面 = 引擎匿名 client 现有覆写的
  五个方法，引擎后续新增覆写需同步转发（review 守护）。`EngineEmulation`
  只覆写活动与后续激活的标签（引擎不暴露后台标签 WebView 句柄），视口
  仿冒无对等面。表单检测/快照不进 iframe（用 `frameList()` 感知后
  `executeJavaScript` 直查同源框 `contentDocument`）。

## 来源追溯

- 抽取自 `Android-Guru-Agent@139ffee`（main, 2026-10）：
  - `core/tool-registry/.../builtin/browser/` → `:browser-core`
  - `app/.../browser/{BrowserEngine,BrowserTracer,OverlayLifecycleOwner,RetryPolicy}.kt` → `:browser-core` / `:browser-engine`
  - `app/.../browser/chrome/**` + `BrowserOverlay.kt` → `:browser-chrome`
- 两仓库 commit 互相引用宿主侧删除提交与库侧首个提交。

## License

MIT
