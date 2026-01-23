package uk.gov.hmcts.reform.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.SearchResult;
import uk.gov.hmcts.reform.migration.ccd.CoreCaseDataService;
import uk.gov.hmcts.reform.migration.query.BooleanQuery;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.query.ExistsQuery;
import uk.gov.hmcts.reform.migration.query.Filter;
import uk.gov.hmcts.reform.migration.repository.ElasticSearchRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.migration.repository.ElasticSearchRepository.SORT_BY_REF;

@ExtendWith(MockitoExtension.class)
class ElasticSearchRepositoryTest {

    private static final String USER_TOKEN = "TEST_USER_TOKEN";

    private static final String CASE_TYPE = "CASE_TYPE";

    private static final String AUTH_TOKEN = "Test_Auth_Token";

    private static final EsQuery QUERY = BooleanQuery.builder()
        .filter(Filter.builder()
            .clauses(List.of(ExistsQuery.of("data.court")))
            .build())
        .build();

    private static final String INITIAL_QUERY = QUERY.toQueryContext(1, SORT_BY_REF, List.of()).toString();
    private static final String AFTER_QUERY = QUERY.toQueryContext(1, 1, SORT_BY_REF).toString();

    private ElasticSearchRepository elasticSearchRepository;

    @Mock
    private CoreCaseDataService coreCaseDataService;

    @BeforeEach
    void setUp() {
        elasticSearchRepository = new ElasticSearchRepository(coreCaseDataService);
    }

    @Test
    void shouldReturnSearchResultsForCaseTypeElasticSearch() {
        SearchResult searchResult = mock(SearchResult.class);
        when(coreCaseDataService.searchCases(
            USER_TOKEN,
            CASE_TYPE,
            QUERY.toQueryContext(1, SORT_BY_REF, List.of()).toString()
        )).thenReturn(searchResult);
        List<CaseDetails> caseDetails = elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, 1,
            null, List.of());

        assertThat(caseDetails)
            .isNotNull()
            .isEmpty();
    }

    @Test
    void shouldNotReturnCaseDetailsForCaseTypeWhenSearchResultIsNull() {
        when(coreCaseDataService.searchCases(
            USER_TOKEN,
            CASE_TYPE,
            QUERY.toQueryContext(1, SORT_BY_REF, List.of()).toString()
        )).thenReturn(null);
        List<CaseDetails> caseDetails = elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, 1,
            null, List.of());

        assertThat(caseDetails)
            .isNotNull()
            .isEmpty();
    }

    @Test
    void shouldReturnSearchResultsAndCaseDetailsForCaseTypeElasticSearch() {
        SearchResult searchResult = mock(SearchResult.class);
        List<CaseDetails> caseDetails = new ArrayList<>();
        CaseDetails details = mock(CaseDetails.class);
        caseDetails.add(details);
        when(searchResult.getCases()).thenReturn(caseDetails);
        when(coreCaseDataService.searchCases(
            USER_TOKEN,
            CASE_TYPE,
            QUERY.toQueryContext(1, SORT_BY_REF, List.of()).toString()
        )).thenReturn(searchResult);

        List<CaseDetails> returnCaseDetails = elasticSearchRepository.search(USER_TOKEN, CASE_TYPE, QUERY, 1,
            null, List.of());
        assertThat(returnCaseDetails).isNotNull();

        verify(coreCaseDataService, times(1)).searchCases(USER_TOKEN,
            CASE_TYPE,
            INITIAL_QUERY);
        assertThat(returnCaseDetails).hasSize(1);
    }
}
