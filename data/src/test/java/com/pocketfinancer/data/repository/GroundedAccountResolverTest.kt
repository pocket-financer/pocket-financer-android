package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import com.pocketfinancer.data.db.entity.AccountAliasEntity
import com.pocketfinancer.data.db.entity.AccountEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class GroundedAccountResolverTest {
    private val accountDao = mockk<AccountDao>()
    private val revisionDao = mockk<TransactionRevisionDao>()
    private val resolver = GroundedAccountResolver(accountDao, revisionDao)

    @Test
    fun `null alias returns Missing without touching persistence`() = runTest {
        assertIs<GroundedAccountResolution.Missing>(resolver.resolve(null))
        coVerify(exactly = 0) { revisionDao.findConfirmedAliases(any(), any()) }
    }

    @Test
    fun `masked suffix resolves only a confirmed owned alias with a live account`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("suffix:1234")
        val alias = aliasEntity("alias", "account", aliasHash, "suffix")
        coEvery {
            revisionDao.findConfirmedAliases(aliasHash, GroundedAccountResolver.MATCHING_SCOPE)
        } returns listOf(alias)
        coEvery { accountDao.getById("account") } returns AccountEntity(
            id = "account", name = "A/c XX1234", bank = "Synthetic Bank", type = "manual"
        )

        val result = assertIs<GroundedAccountResolution.UniquelyResolved>(
            resolver.resolve(" account **１２３４ ")
        )

        assertEquals("account", result.accountId)
        assertEquals(aliasHash, result.matchedAliasHash)
        assertEquals("confirmed_owned_account_alias_v1", result.provenance)
    }

    @Test
    fun `VPA reference normalizes to canonical vpa key and resolves uniquely`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("vpa:owner.test@synthbank")
        val alias = aliasEntity("alias-vpa", "account-vpa", aliasHash, "vpa")
        coEvery {
            revisionDao.findConfirmedAliases(aliasHash, GroundedAccountResolver.MATCHING_SCOPE)
        } returns listOf(alias)
        coEvery { accountDao.getById("account-vpa") } returns AccountEntity(
            id = "account-vpa", name = "UPI Wallet", bank = "Synthetic Bank", type = "manual"
        )

        val result = assertIs<GroundedAccountResolution.UniquelyResolved>(
            resolver.resolve("  OWNER.TEST@SYNTHBANK ")
        )

        assertEquals("account-vpa", result.accountId)
        assertEquals(aliasHash, result.matchedAliasHash)
    }

    @Test
    fun `ambiguous source reference fails before alias lookup`() = runTest {
        assertIs<GroundedAccountResolution.Unresolved>(
            resolver.resolve("accounts **1234 and **5678")
        )
        assertIs<GroundedAccountResolution.Unresolved>(resolver.resolve("   "))
        coVerify(exactly = 0) { revisionDao.findConfirmedAliases(any(), any()) }
        coVerify(exactly = 0) { accountDao.getById(any()) }
    }

    @Test
    fun `orphaned confirmed alias pointing to deleted account remains unresolved`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("suffix:9876")
        val alias = aliasEntity("alias-orphan", "deleted-account", aliasHash, "suffix")
        coEvery {
            revisionDao.findConfirmedAliases(aliasHash, GroundedAccountResolver.MATCHING_SCOPE)
        } returns listOf(alias)
        coEvery { accountDao.getById("deleted-account") } returns null

        assertIs<GroundedAccountResolution.Unresolved>(resolver.resolve("XX9876"))
    }

    @Test
    fun `multiple live accounts for one confirmed alias are ambiguous and sorted`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("suffix:1234")
        val aliases = listOf("account-b", "account-a").map { accountId ->
            aliasEntity("alias-$accountId", accountId, aliasHash, "suffix")
        }
        coEvery {
            revisionDao.findConfirmedAliases(aliasHash, GroundedAccountResolver.MATCHING_SCOPE)
        } returns aliases
        aliases.forEach { alias ->
            coEvery { accountDao.getById(alias.accountId) } returns AccountEntity(
                id = alias.accountId, name = "A/c XX1234",
                bank = "Synthetic Bank", type = "manual"
            )
        }

        val result = assertIs<GroundedAccountResolution.Ambiguous>(
            resolver.resolve("**1234")
        )
        assertEquals(listOf("account-a", "account-b"), result.accountIds)
    }

    private fun aliasEntity(
        id: String,
        accountId: String,
        hash: String,
        kind: String
    ) = AccountAliasEntity(
        id = id,
        accountId = accountId,
        normalizedAliasHash = hash,
        aliasKind = kind,
        matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
        confirmedByUser = true,
        createdAt = 1L
    )
}
