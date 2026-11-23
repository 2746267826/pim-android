package com.pim.app

/**
 * 标记「只有在真机（或带完整外设的环境）上才有意义」的 instrumented 用例。
 *
 * 为什么需要这个注解：2026-11-23 首次在 GitHub 托管模拟器上跑全量
 * `connectedDebugAndroidTest`（186 个用例），26 个失败。逐条分析后分为三类：
 *
 *  1. 真实设备能力依赖（本注解覆盖）：真实麦克风录音、系统相机、中文输入法/软键盘、
 *     `MOCK_LOCATION` 权限。无头模拟器天然不满足 —— 不是产品 bug。
 *  2. 模拟器时序/屏幕尺寸导致（也应标注）：慢速软件渲染下 Compose 未 idle、
 *     小屏导致元素在视口外。这些在真机上通常通过，但需要单独收敛。
 *  3. 真实缺陷（不应标注）：必须修，不能靠注解掩盖。
 *
 * 用法：
 *  - CI 的 stable 通道排除本注解（`notAnnotation=com.pim.app.RequiresDevice`），
 *    剩余用例必须全绿 —— 这是门禁真正"有牙"的部分。
 *  - 全量通道（label `run-instrumented`）不排除任何用例，用于真机/深度验证。
 *
 * 约定：修好一个就摘掉它的注解；新增仅因环境失败而标注的用例，必须在 PR 里写明理由。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class RequiresDevice
