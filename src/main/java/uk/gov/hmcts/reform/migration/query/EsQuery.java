package uk.gov.hmcts.reform.migration.query;

import net.minidev.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;

public interface EsQuery extends EsClause {
    List<String> DEFAULT_SOURCE_FIELDS = List.of("reference", "jurisdiction");

    default JSONObject toQueryContext(int size, int from) {
        return new JSONObject(Map.of(
            "size", size,
            "from", from,
            "query", this.toMap(),
            "_source", DEFAULT_SOURCE_FIELDS,
            "track_total_hits", true
        ));
    }

    default JSONObject toQueryContext(int size, int from, Sort sort) {
        return new JSONObject(Map.of(
                "size", size,
                "from", from,
                "query", this.toMap(),
                "sort", sort.toMap(),
                "_source", DEFAULT_SOURCE_FIELDS,
                "track_total_hits", true)
        );
    }

    default JSONObject toQueryContext(int size, Sort sort, List<String> extraSourceFields) {
        return new JSONObject(Map.of(
            "size", size,
            "query", this.toMap(),
            "sort", sort.toMap(),
            "_source", (isEmpty(extraSourceFields))
                ? DEFAULT_SOURCE_FIELDS : new ArrayList<>(DEFAULT_SOURCE_FIELDS).addAll(extraSourceFields),
            "track_total_hits", true)
        );
    }

    default JSONObject toQueryContext(int size, String after, Sort sort, List<String> extraSourceFields) {
        return new JSONObject(Map.of(
            "size", size,
            "search_after", List.of(after),
            "query", this.toMap(),
            "sort", sort.toMap(),
            "_source", (isEmpty(extraSourceFields))
                ? DEFAULT_SOURCE_FIELDS : new ArrayList<>(DEFAULT_SOURCE_FIELDS).addAll(extraSourceFields),
            "track_total_hits", true)
        );
    }

}
