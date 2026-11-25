package com.pocketfinancer.ui.settings

import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.pipeline.SmsWorkAdmissionPause
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFinancialEraseTest {

    @Test
    fun `view model cancellation cannot publish first run before erase completes`() = runTest {
        val eraseStarted = CompletableDeferred<Unit>()
        val allowEraseToFinish = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        var committed = false

        val job = launch {
            runLocalFinancialEraseCriticalSection(
                beginDurableErase = { actions += "begin" },
                onDurableEraseStarted = { actions += "started" },
                clearEncryptedData = {
                    actions += "clear"
                    eraseStarted.complete(Unit)
                    allowEraseToFinish.await()
                },
                cancelFinancialNotifications = { actions += "notifications" },
                resetInMemoryState = { actions += "memory" },
                commitSetupReset = { actions += "first_run" },
                onCommitted = { committed = true }
            )
        }

        eraseStarted.await()
        job.cancel()
        allowEraseToFinish.complete(Unit)
        job.join()

        assertEquals(
            listOf("begin", "started", "clear", "notifications", "memory", "first_run"),
            actions
        )
        assertTrue(committed)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `ancillary cleanup failure cannot strand final setup reset after database clear`() =
        runTest {
            val actions = mutableListOf<String>()

            val failure = runLocalFinancialEraseCriticalSection(
                beginDurableErase = { actions += "begin" },
                onDurableEraseStarted = { actions += "started" },
                clearEncryptedData = { actions += "clear" },
                cancelFinancialNotifications = {
                    actions += "notifications"
                    error("notification service unavailable")
                },
                resetInMemoryState = { actions += "memory" },
                commitSetupReset = { actions += "first_run" },
                onCommitted = { actions += "committed" }
            )

            assertEquals(
                listOf("begin", "started", "clear", "notifications", "memory", "first_run", "committed"),
                actions
            )
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("notification service"))
        }

    @Test
    fun `process restart completes marked erase before releasing admission`() = runTest {
        val fake = FakeSharedPreferences(
            initialValues = mapOf(
                SetupImportStore.KEY_ONBOARDING_COMPLETED to true,
                "selected_slm_id" to "old-model",
                "onboarding_run_generation" to 4L
            )
        )
        SetupImportStore(fake.preferences, hasSmsPermissions = true)
            .beginLocalFinancialErase(nextRunGeneration = 5L)
        val restartedStore = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        val repository = mockk<TransactionRepository>()
        val storage = mockk<SlmModelStorage>()
        val workController = mockk<SmsWorkController>()
        val admissionPause = mockk<SmsWorkAdmissionPause>()
        val actions = mutableListOf<String>()

        every { workController.pauseAdmissions() } returns admissionPause
        coEvery { admissionPause.cancelPending() } coAnswers { actions += "cancel work" }
        coEvery { repository.clearDatabase() } coAnswers { actions += "clear database" }
        coEvery { admissionPause.release() } coAnswers {
            assertFalse(restartedStore.isLocalFinancialErasePending())
            actions += "release admission"
        }
        every { storage.modelFile(any()) } answers {
            File("build/test-erase-recovery/${System.nanoTime()}", firstArg())
        }
        val recovery = LocalFinancialEraseRecovery(
            setupImportStore = restartedStore,
            transactionRepository = repository,
            modelStorage = storage,
            smsWorkController = workController,
            cancelFinancialNotifications = { actions += "cancel notifications" }
        )

        val result = recovery.recoverIfNeeded()

        assertTrue(result.recovered)
        assertNull(result.cleanupFailure)
        assertEquals(
            listOf("cancel work", "clear database", "cancel notifications", "release admission"),
            actions
        )
        assertFalse(restartedStore.isLocalFinancialErasePending())
        assertEquals(false, fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED])
        assertNull(fake.values["selected_slm_id"])
        assertEquals(5L, fake.values["onboarding_run_generation"])
        assertEquals(SetupImportStatus.NOT_STARTED, restartedStore.state.value.status)
    }

    @Test
    fun `database failure leaves marker for next restart and still releases admission`() = runTest {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        store.beginLocalFinancialErase(nextRunGeneration = 1L)
        val repository = mockk<TransactionRepository>()
        val storage = mockk<SlmModelStorage>()
        val workController = mockk<SmsWorkController>()
        val admissionPause = mockk<SmsWorkAdmissionPause>()

        every { workController.pauseAdmissions() } returns admissionPause
        coEvery { admissionPause.cancelPending() } returns Unit
        coEvery { repository.clearDatabase() } throws IllegalStateException("database unavailable")
        coEvery { admissionPause.release() } returns Unit
        every { storage.modelFile(any()) } answers {
            File("build/test-erase-recovery/${System.nanoTime()}", firstArg())
        }
        var notificationsCancelled = false
        val recovery = LocalFinancialEraseRecovery(
            setupImportStore = store,
            transactionRepository = repository,
            modelStorage = storage,
            smsWorkController = workController,
            cancelFinancialNotifications = { notificationsCancelled = true }
        )

        var failure: Throwable? = null
        try {
            recovery.recoverIfNeeded()
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(store.isLocalFinancialErasePending())
        assertFalse(notificationsCancelled)
        coVerify(exactly = 1) { admissionPause.release() }
    }

    @Test
    fun `startup recovery is a no-op without durable marker`() = runTest {
        val store = SetupImportStore(FakeSharedPreferences().preferences, hasSmsPermissions = true)
        val repository = mockk<TransactionRepository>(relaxed = true)
        val storage = mockk<SlmModelStorage>(relaxed = true)
        val workController = mockk<SmsWorkController>(relaxed = true)
        val recovery = LocalFinancialEraseRecovery(
            setupImportStore = store,
            transactionRepository = repository,
            modelStorage = storage,
            smsWorkController = workController,
            cancelFinancialNotifications = {}
        )

        val result = recovery.recoverIfNeeded()

        assertFalse(result.recovered)
        assertNull(result.cleanupFailure)
        coVerify(exactly = 0) { repository.clearDatabase() }
    }
}
