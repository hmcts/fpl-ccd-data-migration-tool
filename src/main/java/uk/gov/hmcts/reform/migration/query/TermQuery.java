package uk.gov.hmcts.reform.migration.query;

import lombok.EqualsAndHashCode;

import java.util.Map;

import static java.util.Objects.requireNonNull;

@EqualsAndHashCode
public class TermQuery implements EsClause<Map<String, Object>> {
    private final String field;
    private final String value;

    public TermQuery(String field, String value) {
        requireNonNull(field);
        requireNonNull(value);
        this.field = field;
        this.value = value;
    }

    public static TermQuery of(String field, String value) {
        return new TermQuery(field, value);
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of("term", Map.of(field, value));
    }
}
