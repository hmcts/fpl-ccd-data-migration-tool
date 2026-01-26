package uk.gov.hmcts.reform.query;

import net.minidev.json.JSONObject;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.query.Sort;
import uk.gov.hmcts.reform.migration.query.SortOrder;
import uk.gov.hmcts.reform.migration.query.SortQuery;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EsQueryTest {

    @Test
    void shouldCreateQueryWithSizeAndFromFields() {
        EsQuery query = new TestClass();

        final JSONObject queryInContext = query.toQueryContext(2, 3);
        final JSONObject expectedContext = new JSONObject(
            Map.of("query", Map.of("test", "query"), "size", 2, "from", 3,
                "_source", List.of("reference", "jurisdiction"),"track_total_hits", true)
        );

        assertThat(queryInContext).usingRecursiveComparison().isEqualTo(expectedContext);
    }

    @Test
    void shouldCreateQueryWithSizeAndFromFieldsSort() {
        EsQuery query = new TestClass();
        Sort sort = Sort.builder()
            .clauses(List.of(
                SortQuery.of("data.dateSubmitted", SortOrder.DESC)
            ))
            .build();
        final JSONObject queryInContext = query.toQueryContext(2, 3, sort);
        final JSONObject expectedContext = new JSONObject(
            Map.of("_source", List.of("reference", "jurisdiction"),
                "query", Map.of("test", "query"),
                "track_total_hits", true,
                "size", 2,
                "from", 3,
                "sort", List.of(Map.of("data.dateSubmitted", Map.of("order", "desc")))));

        assertThat(queryInContext).usingRecursiveComparison().isEqualTo(expectedContext);
    }

    @Test
    void shouldCreateQueryWithExtraSources() {
        EsQuery query = new TestClass();
        Sort sort = Sort.builder()
            .clauses(List.of(
                SortQuery.of("data.dateSubmitted", SortOrder.DESC)
            ))
            .build();
        final JSONObject queryInContext = query.toQueryContext(2, sort, List.of("extraField"));
        final JSONObject expectedContext = new JSONObject(
            Map.of("_source", List.of("reference", "jurisdiction", "extraField"),
                "query", Map.of("test", "query"),
                "track_total_hits", true,
                "size", 2,
                "sort", List.of(Map.of("data.dateSubmitted", Map.of("order", "desc")))));

        assertThat(queryInContext).usingRecursiveComparison().isEqualTo(expectedContext);
    }

    @Test
    void shouldCreateQueryWithSearchAfterAndExtraSources() {
        EsQuery query = new TestClass();
        Sort sort = Sort.builder()
            .clauses(List.of(
                SortQuery.of("data.dateSubmitted", SortOrder.DESC)
            ))
            .build();
        final JSONObject queryInContext = query.toQueryContext(2,"searchAfter", sort, List.of("extraField"));
        final JSONObject expectedContext = new JSONObject(
            Map.of("_source", List.of("reference", "jurisdiction", "extraField"),
                "query", Map.of("test", "query"),
                "track_total_hits", true,
                "size", 2,
                "search_after", List.of("searchAfter"),
                "sort", List.of(Map.of("data.dateSubmitted", Map.of("order", "desc")))));

        assertThat(queryInContext).usingRecursiveComparison().isEqualTo(expectedContext);
    }

    private static class TestClass implements EsQuery {
        @Override
        public Map<String, Object> toMap() {
            return Map.of("test", "query");
        }
    }
}
