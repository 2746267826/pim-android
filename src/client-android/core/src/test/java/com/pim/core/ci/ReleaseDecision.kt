package com.pim.core.ci

/**
 * REQ-COM-04 的**语义**判据：release 只在所有必需 job 都成功时才能启动
 * （failure / cancelled / skipped 一律不可发布）。
 *
 * 与旧判据的区别（I-2）：旧实现用正则抓 `if` 里的引号字面量，要求同时出现
 * `failure`/`cancelled`/`skipped` 三个词，导致语义等价的
 * `needs: [gate]` + `needs.gate.result == 'success'` 被判红。本实现改为：
 *
 * 1. 按真实 job 依赖图（`needs` 闭包）拓扑求值；
 * 2. 对闭包里**每个** job 分别喂入 `failure` / `cancelled` / `skipped` 三种坏结果
 *    （其余 success），算出每个 job 的真实结果（`if` 求值 + 聚合 job 的脚本语义）；
 * 3. 要求这些坏场景下 release 的 `if` 都必须为 false；
 * 4. 反向守卫：全部 success 且 `is_release=true` 时 release 必须为 true
 *    （防止「条件恒假」也被判通过）。
 */
object ReleaseDecision {

    val BAD_RESULTS = listOf("failure", "cancelled", "skipped")

    data class Scenario(
        val brokenJob: String,
        val brokenResult: String,
        val jobResults: Map<String, String>,
        val releaseRuns: Boolean,
    )

    const val DEFAULT_RELEASE_JOB = "release"

    fun scenarios(workflow: Workflow, releaseJobId: String = DEFAULT_RELEASE_JOB): List<Scenario> {
        val closure = workflow.transitiveNeeds(releaseJobId).toList()
        return closure.flatMap { job ->
            BAD_RESULTS.map { bad -> scenario(workflow, releaseJobId, closure, job, bad) }
        }
    }

    fun allSuccessScenario(workflow: Workflow, releaseJobId: String = DEFAULT_RELEASE_JOB): Scenario {
        val closure = workflow.transitiveNeeds(releaseJobId).toList()
        return scenario(workflow, releaseJobId, closure, brokenJob = "", brokenResult = "success")
    }

    private fun scenario(
        workflow: Workflow,
        releaseJobId: String,
        closure: List<String>,
        brokenJob: String,
        brokenResult: String,
    ): Scenario {
        val order = topological(workflow, closure)
        val results = linkedMapOf<String, String>()
        for (jobId in order) {
            val job = workflow.job(jobId) ?: continue
            val assigned = if (jobId == brokenJob) brokenResult else "success"
            results[jobId] = derive(job, assigned, results)
        }
        val release = workflow.job(releaseJobId)!!
        val context = needsContext(release.needs, results) + releaseOutputs()
        val runs = release.condition?.let {
            ConditionExpression.truthy(ConditionExpression.evaluate(it, context))
        } ?: release.needs.all { results[it] == "success" }
        return Scenario(brokenJob, brokenResult, results, runs)
    }

    private fun derive(
        job: WorkflowJob,
        assigned: String,
        results: Map<String, String>,
    ): String {
        val condition = job.condition
        if (condition != null) {
            val context = needsContext(job.needs, results) + releaseOutputs()
            if (!ConditionExpression.truthy(ConditionExpression.evaluate(condition, context))) {
                return "skipped"
            }
        } else if (job.needs.any { results[it] != "success" }) {
            return "skipped"
        }
        if (assigned != "success") return assigned
        if (job.isRequiredAllAggregator()) {
            // M-G：逐 need 建模 —— 只有**被真正消费**的 need 才会影响聚合结果；
            // 未消费的 need 失败时 gate 仍为 success（= gate 不会拦住它），由 problems() 单独诊断。
            val consumed = job.consumedNeeds()
            return if (consumed.all { results[it] == "success" }) "success" else "failure"
        }
        return "success"
    }

    private fun topological(workflow: Workflow, ids: List<String>): List<String> {
        val remaining = ids.toMutableList()
        val done = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                (workflow.job(id)?.needs ?: emptyList()).all { it in done }
            }
            require(ready.isNotEmpty()) { "job 依赖图存在环：$remaining" }
            done += ready
            remaining -= ready.toSet()
        }
        return done
    }

    /** fixture 上下文：resolve-version 输出（含 is_release）、触发上下文，供 `needs.<job>.outputs.*` 求值。 */
    fun releaseOutputs(
        version: String = "26.10.16",
        versionCode: String = "100016",
        artifactSlug: String = "26.10.16",
        releaseTag: String = "android-v26.10.16",
        isRelease: String = "true",
        ref: String = "refs/heads/master",
        eventName: String = "push",
    ): Map<String, Any?> = mapOf(
        "needs.resolve-version.outputs.version" to version,
        "needs.resolve-version.outputs.version_code" to versionCode,
        "needs.resolve-version.outputs.artifact_slug" to artifactSlug,
        "needs.resolve-version.outputs.release_tag" to releaseTag,
        "needs.resolve-version.outputs.is_release" to isRelease,
        "github.ref" to ref,
        "github.event_name" to eventName,
    )

    /** 语义判据：返回问题列表（空 = 合规）。 */
    fun problems(workflow: Workflow, releaseJobId: String = DEFAULT_RELEASE_JOB): List<String> {
        val release = workflow.job(releaseJobId)
            ?: return listOf("ci.yml 里找不到 $releaseJobId 作业")
        val problems = mutableListOf<String>()
        if (release.needs.isEmpty()) problems += "$releaseJobId 作业没有声明 needs"
        val condition = release.condition
        if (condition.isNullOrBlank()) {
            problems += "$releaseJobId 作业没有 if 条件（会无条件发版）"
            return problems
        }
        // M-G：逐 need 诊断「汇总作业没有消费哪个 need」，而不是 all-or-nothing 地报一堆连带失败。
        for (aggregator in workflow.jobs.filter { it.isRequiredAllAggregator() }) {
            val unconsumed = aggregator.needs.filterNot { it in aggregator.consumedNeeds() }.sorted()
            if (unconsumed.isNotEmpty()) {
                problems += "汇总作业 `${aggregator.name ?: aggregator.id}` 没有消费 need $unconsumed 的结果：" +
                    "这些 job 失败/取消/被跳过时不会被它拦住（列在 needs 里不等于被消费）。"
            }
        }
        for (scenario in scenarios(workflow, releaseJobId)) {
            if (scenario.releaseRuns) {
                problems += "必要性 job ${scenario.brokenJob}=${scenario.brokenResult} 时 " +
                    "$releaseJobId 的 if 仍求值为 true（求值结果：" +
                    scenario.jobResults.entries.joinToString(", ") { "${it.key}=${it.value}" } +
                    "）。\n语义判据拒绝：failure/cancelled/skipped 一律不可发布（REQ-COM-04）。\nif: $condition"
            }
        }
        val allSuccess = allSuccessScenario(workflow, releaseJobId)
        if (!allSuccess.releaseRuns) {
            problems += "反向守卫失败：全部 job success 且 is_release=true 时 $releaseJobId 仍不运行，" +
                "条件恒假会把「不可发布」伪装成合规。\nif: $condition"
        }
        return problems
    }
}
