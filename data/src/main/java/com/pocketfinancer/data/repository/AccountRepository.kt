package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository (
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    runConsolidationOnInit: Boolean = true
) {
    private val accountMutationMutex = Mutex()
    // Construction cannot suspend. Production setup therefore runs lazily
    // inside the first account-producing call's mutation boundary.
    private var initialConsolidationComplete = !runConsolidationOnInit

    @Inject
    constructor(
        accountDao: AccountDao,
        transactionDao: TransactionDao
    ) : this(accountDao, transactionDao, runConsolidationOnInit = true)

    suspend fun consolidateAccounts() = accountMutationMutex.withLock {
        consolidateAccountsLocked()
        initialConsolidationComplete = true
    }

    private suspend fun consolidateAccountsLocked() {
        val allAccounts = accountDao.getAllOnce()
        val sourceGroups = allAccounts
            .mapNotNull { account ->
                normalizeAccountName(account.name)?.let { identity ->
                    identity to account
                }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            )

        for ((_, list) in sourceGroups) {
            if (list.size <= 1) continue

            val (unknowns, knowns) = list.partition {
                isUnknownBank(it.bank)
            }

            val knownBankGroups = knowns.groupBy {
                normalizedBank(it.bank)
            }
            val knownCanonicals = knownBankGroups.values.map { bankAccounts ->
                bankAccounts.first().also { canonical ->
                    mergeAccountsLocked(
                        canonical = canonical,
                        duplicates = bankAccounts.drop(1)
                    )
                }
            }

            if (knownCanonicals.size == 1) {
                mergeAccountsLocked(
                    canonical = knownCanonicals.single(),
                    duplicates = unknowns
                )
            } else if (unknowns.size > 1) {
                mergeAccountsLocked(
                    canonical = unknowns.first(),
                    duplicates = unknowns.drop(1)
                )
            }
        }
    }

    private suspend fun mergeAccountsLocked(
        canonical: AccountEntity,
        duplicates: List<AccountEntity>
    ) {
        duplicates.forEach { duplicate ->
            transactionDao.updateTransactionsAccount(
                duplicate.id,
                canonical.id
            )
            accountDao.delete(duplicate.id)
        }
    }

    private suspend fun completeInitialConsolidationLocked() {
        if (initialConsolidationComplete) return
        consolidateAccountsLocked()
        initialConsolidationComplete = true
    }

    internal suspend fun ensureInitialConsolidation() =
        accountMutationMutex.withLock {
            completeInitialConsolidationLocked()
        }

    fun getAll(): Flow<List<Account>> = flow {
        ensureInitialConsolidation()
        emitAll(
            accountDao.getAll().map { list ->
                list.map { it.toDomain() }
            }
        )
    }

    suspend fun getById(id: String): Account? =
        accountDao.getById(id)?.toDomain()

    suspend fun getAllOnce(): List<Account> =
        accountDao.getAllOnce().map { it.toDomain() }

    /** Legacy explicit import helper; never used by SMS processing. */
    @Deprecated("SMS processing must resolve an existing confirmed account")
    suspend fun getOrCreate(
        name: String,
        bank: String,
        type: String
    ): Account = accountMutationMutex.withLock {
        completeInitialConsolidationLocked()
        getOrCreateLocked(name = name, bank = bank, type = type)
    }

    private suspend fun getOrCreateLocked(
        name: String,
        bank: String,
        type: String
    ): Account {
        val finalBank = if (isUnknownBank(bank)) {
            inferBankFromName(name)
        } else {
            bank
        }

        // Try to normalize account suffix digits
        val normalizedPair = normalizeAccountName(name)
        if (normalizedPair != null) {
            val (category, digits) = normalizedPair
            val capCategory = if (category == "card") "Card" else "A/c"
            val bankPrefix = if (isUnknownBank(finalBank)) "" else "$finalBank "
            val normalizedName = "$bankPrefix$capCategory XX$digits"

            // Match the normalized category/suffix and a compatible bank.
            val allAccounts = accountDao.getAllOnce()
            val sameSourceAccounts = allAccounts.filter { account ->
                normalizeAccountName(account.name) == normalizedPair
            }
            val existing = findCompatibleAccount(
                accounts = sameSourceAccounts,
                requestedBank = finalBank
            )
            if (existing != null) {
                return existing.toDomain()
            }

            // Create new normalized account
            val entity = AccountEntity(
                id = UUID.randomUUID().toString(),
                name = normalizedName,
                bank = finalBank,
                type = type
            )
            accountDao.insert(entity)
            return entity.toDomain()
        }

        val existing = accountDao.findByNameAndBank(name, finalBank)
        if (existing != null) return existing.toDomain()

        val entity = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            bank = finalBank,
            type = type
        )
        accountDao.insert(entity)
        return entity.toDomain()
    }

    private fun normalizeAccountName(
        account: String
    ): NormalizedAccountIdentity? {
        if (account.isBlank()) return null
        val category = if (account.contains("card", ignoreCase = true)) "card" else "account"
        val runs = Regex("\\d+").findAll(account).toList()
        val longRuns = runs.filter { it.value.length >= 3 }
        if (longRuns.isEmpty()) return null
        val lastDigits = longRuns.last().value.takeLast(4)
        return NormalizedAccountIdentity(category, lastDigits)
    }

    private fun inferBankFromName(name: String): String {
        val upper = name.uppercase()
        return when {
            upper.contains("HDFC") -> "HDFC Bank"
            upper.contains("AXIS") -> "Axis Bank"
            upper.contains("ICICI") -> "ICICI Bank"
            upper.contains("SBI") -> "State Bank of India"
            upper.contains("KOTAK") -> "Kotak Bank"
            else -> "Unknown Account"
        }
    }

    /** Legacy explicit import helper; never used by SMS processing. */
    @Deprecated("Unknown accounts must remain unresolved and enter review")
    suspend fun ensureDefault(): Account = accountMutationMutex.withLock {
        completeInitialConsolidationLocked()
        val name = "__UNKNOWN__"
        val bank = "Unknown Account"
        val type = "auto-extracted"

        val existing = accountDao.findByNameAndBank(name, bank)
            ?: accountDao.findByNameAndBank(name, "Unknown Bank")
        if (existing != null) return@withLock existing.toDomain()

        getOrCreateLocked(name, bank, type)
    }

    private fun findCompatibleAccount(
        accounts: List<AccountEntity>,
        requestedBank: String
    ): AccountEntity? {
        val unknown = accounts.firstOrNull { isUnknownBank(it.bank) }
        if (isUnknownBank(requestedBank)) {
            if (unknown != null) return unknown
            val knownBankGroups = accounts
                .filterNot { isUnknownBank(it.bank) }
                .groupBy { normalizedBank(it.bank) }
            return knownBankGroups
                .takeIf { it.size == 1 }
                ?.values
                ?.single()
                ?.first()
        }

        return accounts.firstOrNull {
            normalizedBank(it.bank) == normalizedBank(requestedBank)
        } ?: unknown
    }

    private fun normalizedBank(bank: String): String =
        bank.trim().lowercase(Locale.ROOT)

    private fun isUnknownBank(bank: String): Boolean =
        bank.isBlank() ||
            bank.equals("Unknown Bank", ignoreCase = true) ||
            bank.equals("Unknown Account", ignoreCase = true) ||
            bank.equals("Unknown", ignoreCase = true)

    private data class NormalizedAccountIdentity(
        val category: String,
        val suffix: String
    )

    private fun AccountEntity.toDomain() = Account(
        id = id,
        name = name,
        bank = bank,
        type = type
    )
}
