package uk.gov.hmcts.reform.migration.query;

import net.minidev.json.JSONObject;

import java.util.List;
import java.util.Map;

public interface EsQuery extends EsClause {
    default JSONObject toQueryContext(int size, int from) {
        return new JSONObject(Map.of(
            "size", size,
            "from", from,
            "query", this.toMap(),
            "_source", List.of("reference", "jurisdiction"),
            "track_total_hits", true
        ));
    }

    default JSONObject toQueryContext(int size, int from, Sort sort) {
        return new JSONObject(Map.of(
                "size", size,
                "from", from,
                "query", this.toMap(),
                "sort", sort.toMap(),
                "_source", List.of("reference", "jurisdiction"),
                "track_total_hits", true)
        );
    }

    default JSONObject toQueryContext(int size, Sort sort) {
        return new JSONObject(Map.of(
            "size", size,
            "query", this.toMap(),
            "sort", sort.toMap(),
            "_source", List.of("reference", "jurisdiction"),
            "track_total_hits", true)
        );
    }

    default JSONObject toQueryContext(int size, String after, Sort sort) {
        // Temp for migration tool perf testing
        return new JSONObject(Map.of(
            "size", size,
            "search_after", after,
            "query", this.toMap(),
            "sort", sort.toMap(),
            "_source", List.of("reference", "jurisdiction"),
            "track_total_hits", true)
        );
    }

}
