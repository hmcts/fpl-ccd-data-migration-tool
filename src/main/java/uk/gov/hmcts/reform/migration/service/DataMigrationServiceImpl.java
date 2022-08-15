package uk.gov.hmcts.reform.migration.service;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

@Component
public class DataMigrationServiceImpl implements DataMigrationService<Map<String, Object>> {

    private static final String MIGRATION_ID = "DFPL-702";

    @Override
    public Predicate<CaseDetails> accepts() {
        return new Predicate<CaseDetails>() {
            @Override
            public boolean test(CaseDetails caseDetails) {
                System.out.println("caseDetails.getData().containsKey(\"SearchCriteria\") = " +
                        caseDetails.getData().containsKey("SearchCriteria")
                    );
                return !caseDetails.getData().containsKey("SearchCriteria");
            }
        };
    }

    @Override
    public Map<String, Object> migrate(Map<String, Object> data) {
        /*
         Populate a map here with data that wants to be present when connecting with the callback service.

         With the current implementation of the migration controller in
         https://github.com/hmcts/fpl-ccd-configuration/blob/master/service/src/main/java/uk/gov/hmcts/reform/fpl/controllers/support/MigrateCaseController.java
         we require a migration id to be passed to then pass to the appropriate method in the controller.
         The controller then cleans up this id so that it is no longer present in the case data.
        */
        return Map.of("migrationId", MIGRATION_ID);
    }
}
