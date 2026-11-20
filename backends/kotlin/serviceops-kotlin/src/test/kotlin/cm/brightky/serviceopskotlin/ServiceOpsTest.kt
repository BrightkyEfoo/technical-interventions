package cm.brightky.serviceopskotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ServiceOpsTest {
    @Test
    fun `tracks ownership and prevents invalid transitions`() {
        val manager = demoAccounts.values.first { it.role == Role.MANAGER }
        val owner = demoAccounts.values.first { it.role == Role.REQUESTER }
        val technician = demoAccounts.values.first { it.role == Role.TECHNICIAN }
        val open = Intervention.open("  Porte bloquée  ", owner.id, Priority.NORMAL)
        assertEquals("Porte bloquée", open.title)
        assertTrue(open.visibleTo(owner))
        assertFalse(open.visibleTo(technician))
        assertEquals(Fault.ASSIGNMENT_FORBIDDEN,
            assertThrows(DomainFailure::class.java) { open.assign(owner, technician) }.fault)
        val started = open.assign(manager, technician).start(technician)
        assertEquals(Fault.INVALID_REPORT, assertThrows(DomainFailure::class.java) { started.resolve(technician, "court") }.fault)
        assertEquals(Status.CLOSED, started.resolve(technician, "Problème résolu.").close(owner).status)
    }

    @Test
    fun `structured concurrency cancels the other supplier on failure`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                concurrentReads<String>(2000, {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }, {
                    started.await()
                    error("Remote failure")
                })
            }
        }
        assertEquals("Remote failure", failure.message)
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `shared timeout cancels a hanging dependency`() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { concurrentReads<String>(50, { awaitCancellation() }, { "ready" }) }
        }
    }
}
