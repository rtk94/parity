package com.rknepp.parity.relationships.data

import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall
import com.rknepp.parity.relationships.data.dto.BalanceResponse
import com.rknepp.parity.relationships.data.dto.RelationshipDto
import com.rknepp.parity.relationships.data.dto.RelationshipListResponse

class RelationshipRepository(
    private val apiProvider: () -> RelationshipApi,
) {
    suspend fun list(
        status: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResult<RelationshipListResponse> = apiCall {
        apiProvider().list(status = status, limit = limit, offset = offset)
    }

    suspend fun get(id: Long): ApiResult<RelationshipDto> = apiCall {
        apiProvider().get(id)
    }

    suspend fun balance(id: Long): ApiResult<BalanceResponse> = apiCall {
        apiProvider().balance(id)
    }
}
