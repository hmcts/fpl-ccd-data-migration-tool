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
import java.util.Arrays;
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
    public static final String STATE_OPEN = "Open";
    public static final String STATE_RETURNED = "RETURNED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STTATE_DELETED = "Deleted";
    public static final List<String> DFPL2773_SOURCES =  List.of(
        "refusedHearingOrders", "refusedHearingOrdersCTSC", "refusedHearingOrdersLA",
        "refusedHearingOrdersResp0", "refusedHearingOrdersResp1", "refusedHearingOrdersResp2",
        "refusedHearingOrdersResp3", "refusedHearingOrdersResp4", "refusedHearingOrdersResp5",
        "refusedHearingOrdersResp6", "refusedHearingOrdersResp7", "refusedHearingOrdersResp8",
        "refusedHearingOrdersResp9", "refusedHearingOrdersChild0", "refusedHearingOrdersChild1",
        "refusedHearingOrdersChild2", "refusedHearingOrdersChild3", "refusedHearingOrdersChild4",
        "refusedHearingOrdersChild5", "refusedHearingOrdersChild6", "refusedHearingOrdersChild7",
        "refusedHearingOrdersChild8", "refusedHearingOrdersChild9", "refusedHearingOrdersChild10",
        "refusedHearingOrdersChild11", "refusedHearingOrdersChild12", "refusedHearingOrdersChild13",
        "refusedHearingOrdersChild14");


    public static final String COURT = "court";
    private final Map<String, Function<CaseDetails, Map<String, Object>>> migrations = Map.of(
        "DFPL-log", this::triggerOnlyMigration,
        "SNI-8284", this::triggerOnlyMigration,
        "DFPL-2773", this::triggerOnlyMigration,
        "DFPL-2773-rollback", this::triggerOnlyMigration
    );

    private final Map<String, EsQuery> queries = Map.of(
        "DFPL-test", this.openCases(),
        "DFPL-log", this.allNonDeletedCases(),
        "DFPL-2773", this.allCasesNotInStates(STATE_OPEN),
        "DFPL-2773-rollback", this.allCasesNotInStates(STATE_OPEN)
    );

    // ES fields to be fetched for each migration. "reference" and "jurisdiction are always fetched.
    private final  Map<String, List<String>> esSourceFields = Map.of(
        "DFPL-2773", DFPL2773_SOURCES,
        "DFPL-2773-rollback", DFPL2773_SOURCES
    );

    private final Map<String, Predicate<CaseDetails>> predicates = Map.of(
        "DFPL-2773", this::filterDfpl2773,
        "DFPL-2773-rollback", this::filterDfpl2773
    );

    private EsQuery allCasesInStates(String... states) {
        final List<EsClause> stateQueries = new ArrayList<>();

        for (String state : states) {
            stateQueries.add(MatchQuery.of("state", states));
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
        return allCasesInStates(STTATE_DELETED);
    }

    private EsQuery closedCases() {
        return allCasesInStates(STATE_CLOSED);
    }

    private EsQuery openCases() {
        return allCasesInStates(STATE_OPEN);
    }

    private EsQuery activeCases() {
        return allCasesNotInStates(STATE_OPEN, STTATE_DELETED, STATE_RETURNED, STATE_CLOSED);
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

    public boolean filterDfpl2773(CaseDetails caseDetails) {
        return  !isEmpty(caseDetails.getData().get("refusedHearingOrders"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersCTSC"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersLA"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp0"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp1"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp2"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp3"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp4"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp5"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp6"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp7"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp8"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersResp9"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild0"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild1"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild2"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild3"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild4"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild5"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild6"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild7"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild8"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild9"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild10"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild11"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild12"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild13"))
            || !isEmpty(caseDetails.getData().get("refusedHearingOrdersChild14"));
    }
}
