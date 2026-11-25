package com.pim.app.notes

internal object InkSchemaFixtures {
    fun text(path: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
