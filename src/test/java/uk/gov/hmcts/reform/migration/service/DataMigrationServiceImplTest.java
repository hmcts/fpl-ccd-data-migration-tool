package uk.gov.hmcts.reform.migration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.fpl.model.common.Element;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.reform.migration.service.DataMigrationService.MIGRATION_ID_KEY;


@ExtendWith(MockitoExtension.class)
class DataMigrationServiceImplTest {

    private static final String INVALID_MIGRATION_ID = "NOT_A_MIGRATION";

    private DataMigrationServiceImpl dataMigrationService;

    CaseDetails caseDetails;

    @BeforeEach
    void setUp() {
        dataMigrationService = new DataMigrationServiceImpl();

        Map<String, String> court = Map.of("code", "344",
            "name", "Family Court sitting at Swansea",
            "email", "FamilyPublicLaw+sa@gmail.com"
        );

        caseDetails = CaseDetails.builder()
            .data(Map.of("court", court))
            .build();
    }

    @Test
    void shouldReturnTrueWhenCourtPresent() {
        assertThat(dataMigrationService.accepts().test(caseDetails)).isTrue();
    }


    @Test
    void shouldThrowExceptionWhenMigrationKeyIsNotSet() {
        assertThatThrownBy(() -> dataMigrationService.migrate(caseDetails, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Migration ID must not be null");
    }

    @Test
    void shouldThrowExceptionWhenMigrationKeyIsInvalid() {
        Map<String, Object> data = new HashMap<>();
        assertThatThrownBy(() -> dataMigrationService.migrate(caseDetails, INVALID_MIGRATION_ID))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessage("No migration mapped to " + INVALID_MIGRATION_ID);
        assertThat(data.get(MIGRATION_ID_KEY)).isNull();
    }

    @Test
    void shouldPopulateTtlOnOpenCase() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate expectedSystemTtl = now.toLocalDate().plusDays(180);
        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "No");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        caseDetails = CaseDetails.builder()
            .createdDate(now)
            .state("Open").build();

        assertThat(dataMigrationService.triggerTtlMigration(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldPopulateTtlOnSubmittedCase() {
        LocalDate now = LocalDate.now();
        LocalDate expectedSystemTtl = now.plusDays(6575);
        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "No");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> caseData = new HashMap<>();
        caseData.put("dateSubmitted", now);

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("Submitted").build();

        assertThat(dataMigrationService.triggerTtlMigration(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldPopulateTtlOnClosedCase() {
        LocalDate now = LocalDate.now();
        LocalDate expectedSystemTtl = now.plusDays(6575);

        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "No");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> closeCase = new HashMap<>();
        closeCase.put("date", now.toString());

        Map<String, Object> caseData = new HashMap<>();
        caseData.put("closeCase", closeCase);

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("CLOSED").build();

        assertThat(dataMigrationService.triggerTtlMigration(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldPopulateTtlOnCaseManagementCase() {
        LocalDate now = LocalDate.now();
        LocalDate expectedSystemTtl = now.plusDays(6575);

        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "No");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> order1 = new HashMap<>();
        order1.put("approvalDate", now.minusDays(2).toString());
        Map<String, Object> order2 = new HashMap<>();
        order2.put("approvalDate", now.toString());
        Map<String, Object> order3 = new HashMap<>();
        order3.put("approvalDate", now.minusDays(4).toString());

        List<Element<Map<String, Object>>> orderCollection = List.of(new Element<>(UUID.randomUUID(), order1),
            new Element<>(UUID.randomUUID(), order2),
            new Element<>(UUID.randomUUID(), order3));

        Map<String, Object> caseData = new HashMap<>();
        caseData.put("orderCollection", orderCollection);

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("CASE_MANAGEMENT").build();

        assertThat(dataMigrationService.triggerTtlMigration(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldPopulateTtlOnCaseManagementCaseWithoutOrders() {
        LocalDate now = LocalDate.now();
        LocalDate expectedSystemTtl = now.plusDays(6575);

        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "No");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> caseData = new HashMap<>();
        caseData.put("dateSubmitted", now);

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("CASE_MANAGEMENT").build();

        assertThat(dataMigrationService.triggerTtlMigration(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldSetSuspendOnTtlCaseWithExistingTtl() {
        LocalDate now = LocalDate.now();
        LocalDate expectedSystemTtl = now.plusDays(6575);

        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "Yes");
        expectedTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> existingTtl = new HashMap<>();
        existingTtl.put("OverrideTTL", null);
        existingTtl.put("Suspended", "No");
        existingTtl.put("SystemTTL", expectedSystemTtl);

        Map<String, Object> caseData = new HashMap<>();
        caseData.put("TTL", existingTtl);

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("CASE_MANAGEMENT").build();

        assertThat(dataMigrationService.triggerSuspendMigrationTtl(caseDetails).equals(expectedTtl));
    }

    @Test
    void shouldSetSuspendOnTtlCaseWithoutExistingTtl() {
        Map<String, Object> expectedTtl = new HashMap<>();
        expectedTtl.put("OverrideTTL", null);
        expectedTtl.put("Suspended", "Yes");
        expectedTtl.put("SystemTTL", null);

        Map<String, Object> caseData = new HashMap<>();

        caseDetails = CaseDetails.builder()
            .data(caseData)
            .state("CASE_MANAGEMENT").build();

        assertThat(dataMigrationService.triggerSuspendMigrationTtl(caseDetails).equals(expectedTtl));
    }
}
