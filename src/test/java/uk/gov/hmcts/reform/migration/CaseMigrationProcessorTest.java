package uk.gov.hmcts.reform.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.annotation.DirtiesContext;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.migration.ccd.CoreCaseDataService;
import uk.gov.hmcts.reform.migration.query.BooleanQuery;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.query.ExistsQuery;
import uk.gov.hmcts.reform.migration.query.Filter;
import uk.gov.hmcts.reform.migration.repository.ElasticSearchRepository;
import uk.gov.hmcts.reform.migration.repository.IdamRepository;
import uk.gov.hmcts.reform.migration.service.DataMigrationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.migration.CaseMigrationProcessor.EVENT_DESCRIPTION;
import static uk.gov.hmcts.reform.migration.CaseMigrationProcessor.EVENT_ID;
import static uk.gov.hmcts.reform.migration.CaseMigrationProcessor.EVENT_SUMMARY;


@ExtendWith(MockitoExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CaseMigrationProcessorTest {

    private static final String USER_TOKEN = "Bearer eeeejjjttt";

    private static final String CASE_TYPE = "CARE_SUPERVISION_EPO";
    private static final String CASE_JURISDICTION = "PUBLICLAW";

    private static final String MIGRATION_ID = "DFPL-TEST";

    private static final int DEFAUT_QUERY_SIZE = 10;
    private static final int DEFAULT_THREAD_LIMIT = 8;


    private CaseMigrationProcessor caseMigrationProcessor;

    @Mock
    private CoreCaseDataService coreCaseDataService;

    @Mock
    private ElasticSearchRepository elasticSearchRepository;

    @Mock
    private IdamRepository idamRepository;

    @Mock
    private DataMigrationService<Map<String, Object>> dataMigrationService;

    @Captor
    ArgumentCaptor<CaseDetails> caseDetailsArgumentCaptor;

    private static final EsQuery QUERY = BooleanQuery.builder()
        .filter(Filter.builder()
            .clauses(List.of(ExistsQuery.of("data.court")))
            .build())
        .build();

    @BeforeEach
    void setUp() {
        when(idamRepository.generateUserToken()).thenReturn(USER_TOKEN);
        caseMigrationProcessor = new CaseMigrationProcessor(coreCaseDataService,
            elasticSearchRepository,
            idamRepository,
            dataMigrationService,
            DEFAUT_QUERY_SIZE,
            DEFAULT_THREAD_LIMIT,
            0,
            MIGRATION_ID,
            CASE_JURISDICTION,
            CASE_TYPE,
            false,
            300);
    }

    @Test
    void shouldMigrateCasesOfACaseTypeByParallelProcessing() throws InterruptedException {
        when(idamRepository.generateUserToken()).thenReturn(USER_TOKEN);
        List<CaseDetails> cases = createCaseDetails(1,2);
        when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, null, List.of()))
            .thenReturn(cases);
        when(elasticSearchRepository.searchResultsSize(USER_TOKEN, CASE_TYPE, QUERY)).thenReturn(2);
        when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);

        caseMigrationProcessor.migrateQuery(QUERY);
        verify(coreCaseDataService, times(2))
            .update(eq(USER_TOKEN),
                eq(EVENT_ID),
                eq(EVENT_SUMMARY),
                eq(EVENT_DESCRIPTION),
                eq(CASE_TYPE),
                caseDetailsArgumentCaptor.capture(),
                eq(MIGRATION_ID));

        assertThat(caseDetailsArgumentCaptor.getAllValues())
            .extracting("id")
            .contains(1L, 2L);
    }

    private List<CaseDetails> createCaseDetails(int start, int count) {
        return LongStream.range(start, start + count)
            .mapToObj(counter -> CaseDetails.builder()
                .id(counter)
                .data(new HashMap<>())
                .build())
            .collect(toList());
    }

    @Test
    void shouldMigrateCasesOfACaseType() {
        when(idamRepository.generateUserToken()).thenReturn(USER_TOKEN);
        CaseDetails details = mock(CaseDetails.class);
        when(details.getId()).thenReturn(1677777777L);
        List<CaseDetails> caseDetails = new ArrayList<>();
        caseDetails.add(details);
        when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, null, List.of()))
            .thenReturn(caseDetails);
        when(elasticSearchRepository.searchResultsSize(USER_TOKEN, CASE_TYPE, QUERY)).thenReturn(1);

        when(coreCaseDataService.update(eq(USER_TOKEN), eq(EVENT_ID), eq(EVENT_SUMMARY),
            eq(EVENT_DESCRIPTION), eq(CASE_TYPE), any(), eq(MIGRATION_ID)))
            .thenReturn(details);
        when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);

        caseMigrationProcessor.migrateQuery(QUERY);
        verify(coreCaseDataService, times(1))
            .update(eq(USER_TOKEN),
                eq(EVENT_ID),
                eq(EVENT_SUMMARY),
                eq(EVENT_DESCRIPTION),
                eq(CASE_TYPE),
                caseDetailsArgumentCaptor.capture(),
                eq(MIGRATION_ID));

        assertThat(caseDetailsArgumentCaptor.getValue().getId()).isEqualTo(1677777777L);
    }

    @Test
    void shouldMigrateOnlyLimitedNumberOfCases() {
        when(idamRepository.generateUserToken()).thenReturn(USER_TOKEN);
        CaseDetails details = mock(CaseDetails.class);
        when(details.getId()).thenReturn(1L);
        CaseDetails details1 = mock(CaseDetails.class);
        when(details1.getId()).thenReturn(2L);
        List<CaseDetails> caseDetails = new ArrayList<>();
        caseDetails.add(details);
        caseDetails.add(details1);
        when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, null, List.of()))
            .thenReturn(caseDetails);
        when(elasticSearchRepository.searchResultsSize(USER_TOKEN, CASE_TYPE, QUERY)).thenReturn(2);

        when(coreCaseDataService.update(eq(USER_TOKEN), eq(EVENT_ID), eq(EVENT_SUMMARY), eq(EVENT_DESCRIPTION),
            eq(CASE_TYPE), any(), eq(MIGRATION_ID)))
            .thenReturn(details);
        when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);

        caseMigrationProcessor.migrateQuery(QUERY);
        verify(coreCaseDataService, times(2))
            .update(eq(USER_TOKEN),
                eq(EVENT_ID),
                eq(EVENT_SUMMARY),
                eq(EVENT_DESCRIPTION),
                eq(CASE_TYPE),
                caseDetailsArgumentCaptor.capture(),
                eq(MIGRATION_ID));

        assertThat(caseDetailsArgumentCaptor.getAllValues().stream().map(CaseDetails::getId))
            .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void shouldThrowExceptionWhenCaseTypeIsNull() {
        caseMigrationProcessor = new CaseMigrationProcessor(coreCaseDataService,
            elasticSearchRepository,
            idamRepository,
            dataMigrationService,
            10,
            DEFAULT_THREAD_LIMIT,
            0,
            "Test",
            CASE_JURISDICTION,
            null,
            false,
            300);
        assertThatThrownBy(() -> caseMigrationProcessor.migrateQuery(BooleanQuery.builder().build()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowExceptionWhenQueryIsNull() {
        caseMigrationProcessor = new CaseMigrationProcessor(coreCaseDataService,
            elasticSearchRepository,
            idamRepository,
            dataMigrationService,
            10,
            DEFAULT_THREAD_LIMIT,
            0,
            "Test",
            CASE_JURISDICTION,
            CASE_TYPE,
            false,
            300);
        assertThatThrownBy(() -> caseMigrationProcessor.migrateQuery(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldTimeoutIfSet() {
        when(idamRepository.generateUserToken()).thenReturn(USER_TOKEN);
        CaseDetails details = mock(CaseDetails.class);
        when(details.getId()).thenReturn(1L);
        CaseDetails details1 = mock(CaseDetails.class);
        when(details1.getId()).thenReturn(2L);
        List<CaseDetails> caseDetails = new ArrayList<>();
        caseDetails.add(details);
        caseDetails.add(details1);

        caseMigrationProcessor = new CaseMigrationProcessor(coreCaseDataService,
            elasticSearchRepository,
            idamRepository,
            dataMigrationService,
            DEFAUT_QUERY_SIZE,
            1,                  // single thread - one migration at a time
            20,                 // 20 seconds between each migration
            MIGRATION_ID,
            CASE_JURISDICTION,
            CASE_TYPE,
            false,
            10);                // timeout in 10 seconds, should only migrate one case


        when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, null, List.of()))
            .thenReturn(caseDetails);
        when(elasticSearchRepository.searchResultsSize(USER_TOKEN, CASE_TYPE, QUERY)).thenReturn(2);

        when(coreCaseDataService.update(eq(USER_TOKEN), eq(EVENT_ID), eq(EVENT_SUMMARY), eq(EVENT_DESCRIPTION),
            eq(CASE_TYPE), any(), eq(MIGRATION_ID)))
            .thenReturn(details);
        when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);
        caseMigrationProcessor.migrateQuery(QUERY);

        // Ensure only one case migrated
        verify(coreCaseDataService, times(1))
            .update(eq(USER_TOKEN),
                eq(EVENT_ID),
                eq(EVENT_SUMMARY),
                eq(EVENT_DESCRIPTION),
                eq(CASE_TYPE),
                caseDetailsArgumentCaptor.capture(),
                eq(MIGRATION_ID));

        assertThat(caseDetailsArgumentCaptor.getAllValues().stream().map(CaseDetails::getId))
            .containsExactly(1L);
    }

    @Test
    void shouldThrowExceptionWhenMigrationIdIsNull() {
        caseMigrationProcessor = new CaseMigrationProcessor(coreCaseDataService,
            elasticSearchRepository,
            idamRepository,
            dataMigrationService,
            10,
            DEFAULT_THREAD_LIMIT,
            0,
            null,
            CASE_JURISDICTION,
            CASE_TYPE,
            false,
            300);
        assertThatThrownBy(() -> caseMigrationProcessor.migrateQuery(BooleanQuery.builder().build()))
            .isInstanceOf(NullPointerException.class);
    }

    @Nested
    class MigrateCaseList {

        @Test
        void shouldMigrateOnlySelectCases() {
            List<String> caseIds = List.of("12345", "67890");

            caseMigrationProcessor.migrateList(caseIds);

            verify(coreCaseDataService, times(2))
                .update(eq(USER_TOKEN),
                    eq(EVENT_ID),
                    eq(EVENT_SUMMARY),
                    eq(EVENT_DESCRIPTION),
                    eq(CASE_TYPE),
                    any(),
                    eq(MIGRATION_ID));
        }

    }

    @Nested
    class MigrateByBatchMode {
        @Test
        void shouldThrowExceptionIfArgumentInvalid() {
            assertThatThrownBy(() ->
                caseMigrationProcessor.migrateQueryByBatch(null, null, 0))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                caseMigrationProcessor.migrateQueryByBatch(QUERY, "caseId", 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                caseMigrationProcessor.migrateQueryByBatch(QUERY, "caseId", -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldMigrateCasesUpToTheGiveBatchSize() {
            when(elasticSearchRepository.searchResultsSize(USER_TOKEN, CASE_TYPE, QUERY)).thenReturn(23);
            when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, null, List.of()))
                .thenReturn(createCaseDetails(0, DEFAUT_QUERY_SIZE));
            when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, 1, "" + (DEFAUT_QUERY_SIZE - 1),
                List.of()))
                .thenReturn(createCaseDetails(DEFAUT_QUERY_SIZE, 1));
            when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);

            int batchSize = DEFAUT_QUERY_SIZE + 1;
            caseMigrationProcessor.migrateQueryByBatch(QUERY, null, batchSize);

            verify(coreCaseDataService, times(batchSize))
                .update(eq(USER_TOKEN),
                    eq(EVENT_ID),
                    eq(EVENT_SUMMARY),
                    eq(EVENT_DESCRIPTION),
                    eq(CASE_TYPE),
                    caseDetailsArgumentCaptor.capture(),
                    eq(MIGRATION_ID));

            assertThat(caseDetailsArgumentCaptor.getAllValues().stream().map(CaseDetails::getId))
                .containsExactlyInAnyOrderElementsOf(LongStream.range(0, batchSize).boxed().toList());
        }

        @Test
        void shouldMigrateCasesUpToTheGiveBatchSizeAndSearchAfter() {
            String searchAfter = "" + (DEFAUT_QUERY_SIZE - 1);

            when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, DEFAUT_QUERY_SIZE, searchAfter, List.of()))
                .thenReturn(createCaseDetails(DEFAUT_QUERY_SIZE, 1));
            when(elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, 1, "" + DEFAUT_QUERY_SIZE, List.of()))
                .thenReturn(List.of());
            when(dataMigrationService.accepts(MIGRATION_ID)).thenReturn(obj -> true);

            int batchSize = DEFAUT_QUERY_SIZE + 1;
            caseMigrationProcessor.migrateQueryByBatch(QUERY, searchAfter, batchSize);

            verify(coreCaseDataService, times(1))
                .update(eq(USER_TOKEN),
                    eq(EVENT_ID),
                    eq(EVENT_SUMMARY),
                    eq(EVENT_DESCRIPTION),
                    eq(CASE_TYPE),
                    caseDetailsArgumentCaptor.capture(),
                    eq(MIGRATION_ID));

            assertThat(caseDetailsArgumentCaptor.getAllValues().stream().map(CaseDetails::getId))
                .contains(10L);
        }
    }
}
