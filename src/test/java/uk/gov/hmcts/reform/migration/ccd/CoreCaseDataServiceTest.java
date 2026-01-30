package uk.gov.hmcts.reform.migration.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserDetails;
import uk.gov.hmcts.reform.migration.service.DataMigrationService;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.migration.service.DataMigrationService.MIGRATION_ID_KEY;

@ExtendWith(MockitoExtension.class)
class CoreCaseDataServiceTest {

    private static final String EVENT_ID = "migrateCase";
    private static final String CASE_TYPE = "CARE_SUPERVISION_EPO";
    private static final long CASE_ID = 12345678L;
    private static final String USER_ID = "30";
    private static final String AUTH_TOKEN = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJubGJoN";
    private static final String EVENT_TOKEN = "Bearer aaaadsadsasawewewewew";
    private static final String EVENT_SUMMARY = "Migrate Case";
    private static final String EVENT_DESC = "Migrate Case";
    static final String DFPL_1124 = "DFPL-1124";

    private CoreCaseDataService underTest;

    @Mock
    CoreCaseDataApi coreCaseDataApi;

    @Mock
    private DataMigrationService<Map<String, Object>> dataMigrationService;

    @Mock
    private IdamClient idamClient;

    @Mock
    private AuthTokenGenerator authTokenGenerator;

    CaseDataContent caseDataContent;

    @BeforeEach
    void setUp() {
        underTest = new CoreCaseDataService(idamClient,
            authTokenGenerator,
            coreCaseDataApi,
            dataMigrationService);
    }

    @Test
    void shouldUpdateTheCase() {
        // given
        UserDetails userDetails = UserDetails.builder()
            .id("30")
            .email("test@test.com")
            .forename("Test")
            .surname("Surname")
            .build();

        CaseDetails caseDetails3 = createCaseDetails();

        setupMocks(userDetails, caseDetails3.getData());

        //when
        CaseDetails update = underTest.update(AUTH_TOKEN, EVENT_ID, EVENT_SUMMARY, EVENT_DESC, CASE_TYPE, caseDetails3,
            DFPL_1124);
        //then
        assertThat(update.getId()).isEqualTo(CASE_ID);
        assertThat(update.getData().get(MIGRATION_ID_KEY)).isEqualTo(DFPL_1124);

        verify(dataMigrationService).accepts(DFPL_1124);
        verify(dataMigrationService).migrate(caseDetails3, DFPL_1124);
        verify(coreCaseDataApi).startEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, "30",
            null, CASE_TYPE, String.valueOf(CASE_ID), EVENT_ID);
        verify(coreCaseDataApi).submitEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, USER_ID, null,
            CASE_TYPE, String.valueOf(CASE_ID), true, caseDataContent);
    }

    @Test
    void shouldNotUpdateTheCaseWhenMigrationConditionIsNotMet() {

        // given
        UserDetails userDetails = UserDetails.builder()
            .id("30")
            .email("test@test.com")
            .forename("Test")
            .surname("Surname")
            .build();

        CaseDetails caseDetails3 = createCaseDetails();

        when(idamClient.getUserDetails(AUTH_TOKEN)).thenReturn(userDetails);

        when(authTokenGenerator.generate()).thenReturn(AUTH_TOKEN);
        StartEventResponse startEventResponse = StartEventResponse.builder()
            .eventId(EVENT_ID)
            .token(EVENT_TOKEN)
            .caseDetails(caseDetails3)
            .build();

        when(coreCaseDataApi.startEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, "30",
            null, CASE_TYPE, String.valueOf(CASE_ID), EVENT_ID
        ))
            .thenReturn(startEventResponse);

        when(dataMigrationService.accepts(any()))
            .thenReturn(caseDetails1 -> false);

        //when
        CaseDetails update = underTest.update(AUTH_TOKEN, EVENT_ID, EVENT_SUMMARY, EVENT_DESC, CASE_TYPE, caseDetails3,
            DFPL_1124);
        //then
        assertThat(update).isNull();
        verify(dataMigrationService).accepts(any());
        verify(dataMigrationService, never()).migrate(caseDetails3, DFPL_1124);
        verify(coreCaseDataApi).startEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, "30",
            null, CASE_TYPE, String.valueOf(CASE_ID), EVENT_ID);
        verify(coreCaseDataApi, never()).submitEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, USER_ID, null,
            CASE_TYPE, String.valueOf(CASE_ID), true, caseDataContent);
    }

    private CaseDetails createCaseDetails() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put(MIGRATION_ID_KEY, DFPL_1124);

        return CaseDetails.builder()
            .id(CoreCaseDataServiceTest.CASE_ID)
            .data(data)
            .build();
    }

    private void setupMocks(UserDetails userDetails, Map<String, Object> data) {
        when(idamClient.getUserDetails(AUTH_TOKEN)).thenReturn(userDetails);

        when(authTokenGenerator.generate()).thenReturn(AUTH_TOKEN);

        CaseDetails caseDetails = CaseDetails.builder()
            .id(CASE_ID)
            .data(data)
            .build();

        when(dataMigrationService.accepts(any()))
            .thenReturn(caseDetails1 -> true);

        StartEventResponse startEventResponse = StartEventResponse.builder()
            .eventId(EVENT_ID)
            .token(EVENT_TOKEN)
            .caseDetails(caseDetails)
            .build();

        when(dataMigrationService.migrate(caseDetails, DFPL_1124))
            .thenReturn(data);

        when(coreCaseDataApi.startEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, "30",
                                                     null, CASE_TYPE, String.valueOf(CASE_ID), EVENT_ID
        ))
            .thenReturn(startEventResponse);

        caseDataContent = CaseDataContent.builder()
            .event(Event.builder()
                       .id(EVENT_ID)
                       .description(EVENT_DESC)
                       .summary(EVENT_SUMMARY)
                       .build())
            .eventToken(EVENT_TOKEN)
            .data(data)
            .ignoreWarning(false)
            .build();


        when(coreCaseDataApi.submitEventForCaseWorker(AUTH_TOKEN, AUTH_TOKEN, USER_ID, null,
                                                      CASE_TYPE, String.valueOf(CASE_ID), true, caseDataContent
        )).thenReturn(caseDetails);
    }
}
