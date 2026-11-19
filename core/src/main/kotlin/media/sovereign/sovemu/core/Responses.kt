package media.sovereign.sovemu.core

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloHttpException

/**
 * The response's data, or the failure as an [ApiException]: the first GraphQL error with its
 * `extensions.code` (`UNAUTHENTICATED` …), or `HTTP_<status>` when the server answered with no
 * GraphQL response at all. A network failure is rethrown as it is.
 */
fun <D : Operation.Data> ApolloResponse<D>.orThrow(): D {
    errors?.firstOrNull()?.let { e ->
        throw ApiException(e.extensions?.get("code") as? String ?: "INTERNAL", e.message)
    }
    when (val ex = exception) {
        null -> {}
        is ApolloHttpException -> throw ApiException("HTTP_${ex.statusCode}", "Unexpected response from the server (HTTP ${ex.statusCode})")
        else -> throw ex
    }
    return data ?: throw ApiException("INTERNAL", "The server sent no data")
}
