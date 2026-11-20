package cm.brightky.serviceopskotlin

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

suspend fun <T> concurrentReads(timeoutMs: Long, first: suspend () -> T, second: suspend () -> T): List<T> =
    withTimeout(timeoutMs) {
        coroutineScope {
            val left = async { first() }
            val right = async { second() }
            listOf(left.await(), right.await())
        }
    }
