package com.apex.browser.core

/**
 * v2.1.0 BrowserTask 任务模型（纯 JVM）：一次提交、运行时自主推进、
 * 最后一次性返回压缩结果 —— 把 N 次「模型往返 → 工具执行」压成 1 次。
 *
 * 与 BrowserBatchActions（≤8 动作、ref 寻址、维持现状不动）的分工：
 * 任务步骤按 **CSS selector 直操作，不经 snapshot-ref 链**（模型无需先快照拿 ref），
 * 支持导航/等待/抽取，失败与歧义有完整状态机（预校验/检查点/不回滚承诺）。
 */

/** 任务步骤类型。 */
enum class TaskActionType { NAVIGATE, WAIT_FOR, FILL, CLICK, PRESS_KEY, SCROLL, EXTRACT }

/** 歧义处置：selector 命中 >1 元素时的行为（评审 P0 检查点语义）。 */
enum class Disambiguation {
    /** 任务暂停在检查点，返回候选清单交模型决策（默认）。 */
    CHECKPOINT,
    /** 直接取第一个命中元素（确定性场景显式声明）。 */
    FIRST,
}

/**
 * 任务步骤。字段按类型取用，预校验（[TaskValidator]）负责完备性。
 *
 * @param disambiguation FILL/CLICK 的歧义处置，默认 [Disambiguation.CHECKPOINT]
 * @param expectedDocId 可选前置校验：执行时当前 docId 不一致则 STALE_DOCUMENT
 */
data class TaskStep(
    val type: TaskActionType,
    val url: String = "",
    val selector: String = "",
    val text: String = "",
    val value: String = "",
    val key: String = "",
    /** WAIT_FOR 条件模式（同 WaitCondition 词汇表）。 */
    val waitMode: String = "",
    val waitValue: String = "",
    /** 步骤超时毫秒（navigate/wait 默认 15000/10000）。 */
    val timeoutMs: Long = 0,
    /** EXTRACT 模式：article / tables / links / meta / text（text=selector 子树纯文本）。 */
    val extractMode: String = "article",
    val disambiguation: Disambiguation = Disambiguation.CHECKPOINT,
    val expectedDocId: String = "",
    /** SCROLL：像素（正下负上），0 = 滚到底部。 */
    val deltaY: Int = 0,
    /** FILL 追加模式（默认整段替换）。 */
    val append: Boolean = false,
    /** FILL 后回车。 */
    val pressEnter: Boolean = false,
)

/** 单步执行结果。 */
data class TaskStepResult(
    val stepIndex: Int,
    val type: TaskActionType,
    val ok: Boolean,
    val detail: String,
    /** 该步执行后的当前 docId（NAVIGATE 提交后必填；其余步尽力填充）。 */
    val docId: String? = null,
    val elapsedMs: Long = 0,
)

/** 任务状态（评审 P0 检查点：CHECKPOINT 是一等状态，不是失败）。 */
enum class TaskStatus { QUEUED, RUNNING, CHECKPOINT, COMPLETED, FAILED, CANCELLED, TIMEOUT }

/** 检查点载荷：selector 命中多个元素，交模型决策。 */
data class TaskCheckpoint(
    val stepIndex: Int,
    val selector: String,
    val candidates: List<CheckpointCandidate>,
    /** 检查点之后未执行的步骤数（模型决策后重新提交收敛任务）。 */
    val remainingSteps: Int,
)

/** 检查点候选（ref 由引擎侧打标，可直接用于 ref 类工具）。 */
data class CheckpointCandidate(
    val ref: String,
    val tag: String,
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** 任务结果（模型可见的最终形态；压缩纪律：result 有字符上限）。 */
data class TaskOutcome(
    val taskId: String,
    val status: TaskStatus,
    val steps: List<TaskStepResult>,
    val completedCount: Int,
    val failedIndex: Int? = null,
    val stopped: Boolean = false,
    val finalDocId: String? = null,
    val checkpoint: TaskCheckpoint? = null,
    /** 最终产出（EXTRACT 的压缩结果 / 全程摘要）。 */
    val result: String = "",
    val elapsedMs: Long = 0,
) {
    /** 工具面单行摘要（宿主 BrowserTask 直接返回本体的紧凑版）。 */
    fun summarize(maxResultChars: Int = 4000): String = buildString {
        appendLine("任务 $taskId：$status（完成 ${completedCount}/${steps.size} 步）")
        checkpoint?.let { cp ->
            appendLine("⏸ 检查点：第 ${cp.stepIndex + 1} 步 selector「${cp.selector}」命中 ${cp.candidates.size} 个元素，请选择后重新提交收敛任务：")
            cp.candidates.forEach { c ->
                appendLine("  - [${c.ref}] <${c.tag}> ${c.text.take(60)} @(${c.x},${c.y} ${c.width}x${c.height})")
            }
            appendLine("  （其后 $remainingStepsOrZero 步未执行）")
            return@buildString
        }
        steps.forEach { s ->
            val mark = if (s.ok) "✅" else "❌"
            appendLine("$mark 第 ${s.stepIndex + 1} 步 ${s.type}：${s.detail.take(120)}")
        }
        if (result.isNotBlank()) {
            appendLine("结果：")
            appendLine(result.take(maxResultChars))
        }
    }

    private val remainingStepsOrZero: Int get() = checkpoint?.remainingSteps ?: 0
}

/**
 * 任务预校验（评审 P0：**预校验先于执行** —— 任一步不合法即整体拒绝，
 * 不出现「已导航已点击一半才发现第 4 步没参数」的半程副作用）。
 *
 * 错误信息直接面向模型（同 BatchActionValidator 纪律）：指出第几步、缺什么、怎么改。
 */
object TaskValidator {

    private const val MAX_STEPS = 12
    private const val MAX_SELECTOR_LEN = 500
    private const val MAX_TEXT_LEN = 20_000

    /** 全部合法返回空列表；否则逐条给出「第 N 步 + 修正建议」。 */
    fun validate(steps: List<TaskStep>): List<String> {
        if (steps.isEmpty()) return listOf("任务不能为空（至少一个步骤）")
        if (steps.size > MAX_STEPS) return listOf("步骤数 ${steps.size} 超上限 $MAX_STEPS（拆成多次任务提交）")
        val errors = mutableListOf<String>()
        steps.forEachIndexed { i, s ->
            val at = "第 ${i + 1} 步 ${s.type}"
            when (s.type) {
                TaskActionType.NAVIGATE -> {
                    if (s.url.isBlank()) errors += "$at：缺 url"
                    else if (!s.url.startsWith("http") && s.url.contains("://")) errors += "$at：url scheme 不受支持（仅 http/https）"
                }
                TaskActionType.WAIT_FOR -> {
                    if (s.waitMode.isBlank()) errors += "$at：缺 waitMode（selector|text|url|element_gone|text_gone|network_idle|selector_count|dom_stable）"
                    else if (s.waitMode !in WAIT_MODES) errors += "$at：waitMode「${s.waitMode}」未知（可用：$WAIT_MODES_LABEL）"
                    else if (s.waitValue.isBlank() && s.waitMode != "network_idle" && s.waitMode != "dom_stable") errors += "$at：该 waitMode 需要 waitValue"
                }
                TaskActionType.FILL -> {
                    if (s.selector.isBlank()) errors += "$at：缺 selector"
                    // text 允许空串 = 清空输入框（合法语义）
                }
                TaskActionType.CLICK -> if (s.selector.isBlank()) errors += "$at：缺 selector"
                TaskActionType.PRESS_KEY -> if (s.key.isBlank()) errors += "$at：缺 key（enter/escape/tab/…）"
                TaskActionType.SCROLL -> Unit // deltaY=0 = 滚到底部，合法
                TaskActionType.EXTRACT -> {
                    if (s.extractMode !in EXTRACT_MODES) errors += "$at：extractMode「${s.extractMode}」未知（可用：article/tables/links/meta/text；text 需配 selector）"
                    if (s.extractMode == "text" && s.selector.isBlank()) errors += "$at：extractMode=text 需要 selector 指定子树"
                }
            }
            if (s.selector.length > MAX_SELECTOR_LEN) errors += "$at：selector 过长（>${MAX_SELECTOR_LEN}）"
            if (s.text.length > MAX_TEXT_LEN) errors += "$at：text 过长（>${MAX_TEXT_LEN}）"
            if (s.selector.contains('\u0000') || s.text.contains('\u0000')) errors += "$at：参数含非法控制字符"
        }
        return errors
    }

    private val WAIT_MODES = setOf(
        "selector", "text", "url", "element_gone", "text_gone", "network_idle", "selector_count", "dom_stable",
    )
    private val WAIT_MODES_LABEL = "selector|text|url|element_gone|text_gone|network_idle|selector_count|dom_stable"
    private val EXTRACT_MODES = setOf("article", "tables", "links", "meta", "text")
}
