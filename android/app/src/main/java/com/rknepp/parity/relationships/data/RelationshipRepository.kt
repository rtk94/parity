package com.rknepp.parity.relationships.data

import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.network.apiCall
import com.rknepp.parity.relationships.data.dto.BalanceResponse
import com.rknepp.parity.relationships.data.dto.CreateRelationshipRequest
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

    suspend fun create(username: String, currencyCode: String): ApiResult<RelationshipDto> = apiCall {
        apiProvider().create(CreateRelationshipRequest(username = username, currency_code = currencyCode))
    }

    suspend fun accept(id: Long): ApiResult<RelationshipDto> = apiCall {
        apiProvider().accept(id)
    }

    suspend fun reject(id: Long): ApiResult<RelationshipDto> = apiCall {
        apiProvider().reject(id)
    }
}
