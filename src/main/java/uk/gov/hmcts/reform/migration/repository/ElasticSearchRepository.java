package uk.gov.hmcts.reform.migration.repository;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.SearchResult;
import uk.gov.hmcts.reform.migration.ccd.CoreCaseDataService;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.query.Sort;
import uk.gov.hmcts.reform.migration.query.SortOrder;
import uk.gov.hmcts.reform.migration.query.SortQuery;

import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.springframework.util.ObjectUtils.isEmpty;

@Repository
@Slf4j
public class ElasticSearchRepository {

    private final CoreCaseDataService ccdService;

    public static final Sort SORT_BY_REF = Sort.builder()
        .clauses(List.of(
            SortQuery.of("reference.keyword", SortOrder.DESC)
        ))
        .build();

    @Autowired
    public ElasticSearchRepository(CoreCaseDataService ccdService) {
        this.ccdService = ccdService;
    }

    public int searchResultsSize(String userToken, String caseType, EsQuery query) {
        requireNonNull(query);
        return search(userToken, caseType, query.toQueryContext(1, 0).toString()).getTotal();
    }

    public SearchResult search(String userToken, String caseType, String query) {
        return ccdService.searchCases(userToken, caseType, query);
    }

    @SneakyThrows
    public List<CaseDetails> search(String userToken, String caseType, EsQuery query, int size, String after,
                                    List<String> extraSourceField) {
        requireNonNull(query);
        SearchResult result = null;

        // Attempt ES search with 20 retries
        boolean completed = false;
        int retries = 0;
        while (!completed && retries < 20) {
            try {
                String queryStr = !isEmpty(after)
                    ? query.toQueryContext(size, after, SORT_BY_REF, extraSourceField).toString()
                    : query.toQueryContext(size, SORT_BY_REF, extraSourceField).toString();

                result = search(userToken, caseType, queryStr);
                completed = true;
            } catch (Exception e) {
                // let CCD recover if timeouts are happening
                Thread.sleep(1000);
                log.error("Failed to get page retry = {}", retries, e);
                retries++;
            }
        }

        if (isEmpty(result)) {
            log.error("ES Query returned no cases after 20 retries, {}, {}",
                query.toQueryContext(size, SORT_BY_REF, extraSourceField), after);
            return List.of();
        }
        return result.getCases();
    }
}
