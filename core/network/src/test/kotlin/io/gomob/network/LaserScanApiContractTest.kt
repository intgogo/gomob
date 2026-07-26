package io.gomob.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.Query

class LaserScanApiContractTest {

    @Test
    fun cloudDownloadsExposeMaxPointsQuery() {
        assertMaxPointsQuery("downloadCloud")
        assertMaxPointsQuery("downloadActiveCloud")
    }

    private fun assertMaxPointsQuery(methodName: String) {
        val method = LaserScanApi::class.java.declaredMethods.single { it.name == methodName }
        val queries = method.parameterAnnotations
            .flatMap { annotations -> annotations.filterIsInstance<Query>() }
            .map(Query::value)

        assertThat(queries).contains("max_points")
    }
}
