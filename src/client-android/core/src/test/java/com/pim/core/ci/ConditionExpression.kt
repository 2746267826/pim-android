package com.pim.core.ci

/**
 * GitHub Actions 表达式的一个**可判定子集**求值器。
 *
 * 为什么需要它：release / gate 的 `if` 条件不能靠「字符串里出现了 'skipped'」判定（I-2），
 * 必须对 `needs` 结果做**真实语义求值**，这样 `needs: [gate]` + `needs.gate.result == 'success'`
 * 这种与字面量写法语义等价的合规实现才会被承认。
 *
 * 支持（本仓 workflow 实际用到的子集）：
 * - 字面量：`'字符串'`、`true`/`false`、数字；
 * - 属性链：`needs.build-android.result`、`needs.*.result`、`inputs.require_signing`、`github.*`；
 * - 运算符：`!` `&&` `||` `==` `!=` `<` `<=` `>` `>=`，括号；
 * - 函数：`always()`、`success()`、`failure()`、`cancelled()`、`contains(a, b)`、
 *   `startsWith`、`endsWith`、`join`。
 *
 * 不支持的一律**抛错**而不是静默返回 null —— 静默兜底会制造假绿。
 */
object ConditionExpression {

    private sealed interface Node {
        data class Literal(val value: Any?) : Node
        data class Path(val path: String) : Node
        data class Call(val name: String, val args: List<Node>) : Node
        data class Not(val inner: Node) : Node
        data class Binary(val op: String, val left: Node, val right: Node) : Node
    }

    fun evaluate(expression: String, context: Map<String, Any?>): Any? =
        eval(parseExpression(expression), context)

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> true
        else -> true
    }

    // ---------- parser ----------

    private class Parser(private val tokens: List<String>) {
        private var index = 0

        fun parse(): Node {
            val node = parseOr()
            if (index != tokens.size) error("表达式有未解析的剩余 token：${tokens.drop(index)}（原始：${tokens.joinToString(" ")}）")
            return node
        }

        private fun peek(): String? = tokens.getOrNull(index)

        private fun next(): String = tokens.getOrElse(index++) { error("表达式意外结束") }

        private fun expect(token: String) {
            val actual = next()
            if (actual != token) error("期望 $token，实际 $actual")
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (peek() == "||") {
                next()
                left = Node.Binary("||", left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Node {
            var left = parseUnary()
            while (peek() == "&&") {
                next()
                left = Node.Binary("&&", left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Node {
            if (peek() == "!") {
                next()
                return Node.Not(parseUnary())
            }
            return parseComparison()
        }

        private fun parseComparison(): Node {
            val left = parsePrimary()
            val op = peek() ?: return left
            if (op in COMPARISONS) {
                next()
                return Node.Binary(op, left, parsePrimary())
            }
            return left
        }

        private fun parsePrimary(): Node {
            val token = next()
            return when {
                token == "(" -> {
                    val inner = parseOr()
                    expect(")")
                    inner
                }
                token.startsWith("'") -> Node.Literal(unquote(token))
                token == "true" -> Node.Literal(true)
                token == "false" -> Node.Literal(false)
                token.toDoubleOrNull() != null -> Node.Literal(token.toDouble())
                peek() == "(" -> {
                    next()
                    val args = mutableListOf<Node>()
                    if (peek() != ")") {
                        args += parseOr()
                        while (peek() == ",") {
                            next()
                            args += parseOr()
                        }
                    }
                    expect(")")
                    Node.Call(token, args)
                }
                else -> Node.Path(token)
            }
        }

        companion object {
            private val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=")
            private fun unquote(token: String): String {
                val body = token.removeSurrounding("'")
                return body.replace("''", "'")
            }
        }
    }

    private fun parseExpression(expression: String): Node = Parser(tokenize(expression)).parse()

    private fun tokenize(expression: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' -> {
                    val sb = StringBuilder("'")
                    i++
                    while (i < expression.length) {
                        if (expression[i] == '\'') {
                            if (i + 1 < expression.length && expression[i + 1] == '\'') {
                                sb.append("''")
                                i += 2
                                continue
                            }
                            sb.append('\'')
                            i++
                            break
                        }
                        sb.append(expression[i])
                        i++
                    }
                    tokens += sb.toString()
                }
                c == '(' || c == ')' || c == ',' -> {
                    tokens += c.toString()
                    i++
                }
                c == '!' || c == '=' || c == '<' || c == '>' -> {
                    val two = expression.substring(i, minOf(i + 2, expression.length))
                    if (two == "==" || two == "!=" || two == "<=" || two == ">=") {
                        tokens += two
                        i += 2
                    } else {
                        tokens += c.toString()
                        i++
                    }
                }
                c == '&' || c == '|' -> {
                    val two = expression.substring(i, minOf(i + 2, expression.length))
                    require(two == "&&" || two == "||") { "不支持的运算符：$two（表达式：$expression）" }
                    tokens += two
                    i += 2
                }
                else -> {
                    val start = i
                    while (i < expression.length && expression[i] !in " \t\r\n()!<>=,&|'") i++
                    tokens += expression.substring(start, i)
                }
            }
        }
        return tokens
    }

    // ---------- evaluator ----------

    private fun eval(node: Node, context: Map<String, Any?>): Any? = when (node) {
        is Node.Literal -> node.value
        is Node.Path -> resolve(node.path, context)
        is Node.Not -> !truthy(eval(node.inner, context))
        is Node.Binary -> evalBinary(node, context)
        is Node.Call -> evalCall(node, context)
    }

    private fun resolve(path: String, context: Map<String, Any?>): Any? {
        if (context.containsKey(path)) return context[path]
        // 未登记的属性链返回 null（GitHub 语义）；但带通配的 `needs.*.result` 必须显式提供，
        // 否则会静默变成 null 造成假绿。
        require(!path.contains('*')) { "表达式引用了未提供的通配属性：$path" }
        return null
    }

    private fun evalBinary(node: Node.Binary, context: Map<String, Any?>): Any? = when (node.op) {
        "&&" -> if (truthy(eval(node.left, context))) eval(node.right, context) else eval(node.left, context)
        "||" -> {
            val left = eval(node.left, context)
            if (truthy(left)) left else eval(node.right, context)
        }
        "==" -> looseEquals(eval(node.left, context), eval(node.right, context))
        "!=" -> !looseEquals(eval(node.left, context), eval(node.right, context))
        "<", "<=", ">", ">=" -> {
            val left = toNumber(eval(node.left, context), node.op)
            val right = toNumber(eval(node.right, context), node.op)
            when (node.op) {
                "<" -> left < right
                "<=" -> left <= right
                ">" -> left > right
                else -> left >= right
            }
        }
        else -> error("不支持的运算符：${node.op}")
    }

    private fun evalCall(node: Node.Call, context: Map<String, Any?>): Any? = when (node.name) {
        "always" -> true
        "success" -> context["success()"] ?: true
        "failure" -> context["failure()"] ?: false
        "cancelled" -> context["cancelled()"] ?: false
        "contains" -> {
            val haystack = eval(node.args[0], context)
            val needle = eval(node.args[1], context)
            when (haystack) {
                is Collection<*> -> haystack.any { looseEquals(it, needle) }
                is String -> haystack.contains(needle?.toString() ?: "")
                else -> false
            }
        }
        "startsWith" -> (eval(node.args[0], context) as? String)
            ?.startsWith(eval(node.args[1], context)?.toString() ?: "") ?: false
        "endsWith" -> (eval(node.args[0], context) as? String)
            ?.endsWith(eval(node.args[1], context)?.toString() ?: "") ?: false
        "join" -> {
            val list = eval(node.args[0], context)
            val sep = if (node.args.size > 1) eval(node.args[1], context)?.toString() ?: "," else ","
            (list as? Collection<*>)?.joinToString(sep) { it?.toString() ?: "" } ?: ""
        }
        else -> error("不支持的表达式函数：${node.name}()")
    }

    private fun looseEquals(left: Any?, right: Any?): Boolean {
        if (left == null || right == null) return left == right
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        if (left is Boolean || right is Boolean) {
            val l = if (left is Boolean) left else truthy(left)
            val r = if (right is Boolean) right else truthy(right)
            return l == r
        }
        if (left is String || right is String) return left.toString() == right.toString()
        return left == right
    }

    private fun toNumber(value: Any?, op: String): Double =
        when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
                ?: error("$op 需要数字，实际字符串：'$value'")
            else -> error("$op 需要数字，实际：$value")
        }
}
