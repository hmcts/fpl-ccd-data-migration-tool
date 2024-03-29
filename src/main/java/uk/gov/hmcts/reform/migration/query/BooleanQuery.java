package uk.gov.hmcts.reform.migration.query;

import lombok.Builder;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

@EqualsAndHashCode
@Builder
public class BooleanQuery implements EsQuery {
    private final MustNot mustNot;
    private final Must must;
    private final Filter filter;

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> query = new HashMap<>();
        if (mustNot != null) {
            query.putAll(mustNot.toMap());
        }
        if (must != null) {
            query.putAll(must.toMap());
        }
        if (filter != null) {
            query.putAll(filter.toMap());
        }
        return Map.of("bool", query);
    }
}
