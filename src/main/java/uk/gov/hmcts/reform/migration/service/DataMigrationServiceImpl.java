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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataMigrationServiceImpl implements DataMigrationService<Map<String, Object>> {

    public static final String COURT = "court";
    private final Map<String, Function<CaseDetails, Map<String, Object>>> migrations = Map.of(
        "DFPL-log", this::triggerOnlyMigration,
        "DFPL-2572", this::triggerTtlMigration,
        "DFPL-2635", this::triggerOnlyMigration,
        "DFPL-2642", this::triggerOnlyMigration,
        "DFPL-2423", this::triggerOnlyMigration,
        "DFPL-2640", this::triggerOnlyMigration
        );

    private final Map<String, EsQuery> queries = Map.of(
        "DFPL-2585", this.closedCases(),
        "DFPL-2423", this.topLevelFieldExistsQuery("proceeding"),
        "DFPL-2572", this.openCases()
    );

    private EsQuery closedCases() {
        final MatchQuery closedState = MatchQuery.of("state", "CLOSED");

        return BooleanQuery.builder()
            .must(Must.builder()
                .clauses(List.of(closedState))
                .build())
            .build();
    }

    private EsQuery openCases() {
        final MatchQuery openState = MatchQuery.of("state", "Open");

        return BooleanQuery.builder()
            .must(Must.builder()
                .clauses(List.of(openState))
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
        ttlMap.put("Suspended", "No");

        ObjectMapper objectMapper = new ObjectMapper();

        switch (caseDetails.getState()) {
            case "Open":
                ttlMap.put("SystemTTL", addDaysAndConvertToString(
                    caseDetails.getCreatedDate().toLocalDate(), 180));
                break;
            case "Submitted", "Gatekeeping", "GATEKEEPING_LISTING", "RETURNED":
                LocalDate dateSubmitted = convertValueToLocalDate(caseDetails.getData().get("dateSubmitted"));

                ttlMap.put("SystemTTL", addDaysAndConvertToString(dateSubmitted, 6575));
                break;
            case "CLOSED":
                Map<String, Object> closedCase = objectMapper.convertValue(
                    caseDetails.getData().get("closeCaseTabField"),
                    new TypeReference<Map<String, Object>>() {}
                );

                LocalDate closedCaseDate = convertValueToLocalDate(closedCase.get("date"));

                ttlMap.put("SystemTTL", addDaysAndConvertToString(closedCaseDate,6575));
                break;
            case "PREPARE_FOR_HEARING", "FINAL_HEARING":
                if (isEmpty(caseDetails.getData().get("orderCollection"))) {
                    dateSubmitted = convertValueToLocalDate(caseDetails.getData().get("dateSubmitted"));
                    ttlMap.put("SystemTTL", addDaysAndConvertToString(dateSubmitted, 6575));
                } else {
                    List<Element<Map<String,Object>>> orderCollection = objectMapper.convertValue(
                        caseDetails.getData().get("orderCollection"),
                        new TypeReference<List<Element<Map<String, Object>>>>() {}
                    );

                    orderCollection.sort((element1, element2) ->
                        getApprovalDateOnElement(element1)
                            .compareTo(getApprovalDateOnElement(element2)));

                    LocalDate localDate = getApprovalDateOnElement(orderCollection.get(orderCollection.size() - 1));
                    ttlMap.put("SystemTTL", addDaysAndConvertToString(localDate, 6575));
                }
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
        ObjectMapper objectMapper = new ObjectMapper();

        if (caseDetails.getData().containsKey("TTL")) {
            ttlMap = objectMapper.convertValue(caseDetails.getData().get("TTL"),
                new TypeReference<HashMap<String, Object>>() {});
            ttlMap.replace("Suspended", "Yes");
        } else {
            ttlMap.put("OverrideTTL", null);
            ttlMap.put("Suspended", "Yes");
            ttlMap.put("SystemTTL", null);
        }

        updates.put("TTL", ttlMap);
        return updates;
    }

    public Map<String, Object> triggerResumeMigrationTtl(CaseDetails caseDetails) {
        HashMap<String, Object> updates = new HashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();

        if (caseDetails.getData().containsKey("TTL")) {
            HashMap<String, Object> ttlMap = objectMapper.convertValue(caseDetails.getData().get("TTL"),
                new TypeReference<HashMap<String, Object>>() {});
            ttlMap.replace("Suspended", "No");
            updates.put("TTL", ttlMap);
        }

        return updates;
    }

    public Map<String, Object> triggerRemoveMigrationTtl(CaseDetails caseDetails) {
        HashMap<String, Object> updates = new HashMap<>();

        if (caseDetails.getData().containsKey("TTL")) {
            updates.put("TTL", new HashMap<>());
        }

        return updates;
    }

    public LocalDate convertValueToLocalDate(Object dateOnCase) {
        return LocalDate.parse(dateOnCase.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public String addDaysAndConvertToString(LocalDate localDate, long daysToAdd) {
        return localDate.plusDays(daysToAdd).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public LocalDate getApprovalDateOnElement(Element<Map<String, Object>> element) {
        if (!isEmpty(element.getValue().get("approvalDateTime"))) {
            return LocalDateTime.parse(element.getValue().get("approvalDateTime").toString()).toLocalDate();
        } else if (!isEmpty(element.getValue().get("approvalDate"))) {
            return convertValueToLocalDate(element.getValue().get("approvalDate"));
        } else {
            return LocalDate.parse(element.getValue().get("dateOfIssue").toString(),
                DateTimeFormatter.ofPattern("d MMMM yyyy"));
        }
    }
}
