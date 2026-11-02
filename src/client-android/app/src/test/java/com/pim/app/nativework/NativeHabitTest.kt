package com.pim.app.nativework

import com.pim.core.contract.HabitRoutineDto
import com.pim.core.contract.HabitRoutineDtoApiResponse
import com.pim.core.contract.HabitRoutineDtoIReadOnlyListApiResponse
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeHabitTest {
    @Test
    fun projectionKeepsOnlyBoundedUserFields() {
        val result = nativeHabit(
            habit(
                title = "  每日\n阅读  ",
                description = "private description",
                source = "third-party-account",
                status = "active\nunsafe value",
            )
        )

        assertEquals("每日 阅读", result.title)
        assertEquals("active unsafe value", result.status)
        assertEquals("每周", habitCadenceLabel(result.cadence))
        assertFalse(result.toString().contains("private description"))
        assertFalse(result.toString().contains("third-party-account"))
    }

    @Test
    fun repositoryUsesOnlyContractListAndArchiveOperations() = runBlocking {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = fakeApi { method, args ->
            calls += (method to args.dropLast(1))
            when (method) {
                "getHabits" -> HabitRoutineDtoIReadOnlyListApiResponse(
                    code = 200,
                    data = listOf(habit()),
                    message = "ok",
                    timestamp = "now",
                )
                "archiveHabit" -> HabitRoutineDtoApiResponse(
                    code = 200,
                    data = habit(status = "archived"),
                    message = "ok",
                    timestamp = "now",
                )
                else -> error("Unexpected $method")
            }
        }
        val repository = NativeWorkbenchRepository(api)

        val habits = repository.loadHabits()
        val archived = repository.archiveHabit("habit-1")

        assertEquals(listOf("habit-1"), habits.map { it.id })
        assertEquals("archived", archived.status)
        assertEquals(
            listOf(
                "getHabits" to emptyList<Any?>(),
                "archiveHabit" to listOf("habit-1"),
            ),
            calls,
        )
    }

    private fun fakeApi(handler: (String, List<Any?>) -> Any): ApiService =
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "toString" -> "NativeHabitFakeApi"
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                else -> handler(method.name, args?.toList().orEmpty())
            }
        } as ApiService

    private fun habit(
        title: String = "晨间阅读",
        description: String? = null,
        source: String = "native",
        status: String = "active",
    ) = HabitRoutineDto(
        cadence = 1,
        description = description,
        id = "habit-1",
        source = source,
        status = status,
        title = title,
    )
}
