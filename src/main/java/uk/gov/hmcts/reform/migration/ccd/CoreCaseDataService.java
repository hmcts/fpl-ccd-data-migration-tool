package uk.gov.hmcts.reform.migration.ccd;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.SearchResult;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserDetails;
import uk.gov.hmcts.reform.migration.auth.AuthUtil;
import uk.gov.hmcts.reform.migration.service.DataMigrationService;

import java.util.Map;

import static uk.gov.hmcts.reform.migration.service.DataMigrationService.MIGRATION_ID_KEY;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class CoreCaseDataService {

    private final IdamClient idamClient;
    private final AuthTokenGenerator authTokenGenerator;
    private final CoreCaseDataApi coreCaseDataApi;
    private final DataMigrationService<Map<String, Object>> dataMigrationService;

    public CaseDetails update(String authorisation, String eventId,
                              String eventSummary,
                              String eventDescription,
                              String caseType,
                              CaseDetails caseDetails,
                              String migrationId) {
        String caseId = String.valueOf(caseDetails.getId());
        UserDetails userDetails = idamClient.getUserDetails(AuthUtil.getBearerToken(authorisation));

        StartEventResponse startEventResponse = coreCaseDataApi.startEventForCaseWorker(
            AuthUtil.getBearerToken(authorisation),
            authTokenGenerator.generate(),
            userDetails.getId(),
            caseDetails.getJurisdiction(),
            caseType,
            caseId,
            eventId);

        CaseDetails updatedCaseDetails = startEventResponse.getCaseDetails();

        if (dataMigrationService.accepts(migrationId).test(updatedCaseDetails)) {
            log.info("Initiating updating case {}", updatedCaseDetails.getId());

            Map<String, Object> migratedFields = dataMigrationService.migrate(
                updatedCaseDetails,
                migrationId);
            migratedFields.put(MIGRATION_ID_KEY, migrationId);

            CaseDataContent caseDataContent = CaseDataContent.builder()
                .eventToken(startEventResponse.getToken())
                .event(
                    Event.builder()
                        .id(startEventResponse.getEventId())
                        .summary(eventSummary)
                        .description(eventDescription)
                        .build()
                ).data(migratedFields)
                .build();
            return coreCaseDataApi.submitEventForCaseWorker(
                AuthUtil.getBearerToken(authorisation),
                authTokenGenerator.generate(),
                userDetails.getId(),
                updatedCaseDetails.getJurisdiction(),
                caseType,
                caseId,
                true,
                caseDataContent);
        } else {
            log.info("For case id {}, court is {} and dfjArea is {}",
                caseDetails.getId(),
                caseDetails.getData().get("court"),
                caseDetails.getData().get("dfjArea")
            );
            return null;
        }
    }

    public SearchResult searchCases(String userToken, String caseType, String query) {
        return coreCaseDataApi.searchCases(userToken, authTokenGenerator.generate(), caseType, query);
    }
}
