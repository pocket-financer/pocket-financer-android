package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GroundedAccountResolution {
    data object Missing : GroundedAccountResolution
    data object Unresolved : GroundedAccountResolution
    data class Ambiguous(val accountIds: List<String>) : GroundedAccountResolution
    data class UniquelyResolved(
        val accountId: String,
        val matchedAliasHash: String,
        val provenance: String
    ) : GroundedAccountResolution
}

@Singleton
class GroundedAccountResolver @Inject constructor(
    private val accountDao: AccountDao,
    private val revisionDao: TransactionRevisionDao
) {
    suspend fun resolve(sourceGroundedAlias: String?): GroundedAccountResolution {
        if (sourceGroundedAlias == null) return GroundedAccountResolution.Missing
        val normalized = normalize(sourceGroundedAlias)
        if (normalized.isEmpty()) return GroundedAccountResolution.Unresolved
        val aliasHash = SmsProcessingStore.sha256(normalized)
        val aliases = revisionDao.findConfirmedAliases(aliasHash, MATCHING_SCOPE)
        val accounts = aliases.mapNotNull { alias -> accountDao.getById(alias.accountId) }
            .distinctBy { it.id }
        return when (accounts.size) {
            0 -> GroundedAccountResolution.Unresolved
            1 -> GroundedAccountResolution.UniquelyResolved(
                accountId = accounts.single().id,
                matchedAliasHash = aliasHash,
                provenance = "confirmed_owned_account_alias_v1"
            )
            else -> GroundedAccountResolution.Ambiguous(accounts.map { it.id }.sorted())
        }
    }

    private fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .lowercase(Locale.ROOT)
        val vpa = Regex("[a-z0-9][a-z0-9._-]{1,}@[a-z0-9][a-z0-9.-]+")
            .find(normalized)?.value
        if (vpa != null) return "vpa:$vpa"
        val suffixes = Regex("(?:[xX*•-]{2,}\\s*)?[0-9]{3,8}")
            .findAll(normalized).map { it.value }.toList()
        if (suffixes.size != 1) return ""
        val digits = suffixes.single().filter { it.isDigit() }
        return digits.takeIf { it.length in 3..8 }?.let { "suffix:$it" }.orEmpty()
    }

    companion object {
        const val MATCHING_SCOPE = "owned_account_v1"
    }
}
