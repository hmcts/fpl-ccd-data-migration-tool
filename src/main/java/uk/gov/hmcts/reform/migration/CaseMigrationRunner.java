package uk.gov.hmcts.reform.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.migration.configuration.CaseIdListConfiguration;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.service.DataMigrationService;

import java.util.List;

@Slf4j
@SpringBootApplication(scanBasePackages = {
    "uk.gov.hmcts.reform.migration",
    "uk.gov.hmcts.reform.domain",
    "uk.gov.hmcts.reform.fpl"
}, scanBasePackageClasses = {IdamClient.class})
@EnableFeignClients(basePackages = {
    "uk.gov.hmcts.reform.idam.client",
    "uk.gov.hmcts.reform.ccd.client",
    "uk.gov.hmcts.reform.authorisation",
})
public class CaseMigrationRunner implements CommandLineRunner {

    @Autowired
    private CaseMigrationProcessor caseMigrationProcessor;
    @Autowired
    private DataMigrationService dataMigrationService;

    @Autowired
    private CaseIdListConfiguration caseIdListConfiguration;

    @Value("${case-migration.processing.id}") String migrationId;

    @Value("${case-migration.enabled}") boolean enabled;

    @Value("${case-migration.use_case_id_mapping:false}") boolean useIdList;

    public static void main(String[] args) {
        SpringApplication.run(CaseMigrationRunner.class, args);
    }

    @Override
    public void run(String... args) {
        try {
            log.info("Job is triggered: {}", enabled);
            if (!enabled) {
                return;
            }
            log.info("Migration ID is {}", migrationId);
            dataMigrationService.validateMigrationId(migrationId);
            if (useIdList) {
                // Do ID List Migration
                List<String> caseIds = caseIdListConfiguration.getCaseIds(migrationId);
                caseMigrationProcessor.migrateList(caseIds);
            } else {
                // Do ESQuery based migration
                EsQuery query = dataMigrationService.getQuery(migrationId);
                caseMigrationProcessor.migrateQuery(query);
            }
        } catch (Exception e) {
            log.error("Migration failed with the following reason: {}", e.getMessage(), e);
        }
    }
}
