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

    @Test fun `masked suffix resolves only a confirmed owned alias with a live account`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("suffix:1234")
        val alias = AccountAliasEntity(
            id = "alias", accountId = "account", normalizedAliasHash = aliasHash,
            aliasKind = "suffix", matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
            confirmedByUser = true, createdAt = 1L
        )
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

    @Test fun `ambiguous source reference fails before alias lookup`() = runTest {
        assertIs<GroundedAccountResolution.Unresolved>(
            resolver.resolve("accounts **1234 and **5678")
        )
        coVerify(exactly = 0) { revisionDao.findConfirmedAliases(any(), any()) }
        coVerify(exactly = 0) { accountDao.getById(any()) }
    }

    @Test fun `multiple live accounts for one confirmed alias are ambiguous`() = runTest {
        val aliasHash = SmsProcessingStore.sha256("suffix:1234")
        val aliases = listOf("account-a", "account-b").map { accountId ->
            AccountAliasEntity(
                id = "alias-$accountId", accountId = accountId,
                normalizedAliasHash = aliasHash, aliasKind = "suffix",
                matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                confirmedByUser = true, createdAt = 1L
            )
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
}
