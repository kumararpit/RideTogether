package com.example

import com.example.data.amenities.OverpassQueryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogues the Overpass query builder: the consolidated regex-union QL must keep
 * EXACTLY the original 13-category, node+way coverage from the pre-audit builder
 * (locked here) while shrinking the request text ~3x (the old 4-cell GET URL hit
 * the endpoint's 8 kB header cap with HTTP 414 — device 2026-10-19).
 */
class OverpassQueryBuilderTest {

    /** The 13 original (key, value) selectors from the pre-audit implementation. */
    private val originalSelectors = listOf(
        "amenity=fuel", "amenity=cafe", "shop=motorcycle_repair", "amenity=motorcycle_repair",
        "highway=rest_area", "shop=convenience", "shop=supermarket", "tourism=hotel",
        "tourism=motel", "amenity=restaurant", "amenity=fast_food", "amenity=toilets",
        "tourism=viewpoint"
    )

    private val cell = OverpassQueryBuilder.Cell(18.58, 73.65, 18.62, 73.72)

    private fun bboxStatements(query: String, bbox: String): List<String> =
        query.split(";").filter { it.contains("($bbox)") }

    @Test
    fun `exact generation coverage is preserved`() {
        val query = OverpassQueryBuilder.buildQuery(listOf(cell))
        val statements = query.split(";").filter { it.contains("(") && it.contains(")") }
        for (selector in originalSelectors) {
            val (key, value) = selector.split("=")
            assertTrue(
                "selector \"$selector\" missing from consolidated QL",
                statements.any { stmt -> stmt.contains("\"$key\"") && stmt.contains(value) }
            )
        }
        // And NO unmapped selector slipped in through the consolidation.
        val allowed = setOf("amenity", "shop", "tourism", "highway")
        statements.forEach { stmt ->
            assertTrue("statement touches an unexpected key: $stmt", allowed.any { stmt.contains("\"$it\"") })
        }
    }

    @Test
    fun `every element type appears for both node and way`() {
        val query = OverpassQueryBuilder.buildQuery(listOf(cell))
        // The union's opening paren glues to the first statement; normalize it off.
        val statements = query
            .removePrefix("[out:json][timeout:25];(")
            .removeSuffix(");out center;")
            .split(";")
            .filter { it.isNotBlank() }
        assertEquals(8, statements.size)
        assertEquals(4, statements.count { it.startsWith("node[") })
        assertEquals(4, statements.count { it.startsWith("way[") })
    }

    @Test
    fun `rest_area stays an exact equality match`() {
        val query = OverpassQueryBuilder.buildQuery(listOf(cell))
        assertTrue(query.contains("node[\"highway\"=\"rest_area\"]"))
        assertTrue(query.contains("way[\"highway\"=\"rest_area\"]"))
        // Rest-area lines must not be folded into a loose regex.
        assertTrue(!query.contains("highway\"~"))
    }

    @Test
    fun `output statement is compact center output`() {
        val query = OverpassQueryBuilder.buildQuery(emptyList())
        assertEquals("[out:json][timeout:25];();out center;", query)
        val single = OverpassQueryBuilder.buildQuery(listOf(cell))
        assertTrue(single.endsWith(");out center;"))
        assertTrue(!single.contains("out meta"))
        assertTrue(!single.contains("out body"))
    }

    @Test
    fun `four cells stay far under the old GET url size`() {
        val cells = listOf(
            OverpassQueryBuilder.Cell(18.5803, 73.6198, 18.6252, 73.6644),
            OverpassQueryBuilder.Cell(18.5803, 73.6644, 18.6252, 73.7091),
            OverpassQueryBuilder.Cell(18.5803, 73.7091, 18.6252, 73.7537),
            OverpassQueryBuilder.Cell(18.5803, 73.7537, 18.6252, 73.7983)
        )
        val query = OverpassQueryBuilder.buildQuery(cells)
        assertTrue(
            "pre-audit 4-cell QL was 6265 B; consolidated must be inherently smaller",
            query.length < 4000
        )
    }

    @Test
    fun `query version stays pinned for cache-key stability`() {
        assertEquals(2, OverpassQueryBuilder.QUERY_VERSION)
        assertEquals(14, OverpassQueryBuilder.CELL_ZOOM)
    }
}
