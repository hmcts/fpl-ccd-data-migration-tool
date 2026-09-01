package uk.gov.hmcts.reform.migration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.domain.exception.CaseMigrationSkippedException;
import uk.gov.hmcts.reform.fpl.model.common.Element;
import uk.gov.hmcts.reform.migration.query.BooleanQuery;
import uk.gov.hmcts.reform.migration.query.EsClause;
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

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class DataMigrationServiceImpl implements DataMigrationService<Map<String, Object>> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    public static final String STATE_OPEN = "Open";
    public static final String STATE_RETURNED = "RETURNED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_DELETED = "Deleted";
    public static final List<String> DFPL_2421_SOURCES =  List.of("others", "othersV2");


    public static final String COURT = "court";
    public static final String CASE_MANAGEMENT_LOCATION = "caseManagementLocation";
    public static final String BASE_LOCATION =  "baseLocation";
    public static final String FLEETWOOD_COURT_CODE = "401452";
    private static final String TRANSFERRED_EPIMMS_ID = "102476";
    private static final String ORDERS = "orders";
    private final Map<String, Function<CaseDetails, Map<String, Object>>> migrations = Map.of(
        "DFPL-log", this::triggerOnlyMigration,
        "DFPL-3290", this::triggerOnlyMigration,
        "DFPL-3213", this::triggerOnlyMigration,
        "DFPL-2421", this::triggerOnlyMigration,
        "DFPL-2421-rollback", this::triggerOnlyMigration,
        "DFPL-3306", this::triggerOnlyMigration,
        "DFPL-3292", this::triggerOnlyMigration,
        "DFPL-3296", this::triggerOnlyMigration,
        "DFPL-3213-v2", this::triggerOnlyMigration,
        "DFPL-3345", this::triggerOnlyMigration
    );

    private final Map<String, EsQuery> queries = Map.of(
        "DFPL-test", this.openCases(),
        "DFPL-log", this.allNonDeletedCases(),
        "DFPL-2421", this.allCases(),
        "DFPL-2421-rollback", this.allCases()
    );

    // ES fields to be fetched for each migration. "reference" and "jurisdiction are always fetched.
    private final  Map<String, List<String>> esSourceFields = Map.of(
        "DFPL-test", List.of("court"),
        "DFPL-2421", DFPL_2421_SOURCES,
        "DFPL-2421-rollback", DFPL_2421_SOURCES
    );

    private final Map<String, Predicate<CaseDetails>> predicates = Map.of(
        "DFPL-test", (caseDetails) -> !isEmpty(caseDetails.getData().get("court")),
        "DFPL-3213", this::filterDfpl3213,
        "DFPL-2421", this::filter2421,
        "DFPL-2421-rollback", this::filter2421Rollback,
        "DFPL-3213-v2", this::filterDfpl3213v2
    );

    private EsQuery allCases() {
        return BooleanQuery.builder().build();
    }

    private EsQuery allCasesInStates(String... states) {
        final List<EsClause> stateQueries = new ArrayList<>();

        for (String state : states) {
            stateQueries.add(MatchQuery.of("state", state));
        }

        return BooleanQuery.builder()
            .must(Must.builder()
                .clauses(stateQueries)
                .build())
            .build();
    }

    private EsQuery allCasesNotInStates(String... states) {
        final List<EsClause> stateQueries = new ArrayList<>();

        for (String state : states) {
            stateQueries.add(MatchQuery.of("state", state));
        }

        return BooleanQuery.builder()
            .mustNot(MustNot.builder()
                .clauses(stateQueries)
                .build())
            .build();
    }

    private EsQuery allNonDeletedCases() {
        return allCasesNotInStates(STATE_DELETED);
    }

    private EsQuery closedCases() {
        return allCasesInStates(STATE_CLOSED);
    }

    private EsQuery openCases() {
        return allCasesInStates(STATE_OPEN);
    }

    private EsQuery activeCases() {
        return allCasesNotInStates(STATE_OPEN, STATE_DELETED, STATE_RETURNED, STATE_CLOSED);
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
    public Predicate<CaseDetails> accepts(String migrationId) {
        return predicates.getOrDefault(migrationId, caseDetails -> true);
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

    @Override
    public List<String> getExtraSourceFields(String migrationId) {
        return esSourceFields.getOrDefault(migrationId, List.of());
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

    private Map<String, Object> triggerIfTopLevelFieldExist(CaseDetails data, String fieldName) {
        if (data.getData().containsKey(fieldName)) {
            // do nothing
            return new HashMap<>();
        }
        throw new CaseMigrationSkippedException("Skipping case. " + fieldName + " is empty");
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

    private boolean filter2421(CaseDetails caseDetails) {
        return !isEmpty(caseDetails.getData().get("others"));
    }

    private boolean filterDfpl3213(CaseDetails caseDetails) {
        if (isEmpty(caseDetails.getData().get(CASE_MANAGEMENT_LOCATION))) {
            return false;
        }

        try {
            Map<String, Object> location = objectMapper.convertValue(
                caseDetails.getData().get(CASE_MANAGEMENT_LOCATION),
                new TypeReference<>() {
                }
            );

            // Accept the case ONLY if the baseLocation exists and matches Fleetwood's code (401452)
            return location != null
                && FLEETWOOD_COURT_CODE.equalsIgnoreCase(String.valueOf(location.get(BASE_LOCATION)));
        } catch (Exception e) {
            log.error("Failed to parse caseManagementLocation for case: {}", caseDetails.getId(), e);
            return false;
        }
    }

    private boolean filterDfpl3213v2(CaseDetails caseDetails) {
        if (isEmpty(caseDetails.getData().get(CASE_MANAGEMENT_LOCATION))) {
            return false;
        }

        try {
            Map<String, Object> location = objectMapper.convertValue(
                caseDetails.getData().get(CASE_MANAGEMENT_LOCATION),
                new TypeReference<>() {
                }
            );

            Map<String, Object> orders = objectMapper.convertValue(
                caseDetails.getData().get(ORDERS),
                new TypeReference<>() {
                }
            );

            String ordersCourt = orders != null ? String.valueOf(orders.get(COURT)) : null;

            return location != null
                && TRANSFERRED_EPIMMS_ID.equalsIgnoreCase(String.valueOf(location.get(BASE_LOCATION)))
                && "438".equalsIgnoreCase(ordersCourt);
        } catch (Exception e) {
            log.error("Failed to parse case details for case: {}", caseDetails.getId(), e);
            return false;
        }
    }

    private boolean filter2421Rollback(CaseDetails caseDetails) {
        return !isEmpty(caseDetails.getData().get("othersV2"));
    }

}
