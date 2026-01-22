package uk.gov.hmcts.reform.migration.service;

import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.migration.query.EsQuery;

import java.util.List;
import java.util.function.Predicate;

public interface DataMigrationService<T> {
    String MIGRATION_ID_KEY = "migrationId";

    Predicate<CaseDetails> accepts(String migrationId);

    T migrate(CaseDetails caseDetails, String migrationId);

    void validateMigrationId(String migrationId);

    EsQuery getQuery(String migrationId);

    List<String> getExtraSourceFields(String migrationId);
}
