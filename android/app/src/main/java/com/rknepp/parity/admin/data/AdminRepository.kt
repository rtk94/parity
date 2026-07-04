package com.rknepp.parity.admin.data

import com.rknepp.parity.admin.data.dto.AdminStatsDto
import com.rknepp.parity.admin.data.dto.CleanupTokensResponse
import com.rknepp.parity.admin.data.dto.ResetLedgerRequest
import com.rknepp.parity.admin.data.dto.ResetLedgerResponse
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall

class AdminRepository(
    private val apiProvider: () -> AdminApi,
) {
    suspend fun stats(): ApiResult<AdminStatsDto> = apiCall {
        apiProvider().stats()
    }

    suspend fun cleanupTokens(): ApiResult<CleanupTokensResponse> = apiCall {
        apiProvider().cleanupTokens()
    }

    /**
     * Erase every ledger entry. The confirmation phrase is fixed by
     * the backend; the UI gates this behind its own type-to-confirm
     * dialog before the request is ever sent.
     */
    suspend fun resetLedger(): ApiResult<ResetLedgerResponse> = apiCall {
        apiProvider().resetLedger(ResetLedgerRequest(confirm = RESET_CONFIRM_PHRASE))
    }

    companion object {
        const val RESET_CONFIRM_PHRASE = "RESET LEDGER"
    }
}
