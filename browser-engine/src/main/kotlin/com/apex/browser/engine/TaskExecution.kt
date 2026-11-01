package com.apex.browser.engine

import android.os.SystemClock
import com.apex.browser.core.BrowserScript
import com.apex.browser.core.CheckpointCandidate
import com.apex.browser.core.Disambiguation
import com.apex.browser.core.TaskActionType
import com.apex.browser.core.TaskCheckpoint
import com.apex.browser.core.TaskOutcome
import com.apex.browser.core.TaskScripts
import com.apex.browser.core.TaskStatus
import com.apex.browser.core.TaskStep
import com.apex.browser.core.TaskStepResult
import com.apex.browser.core.TaskValidator
import com.apex.browser.core.WaitCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.1.0 BrowserTask 执行引擎（评审 A4，P0）：一次提交、事件驱动自主推进、
 * 最后一次性返回压缩结果 —— 把 N 次「模型往返 → 工具执行」压成 1 次。
 *
 * 纪律：
 * - **预校验先于执行**（[TaskValidator]）：任一步不合法即整体拒绝，零副作用；
 * - 步骤按 **CSS selector 直操作**（[TaskScripts.markBySelectorJs] 打 ref），执行复用
 *   既有 ref 硬化路径（clickElement/inputText 的物理触摸+探针+动作后验证）；
 * - **歧义检查点**：selector 命中 >1 且未声明 first → 任务暂停（CHECKPOINT 一等状态），
 *   候选 ref 已打标可直接用于 ref 类工具；确定性连续操作交运行时，判断节点交模型；
 * - 每步前做 [TaskStep.expectedDocId] 校验（不匹配 → STALE_DOCUMENT，绝不操作错页）；
 * - 失败默认停（不承诺回滚）；总超时兜底；人工接管（WAITING_HUMAN）即中止——人工优先；
 * - mode=background：立即回 taskId，任务后台推进，状态经 [taskState] 查询
 *   （模型与浏览器并行工作的落点）。
 */
suspend fun BrowserEngine.runTask(
    steps: List<TaskStep>,
    mode: String = "wait",
    totalTimeoutMs: Long = 90_000,
    maxResultChars: Int = 4000,
): TaskOutcome {
    // (0) 预校验：零副作用承诺的第一半——任何错误在第 1 步前整体拒绝
    val validationErrors = TaskValidator.validate(steps)
    if (validationErrors.isNotEmpty()) {
        return TaskOutcome(
            taskId = "t_invalid", status = TaskStatus.FAILED,
            steps = emptyList(), completedCount = 0, stopped = true,
            result = "任务未执行（预校验失败，零副作用）：\n" + validationErrors.joinToString("\n"),
        )
    }
    // 人工接管 = 人工优先：不排队不执行
    runCatching { assertAgentControl() }.onFailure {
        return TaskOutcome(
            taskId = "t_handoff", status = TaskStatus.CANCELLED,
            steps = emptyList(), completedCount = 0, stopped = true,
            result = "任务未执行：人工接管中（WAITING_HUMAN）。接管结束后重新提交。",
        )
    }

    val runtime = this.runtime
    if (mode == "background") {
        val taskId = runtime.nextTaskId()
        val job = runtime.scope.launch {
            val outcome = runTaskBody(taskId, steps, totalTimeoutMs, maxResultChars)
            runtime.getTask(taskId)?.outcome = outcome
        }
        runtime.putTask(EngineRuntime.TaskEntry(taskId, job, tabId = activeTab()?.id ?: 0))
        return TaskOutcome(
            taskId = taskId, status = TaskStatus.QUEUED,
            steps = emptyList(), completedCount = 0,
            result = "任务已提交后台（taskId=$taskId）。用 BrowserDocState(navigation_id=\"$taskId\") 查询进度与结果。",
        )
    }

    val taskId = runtime.nextTaskId()
    val outcome = runTaskBody(taskId, steps, totalTimeoutMs, maxResultChars)
    runtime.putTask(EngineRuntime.TaskEntry(taskId, kotlinx.coroutines.Job(), tabId = activeTab()?.id ?: 0, outcome = outcome))
    return outcome
}

/** 任务体（wait/background 共用）：逐步执行 + 检查点 + 超时/取消折叠。 */
private suspend fun BrowserEngine.runTaskBody(
    taskId: String,
    steps: List<TaskStep>,
    totalTimeoutMs: Long,
    maxResultChars: Int,
): TaskOutcome {
    val startedAt = System.currentTimeMillis()
    val stepResults = mutableListOf<TaskStepResult>()
    var finalDocId: String? = null
    var lastExtract: String? = null

    suspend fun buildOutcome(
        status: TaskStatus,
        checkpoint: TaskCheckpoint? = null,
        stopped: Boolean = true,
        failedIndex: Int? = null,
        tail: String = "",
    ): TaskOutcome {
        val summary = lastExtract?.take(maxResultChars)
            ?: tail.ifBlank { "已完成 ${stepResults.size}/${steps.size} 步" }
        return TaskOutcome(
            taskId = taskId, status = status, steps = stepResults.toList(),
            completedCount = stepResults.count { it.ok },
            failedIndex = failedIndex, stopped = stopped,
            finalDocId = finalDocId, checkpoint = checkpoint,
            result = summary, elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    try {
        withTimeout(totalTimeoutMs.coerceIn(5_000, 600_000)) {
            for ((index, step) in steps.withIndex()) {
                // 每步前重查人工接管：接管即中止（评审「接管时在途任务 SUPERSEDED」口径）
                runCatching { assertAgentControl() }.onFailure {
                    stepResults += TaskStepResult(index, step.type, false, "人工接管中，任务中止（WAITING_HUMAN）")
                    return@withTimeout buildOutcome(
                        TaskStatus.CANCELLED,
                        tail = "人工接管中，任务在第 ${index + 1} 步前中止",
                        failedIndex = index,
                    )
                }
                val stepStart = SystemClock.uptimeMillis()
                val exec = executeStep(step, index)
                val res = when (exec) {
                    is StepExec.Done -> TaskStepResult(
                        index, step.type, exec.ok, exec.detail,
                        docId = exec.docId ?: activeDocId(), elapsedMs = SystemClock.uptimeMillis() - stepStart,
                    )
                    is StepExec.CheckpointHit -> {
                        stepResults += TaskStepResult(
                            index, step.type, false,
                            "检查点：selector「${step.selector}」命中 ${exec.candidates.size} 个元素",
                            docId = exec.docId, elapsedMs = SystemClock.uptimeMillis() - stepStart,
                        )
                        return@withTimeout buildOutcome(
                            TaskStatus.CHECKPOINT,
                            checkpoint = TaskCheckpoint(
                                stepIndex = index, selector = step.selector,
                                candidates = exec.candidates, remainingSteps = steps.size - index - 1,
                            ),
                        )
                    }
                }
                stepResults += res
                res.docId?.let { finalDocId = it }
                if (!res.ok) {
                    return@withTimeout buildOutcome(
                        TaskStatus.FAILED,
                        failedIndex = index,
                        tail = "第 ${index + 1} 步失败：${res.detail}",
                    )
                }
                if (step.type == TaskActionType.EXTRACT && exec is StepExec.Done && exec.ok) {
                    lastExtract = exec.detail.takeIf { step.extractMode != "meta" } ?: lastExtract
                }
            }
            buildOutcome(TaskStatus.COMPLETED, stopped = false)
        }
    } catch (e: TimeoutCancellationException) {
        return buildOutcome(TaskStatus.TIMEOUT, tail = "任务总超时（${totalTimeoutMs}ms），已完成部分如实上报")
    } catch (e: CancellationException) {
        // 后台任务被取消（BrowserDocControl/引擎释放）——折叠为 CANCELLED 结果而非异常
        return buildOutcome(TaskStatus.CANCELLED, tail = "任务被取消")
    } catch (e: Exception) {
        return buildOutcome(TaskStatus.FAILED, tail = "任务执行异常：${e.message?.take(160)}")
    }
}

/** 步骤执行结果：完成（含失败）或检查点暂停。 */
private sealed interface StepExec {
    data class Done(val ok: Boolean, val detail: String, val docId: String? = null) : StepExec
    data class CheckpointHit(val candidates: List<CheckpointCandidate>, val docId: String?) : StepExec
}

private val taskJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 单步执行（selector 定位复用 ref 硬化路径；每步过 TabWorker 串行锁）。 */
private suspend fun BrowserEngine.executeStep(step: TaskStep, index: Int): StepExec =
    when (step.type) {
        TaskActionType.NAVIGATE -> execNavigateStep(step, index)
        TaskActionType.WAIT_FOR -> execWaitStep(step)
        TaskActionType.CLICK -> execMarkThenRef(step, index, isFill = false)
        TaskActionType.FILL -> execMarkThenRef(step, index, isFill = true)
        TaskActionType.PRESS_KEY -> {
            val st = pressKey(step.key)
            StepExec.Done(st.success, "press_key ${step.key}：${st.toText().take(120)}")
        }
        TaskActionType.SCROLL -> {
            if (step.deltaY == 0) {
                val ok = scrollToBoundary(toBottom = true)
                StepExec.Done(ok, if (ok) "已滚动到底部" else "无法滚动（无激活标签页）")
            } else {
                val r = scroll(step.deltaY, false)
                StepExec.Done(true, "已滚动 ${step.deltaY}px，新增元素 ${r.newElementsDetected}")
            }
        }
        TaskActionType.EXTRACT -> execExtractStep(step)
    }

private suspend fun BrowserEngine.execNavigateStep(step: TaskStep, index: Int): StepExec {
    val handle = navigateAsync(
        url = step.url,
        timeoutMs = if (step.timeoutMs > 0) step.timeoutMs else 15_000,
        waitUntil = "dom_ready",
    )
    val st = runtime.navigation.awaitNavigation(handle.navigationId, if (step.timeoutMs > 0) step.timeoutMs else 15_000) {
        it.readinessStatus in com.apex.browser.core.READINESS_SETTLED || it.isTerminal
    } ?: return StepExec.Done(false, "导航状态丢失（handle 被释放）")
    return when {
        st.isTerminal -> StepExec.Done(false, "导航终态 ${st.navigationStatus}：${st.detail ?: ""}")
        st.readinessStatus == com.apex.browser.core.ReadinessStatus.TIMEOUT ->
            StepExec.Done(false, "页面加载超时（${step.timeoutMs}ms 内未到 DOM_READY）")
        else -> StepExec.Done(true, "已打开 ${step.url}（docId=${st.docId ?: "-"}）", docId = st.docId)
    }
}

private suspend fun BrowserEngine.execWaitStep(step: TaskStep): StepExec {
    val tab = activeTab() ?: return StepExec.Done(false, "无激活标签页")
    val condition = WaitCondition(
        mode = step.waitMode, value = step.waitValue,
        timeoutMs = if (step.timeoutMs > 0) step.timeoutMs else 10_000,
    )
    val o = runtime.hubFor(tab.id).await(tab, condition)
    val docId = runtime.docIdOf(tab)
    return when {
        o.matched -> StepExec.Done(true, "✅ ${o.detail}", docId)
        o.notDetected -> StepExec.Done(false, "network_idle 无法判定（${o.detail}）", docId)
        else -> StepExec.Done(false, o.detail, docId)
    }
}

/** selector 定位 →（歧义分支）→ 既有 ref 执行路径（click/inputText 的物理触摸+验证）。 */
private suspend fun BrowserEngine.execMarkThenRef(step: TaskStep, index: Int, isFill: Boolean): StepExec {
    val tab = activeTab() ?: return StepExec.Done(false, "无激活标签页")
    // STALE_DOCUMENT 前置校验（评审 P1-1 闭环）
    val currentDocId = runtime.docIdOf(tab)
    if (step.expectedDocId.isNotBlank() && currentDocId != step.expectedDocId) {
        return StepExec.Done(
            false,
            "STALE_DOCUMENT: expected ${step.expectedDocId}, current ${currentDocId ?: "none"} — 请重新 BrowserSnapshot",
            currentDocId,
        )
    }
    val raw = withContext(kotlinx.coroutines.Dispatchers.Main) {
        evaluateJson(tab.webView, TaskScripts.markBySelectorJs(step.selector, limit = 8))
    }
    val parsed = runCatching { taskJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return StepExec.Done(false, "selector 定位结果解析失败", currentDocId)
    if (parsed.containsKey("error")) {
        return StepExec.Done(false, "selector 语法非法（CSS 解析失败）", currentDocId)
    }
    val matches = parsed["matches"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val elements = parsed["elements"]?.jsonArray
    if (matches == 0 || elements == null || elements.isEmpty()) {
        return StepExec.Done(
            false,
            "selector「${step.selector}」未命中可见元素（可用 BrowserLocate 按文本重锚定或换更宽选择器）",
            currentDocId,
        )
    }
    val candidates = elements.mapNotNull { el ->
        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        CheckpointCandidate(
            ref = o["ref"]?.jsonPrimitive?.content ?: "",
            tag = o["tag"]?.jsonPrimitive?.content ?: "",
            text = o["text"]?.jsonPrimitive?.content ?: "",
            x = o["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            y = o["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            width = o["w"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            height = o["h"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }
    if (candidates.isEmpty()) return StepExec.Done(false, "候选解析失败", currentDocId)
    if (candidates.size > 1 && step.disambiguation == Disambiguation.CHECKPOINT) {
        return StepExec.CheckpointHit(candidates, currentDocId)
    }
    // first（显式声明或单命中）→ 走既有 ref 执行路径
    val target = candidates.first()
    val st = if (isFill) {
        // inputText 内建 append/pressEnter 语义（与 BrowserInput 工具同通道）
        inputText(target.ref, step.text, append = step.append, pressEnter = step.pressEnter)
    } else {
        clickElement(target.ref)
    }
    val verb = if (isFill) "fill" else "click"
    return StepExec.Done(
        st.success,
        "$verb [${target.ref}] <${target.tag}>：${st.toText().take(140)}" +
            if (candidates.size > 1) "（多命中 ${candidates.size} 个，按 disambiguation=first 取第一个）" else "",
        runtime.docIdOf(activeTab() ?: tab),
    )
}

private suspend fun BrowserEngine.execExtractStep(step: TaskStep): StepExec {
    val tab = activeTab() ?: return StepExec.Done(false, "无激活标签页")
    val mode = when (step.extractMode) {
        "tables" -> BrowserScript.ExtractMode.TABLES
        "links" -> BrowserScript.ExtractMode.LINKS
        "meta" -> BrowserScript.ExtractMode.META
        else -> BrowserScript.ExtractMode.ARTICLE
    }
    val raw = if (step.extractMode == "text") {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            evaluateJson(tab.webView, TaskScripts.extractSubtreeTextJs(step.selector))
        }
    } else {
        extractContent(mode) ?: return StepExec.Done(false, "抽取失败（页面尚未加载完成）", runtime.docIdOf(tab))
    }
    val text = raw.ifBlank { return StepExec.Done(false, "该模式下页面无可用内容", runtime.docIdOf(tab)) }
    return StepExec.Done(true, text.take(2000), runtime.docIdOf(tab))
}

// ───────── 后台任务查询 / 控制 ─────────

/**
 * 查询任务状态（后台任务的进度/检查点/最终结果）。未知 id 返回 null；
 * 已完成任务的 outcome 常驻可查（TTL 由运行时 LRU 淘汰）。
 */
fun BrowserEngine.taskState(taskId: String): TaskOutcome? = runtime.getTask(taskId)?.outcome

/** 取消后台任务（已终态返回 false）。 */
fun BrowserEngine.cancelTask(taskId: String, reason: String = "cancelled"): Boolean {
    val entry = runtime.getTask(taskId) ?: return false
    if (entry.outcome != null) return false
    entry.job.cancel(CancellationException(reason))
    return true
}
