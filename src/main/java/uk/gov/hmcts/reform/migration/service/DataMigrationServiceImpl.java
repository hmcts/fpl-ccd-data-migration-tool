package uk.gov.hmcts.reform.migration.service;

import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class DataMigrationServiceImpl implements DataMigrationService<Object> {

    private static final Set<Long> IDS = Set.of(1547477328203284L, 1623415086491615L, 1623231176358626L);

    @Override
    public Predicate<CaseDetails> accepts() {
        return caseDetails -> IDS.contains(caseDetails.getId());
    }

    @Override
    public Object migrate(Map<String, Object> data) {
        return Map.<String, Object>of("migrationId", "FPLA-3214");
    }
}
