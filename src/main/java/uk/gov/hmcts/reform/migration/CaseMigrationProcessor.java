package uk.gov.hmcts.reform.migration;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.domain.exception.CaseMigrationSkippedException;
import uk.gov.hmcts.reform.migration.ccd.CoreCaseDataService;
import uk.gov.hmcts.reform.migration.query.EsQuery;
import uk.gov.hmcts.reform.migration.repository.ElasticSearchRepository;
import uk.gov.hmcts.reform.migration.repository.IdamRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static java.math.RoundingMode.UP;
import static java.time.LocalDateTime.now;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.util.ObjectUtils.isEmpty;

@Slf4j
@Component
public class CaseMigrationProcessor {
    public static final String EVENT_ID = "migrateCase";
    public static final String EVENT_SUMMARY = "Migrate Case";
    public static final String EVENT_DESCRIPTION = "Migrate Case";
    public static final String LOG_STRING = "-----------------------------------------";

    private final CoreCaseDataService coreCaseDataService;
    private final ElasticSearchRepository elasticSearchRepository;
    private final IdamRepository idamRepository;
    private final int defaultQuerySize;
    private final int defaultThreadLimit;
    private final int defaultThreadDelay;
    private final int timeout;
    private final String migrationId;
    private final String caseType;
    private final String jurisdiction;

    private final ForkJoinPool threadPool;

    @Getter
    private final List<Long> migratedCases = new ArrayList<>();
    @Getter
    private final List<Long> skippedCases = new ArrayList<>();
    @Getter
    private final List<Long> failedCases = new ArrayList<>();

    private final ConcurrentLinkedQueue<Long> casesToMigrate = new ConcurrentLinkedQueue<>();

    private boolean finishedLoading = false;

    private LocalDateTime startTime = now();

    private boolean retryFailures;

    //@Autowired
    public CaseMigrationProcessor(CoreCaseDataService coreCaseDataService,
                                  ElasticSearchRepository elasticSearchRepository,
                                  IdamRepository idamRepository,
                                  @Value("${default.query.size}") int defaultQuerySize,
                                  @Value("${default.thread.limit:8}") int defaultThreadLimit,
                                  @Value("${default.thread.delay:0}") int defaultThreadDelay,
                                  @Value("${case-migration.processing.id}") String migrationId,
                                  @Value("${migration.jurisdiction}") String jurisdiction,
                                  @Value("${migration.caseType}") String caseType,
                                  @Value("${case-migration.retry_failures}") boolean retryFailures,
                                  @Value("${case-migration.timeout:7200}") int timeout) {
        this.coreCaseDataService = coreCaseDataService;
        this.elasticSearchRepository = elasticSearchRepository;
        this.idamRepository = idamRepository;
        this.defaultQuerySize = defaultQuerySize;
        this.defaultThreadLimit = defaultThreadLimit;
        this.defaultThreadDelay = defaultThreadDelay * 1000;
        this.migrationId = migrationId;
        this.jurisdiction = jurisdiction;
        this.caseType = caseType;
        this.retryFailures = retryFailures;
        this.threadPool = new ForkJoinPool(defaultThreadLimit);
        this.timeout = timeout;

        setupProcessor(true);
    }

    public void setupProcessor(boolean firstTry) {
        log.info("Setting up migration tool, timeout: {}s, thread delay: {}s, num threads: {}",
            this.timeout, this.defaultThreadDelay, this.defaultThreadLimit);

        this.startTime = now();
        this.getFailedCases().clear();
        this.getMigratedCases().clear();
        this.getSkippedCases().clear();

        this.finishedLoading = false;

        String userToken =  idamRepository.generateUserToken();
        // Setup consumers
        for (int i = 0; i < defaultThreadLimit; i++) {
            threadPool.execute(() -> worker(caseType, jurisdiction, userToken));
        }

        if (!firstTry) {
            this.retryFailures = false;
        }
    }


    @SneakyThrows
    private void worker(String caseType, String jurisdiction, String userToken) {
        while (!finishedLoading || !casesToMigrate.isEmpty()) {
            // check for content
            Long caseId = casesToMigrate.poll();
            if (!isEmpty(caseId)) {
                // we've removed our caseId from the queue - now need to process it
                try {
                    coreCaseDataService.update(userToken,
                        EVENT_ID,
                        EVENT_SUMMARY,
                        EVENT_DESCRIPTION,
                        caseType,
                        CaseDetails.builder()
                            .id(caseId)
                            .jurisdiction(jurisdiction)
                            .build(),
                        this.migrationId
                    );
                    log.info("Completed migrating case {}", caseId);
                    migratedCases.add(caseId);

                    // artificially slow down the migration tool if needed
                    if (defaultThreadDelay > 0) {
                        Thread.sleep(defaultThreadDelay);
                    }
                } catch (CaseMigrationSkippedException e) {
                    log.info("Skipped migrating case {}, {}", caseId, e.getMessage());
                    skippedCases.add(caseId);
                } catch (Exception e) {
                    log.error("Failed migrating case {}", caseId, e);
                    failedCases.add(caseId);
                }
            } else {
                // polling for 1s as no caseId polled yet
                Thread.sleep(1000);
            }
        }
    }

    @SneakyThrows
    public void migrateQuery(EsQuery query) {
        requireNonNull(query);
        requireNonNull(caseType);
        requireNonNull(migrationId);

        String userToken =  idamRepository.generateUserToken();

        // Get total cases to migrate
        int total;
        try {
            total = elasticSearchRepository.searchResultsSize(userToken, this.caseType, query);
            log.info("Found {} cases to migrate", total);
        } catch (Exception e) {
            log.error("Could not determine the number of cases to search for due to {}",
                e.getMessage(), e
            );
            log.info("Migration finished unsuccessfully.");
            return;
        }

        // Setup ESQuery provider to fill up the queue
        int pages = paginate(total);
        log.debug("Found {} pages", pages);
        String searchAfter = null;
        boolean complete = false;
        int page = 0;
        while (!complete) {
            try {
                List<CaseDetails> cases = elasticSearchRepository.search(userToken, caseType, query, defaultQuerySize,
                    searchAfter);

                if (cases.isEmpty()) {
                    complete = true;
                    continue;
                }

                searchAfter = cases.get(cases.size() - 1).getId().toString();

                // add to queue
                cases.stream()
                    .map(CaseDetails::getId)
                    .forEach(casesToMigrate::add);

                page++;
            } catch (Exception e) {
                log.error("Could not search for page {}", page, e);
            }
        }

        finishedLoading = true;

        // Finalise + wait for the queue to finish processing
        boolean timedOut = !threadPool.awaitQuiescence(timeout, SECONDS);
        if (timedOut) {
            log.error("Timed out after {} seconds", timeout);
        }

        publishStats(startTime);

        if (retryFailures && this.getFailedCases().size() > 0) {
            List<String> toRetry = new ArrayList<>(this.getFailedCases()).stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            // reset migration tool, with no more retries allowed
            this.setupProcessor(false);

            // migrate the failed cases
            this.migrateList(toRetry);
        }
    }

    @SneakyThrows
    public void migrateList(List<String> caseIds) {
        requireNonNull(caseIds);

        if (caseIds.isEmpty()) {
            log.error("No case ids found for migration {}, aborting", migrationId);
            return;
        } else {
            log.info("Found {} cases to migrate", caseIds.size());
        }

        // Add them to the queue
        casesToMigrate.addAll(caseIds.stream().map(Long::parseLong).collect(Collectors.toList()));
        this.finishedLoading = true;

        // Wait for the threadpool to finish
        boolean timedOut = !threadPool.awaitQuiescence(timeout, SECONDS);
        if (timedOut) {
            log.error("Timed out after {} seconds", timeout);
        }

        publishStats(startTime);

        if (retryFailures && this.getFailedCases().size() > 0) {
            List<String> toRetry = new ArrayList<>(this.getFailedCases()).stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            // reset migration tool, with no more retries allowed
            this.setupProcessor(false);

            // migrate the failed cases
            this.migrateList(toRetry);
        }
    }

    private int paginate(int total) {
        return new BigDecimal(total).divide(new BigDecimal(defaultQuerySize), UP).intValue();
    }

    private void publishStats(LocalDateTime startTime) {
        log.info(LOG_STRING);
        log.info(
            "FPLA Data migration completed: Total number of processed cases: {}",
            getMigratedCases().size() + getFailedCases().size()
        );

        String[] task = {"Migrated", "migrations"};
        if ("DFPL-1124Rollback".equals(migrationId)) {
            task = new String[]{"Rolled back", "rollbacks"};
        }

        if (getMigratedCases().isEmpty()) {
            log.info("{} cases: NONE ", task[0]);
        } else {
            log.info(
                "Total number of {} performed: {} ",
                task[1],
                getMigratedCases().size()
            );
        }

        if (getSkippedCases().isEmpty()) {
            log.info("Skipped cases: NONE ");
        } else {
            log.info("Skipped count:{}, cases: {} ", getSkippedCases().size(), getSkippedCases());
        }

        if (getFailedCases().isEmpty()) {
            log.info("Failed cases: NONE ");
        } else {
            log.info("Failed count:{}, cases: {} ", getFailedCases().size(), getFailedCases());
        }

        log.info("Data migration start at {} and completed at {}", startTime, now());
    }

}
