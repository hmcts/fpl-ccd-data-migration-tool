package uk.gov.hmcts.reform.migration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.fpl.model.common.Element;
import uk.gov.hmcts.reform.migration.query.BooleanQuery;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.query.ExistsQuery;
import uk.gov.hmcts.reform.migration.query.Filter;
import uk.gov.hmcts.reform.migration.query.MatchQuery;
import uk.gov.hmcts.reform.migration.query.Must;
import uk.gov.hmcts.reform.migration.query.MustNot;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataMigrationServiceImpl implements DataMigrationService<Map<String, Object>> {

    public static final String COURT = "court";
    private final Map<String, Function<CaseDetails, Map<String, Object>>> migrations = Map.of(
        "DFPL-log", this::triggerOnlyMigration,
        "DFPL-2572", this::triggerTtlMigration
        );

    private final Map<String, EsQuery> queries = Map.of(
        "DFPL-2585", this.closedCases()
    );

    private EsQuery closedCases() {
        final MatchQuery closedState = MatchQuery.of("state", "CLOSED");

        return BooleanQuery.builder()
            .must(Must.builder()
                .clauses(List.of(closedState))
                .build())
            .build();
    }

    @Override
    public void validateMigrationId(String migrationId) {
        if (!migrations.containsKey(migrationId)) {
            throw new NoSuchElementException("No migration mapped to " + migrationId);
        }
    }

    @Override
    public EsQuery getQuery(String migrationId) {
        if (!queries.containsKey(migrationId)) {
            throw new NoSuchElementException("No migration mapped to " + migrationId);
        }
        log.info(queries.get(migrationId).toQueryContext(100, 0).toString());
        return queries.get(migrationId);
    }


    @Override
    public Predicate<CaseDetails> accepts() {
        return caseDetails -> true;
    }

    @Override
    public Map<String, Object> migrate(CaseDetails caseDetails, String migrationId) {
        requireNonNull(migrationId, "Migration ID must not be null");
        if (!migrations.containsKey(migrationId)) {
            throw new NoSuchElementException("No migration mapped to " + migrationId);
        }

        // Perform Migration
        return migrations.get(migrationId).apply(caseDetails);
    }

    private EsQuery topLevelFieldExistsQuery(String field) {
        return BooleanQuery.builder()
            .filter(Filter.builder()
                .clauses(List.of(ExistsQuery.of("data." + field)))
                .build())
            .build();
    }

    private EsQuery topLevelFieldDoesNotExistQuery(String field) {
        return BooleanQuery.builder()
            .filter(Filter.builder()
                .clauses(List.of(BooleanQuery.builder()
                    .mustNot(MustNot.of(ExistsQuery.of("data." + field)))
                    .build()))
                .build())
            .build();
    }

    private Map<String, Object> triggerOnlyMigration(CaseDetails caseDetails) {
        // do nothing
        return new HashMap<>();
    }

    public Map<String, Object> triggerTtlMigration(CaseDetails caseDetails) {
        HashMap<String, Object> ttlMap = new HashMap<>();
        ttlMap.put("OverrideTTL", null);
        ttlMap.put("Suspend", "NO");

        ObjectMapper objectMapper = new ObjectMapper();

        switch (caseDetails.getState()) {
            case "Open":
                ttlMap.put("SystemTTL", caseDetails.getCreatedDate().toLocalDate().plusDays(180));
                break;
            case "Submitted", "Gatekeeping", "GATEKEEPING_LISTING", "Returned":
                Object dateSubmitted = caseDetails.getData().get("dateSubmitted");

                ttlMap.put("SystemTTL", convertValueToLocalDate(dateSubmitted).plusDays(6575));
                break;
            case "CLOSED":
                Map<String, Object> closedCase = objectMapper.convertValue(
                    caseDetails.getData().get("closeCase"),
                    new TypeReference<Map<String, Object>>() {}
                );

                Object closedCaseDate = closedCase.get("date");

                ttlMap.put("SystemTTL", convertValueToLocalDate(closedCaseDate).plusDays(6575));
                break;
            case "CASE_MANAGEMENT", "FINAL_HEARING":
                List<Element<Map<String,Object>>> orderCollection = objectMapper.convertValue(
                    caseDetails.getData().get("orderCollection"),
                    new TypeReference<List<Element<Map<String, Object>>>>() {}
                );

                orderCollection.sort((element1, element2) ->
                    convertValueToLocalDate(element1.getValue().get("approvalDate"))
                        .compareTo(convertValueToLocalDate(element2.getValue().get("approvalDate"))));

                Object lastIssuedDate = orderCollection
                    .get(orderCollection.size() - 1)
                    .getValue()
                    .get("approvalDate");

                ttlMap.put("SystemTTL", convertValueToLocalDate(lastIssuedDate).plusDays(6575));
                break;
            default:
                throw new AssertionError(format("Migration 2572, case with id: %s "
                    + "not in valid state for TTL migration", caseDetails.getId()));
        }

        HashMap<String, Object> updates = new HashMap<>();
        updates.put("TTL", ttlMap);
        return updates;
    }

    public Map<String, Object> triggerSuspendMigrationTtl(CaseDetails caseDetails) {
        HashMap<String, Object> updates = new HashMap<>();
        HashMap<String, Object> ttlMap = new HashMap<>();

        if (caseDetails.getData().containsKey("TTL")) {
            ttlMap.put("OverrideTTL", caseDetails.getData().get("OverrideTTL"));
            ttlMap.put("Suspend", "YES");
            ttlMap.put("SystemTTL", caseDetails.getData().get("SystemTTL"));
        } else {
            ttlMap.put("OverrideTTL", null);
            ttlMap.put("Suspend", "YES");
            ttlMap.put("SystemTTL", null);
        }

        updates.put("TTL", ttlMap);
        return updates;
    }

    public LocalDate convertValueToLocalDate(Object dateOnCase) {
        return LocalDate.parse(dateOnCase.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
