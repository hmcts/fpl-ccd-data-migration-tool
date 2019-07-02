package uk.gov.hmcts.reform.fpl.ccddatamigration.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.fpl.ccddatamigration.service.MigrationService;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@Slf4j
@Component
@Configuration
@PropertySource("classpath:application.properties")
public class DataMigrationProcessor implements CommandLineRunner {

    @Value("${idam.username}")
    private String idamUsername;

    @Value("${idam.password}")
    private String idamPassword;

    @Value("${ccd.jurisdictionid}")
    private String jurisdictionId;

    @Value("${ccd.casetype}")
    private String caseType;

    @Value("${ccd.caseId}")
    private String ccdCaseId;

    @Value("${ccd.dryrun}")
    private boolean dryRun;

    @Value("${log.debug}")
    private boolean debugEnabled;

    @Autowired
    private IdamClient idamClient;

    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    @Autowired
    private MigrationService migrationService;

    public static void main(String[] args) {
        SpringApplication.run(DataMigrationProcessor.class, args);
    }

    @Override
    public void run(String... args) {

        try {
            if (debugEnabled) {
                log.info("Start processing cases");
            }
            String userToken = idamClient.authenticateUser(idamUsername, idamPassword);
            if (debugEnabled) {
                log.info("  userToken  : {}", userToken);
            }
            String s2sToken = authTokenGenerator.generate();
            if (debugEnabled) {
                log.info("  s2sToken : {}", s2sToken);
            }
            String userId = idamClient.getUserDetails(userToken).getId();
            if (debugEnabled) {
                log.info("  userId  : {}", userId);
            }

            if (ccdCaseId != null && !ccdCaseId.isBlank()) {
                log.info("migrate case, caseId  {}", ccdCaseId);
                migrationService.processSingleCase(userToken, s2sToken, ccdCaseId);
            } else {
                migrationService.processAllTheCases(userToken, s2sToken, userId, jurisdictionId, caseType);
            }
            log.info("Migrated Cases {} ",
                migrationService.getMigratedCases() != null && !migrationService.getMigratedCases().isBlank() ? migrationService.getMigratedCases() : "NONE");

            log.info("-----------------------------");
            log.info("Data migration completed");
            log.info("-----------------------------");
            log.info("Total number of cases: " + migrationService.getTotalNumberOfCases());
            log.info("Total migrations performed: " + migrationService.getTotalMigrationsPerformed());
            log.info("-----------------------------");
            log.info("Failed Cases {}",
                migrationService.getFailedCases() != null && !migrationService.getFailedCases().isBlank() ? migrationService.getFailedCases() : "NONE");

        } catch (Throwable e) {
            log.error("Migration failed with the following reason :", e.getMessage());
            e.printStackTrace();
        }
    }

}