package com.pocketfinancer.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.accountDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert, lookup by id, name, and name-plus-bank work accurately`() = runBlocking {
        val account = AccountEntity(
            id = "acct-1",
            name = "A/c XX6254",
            bank = "Synthetic Bank",
            type = "auto-extracted"
        )
        dao.insert(account)

        val byId = dao.getById("acct-1")
        assertNotNull(byId)
        assertEquals("A/c XX6254", byId.name)
        assertEquals("Synthetic Bank", byId.bank)

        assertEquals("Synthetic Bank", dao.findByName("A/c XX6254")?.bank)
        assertNull(dao.findByName("nonexistent"))

        assertNotNull(dao.findByNameAndBank("A/c XX6254", "Synthetic Bank"))
        assertNull(dao.findByNameAndBank("A/c XX6254", "Other Bank"))
    }

    @Test
    fun `getAll emits all inserted accounts`() = runBlocking {
        dao.insert(AccountEntity("a1", "A/c XX1111", "Bank A", "auto-extracted"))
        dao.insert(AccountEntity("a2", "A/c XX2222", "Bank B", "auto-extracted"))

        val all = dao.getAll().first()
        assertEquals(setOf("a1", "a2"), all.map { it.id }.toSet())
    }
}
