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
import uk.gov.hmcts.reform.migration.service.DataMigrationService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.math.RoundingMode.UP;
import static java.time.LocalDateTime.now;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.util.ObjectUtils.isEmpty;

@Slf4j
@Component
public class CaseMigrationProcessor {
    public static final String EVENT_ID = "migrateCase";
    public static final String EVENT_SUMMARY = "Migrate Case";
    public static final String EVENT_DESCRIPTION = "Migrate Case";
    public static final String CASE_FLAGS_MIGRATION_EVENT_ID = "migrateCaseFlags";
    public static final String CASE_FLAGS_MIGRATION_EVENT_SUMMARY = "Migrate Case Flags";
    public static final String CASE_FLAGS_MIGRATION_EVENT_DESCRIPTION = "Migrate Case Flags";
    public static final String CASE_FLAGS_ROLLBACK_EVENT_ID = "rollbackCaseFlags";
    public static final String CASE_FLAGS_ROLLBACK_EVENT_SUMMARY = "Rollback Case Flags";
    public static final String CASE_FLAGS_ROLLBACK_EVENT_DESCRIPTION = "Rollback Case Flags";
    public static final String LOG_STRING = "-----------------------------------------";

    private final CoreCaseDataService coreCaseDataService;
    private final ElasticSearchRepository elasticSearchRepository;
    private final IdamRepository idamRepository;
    private final DataMigrationService<Map<String, Object>> dataMigrationService;
    private final int defaultQuerySize;
    private final int defaultThreadLimit;
    private final int defaultThreadDelay;
    private final int timeout;
    private final String migrationId;
    private final List<String> targetCaseIds;
    private final List<String> caseIdsToSkip;
    private final String caseType;
    private final String jurisdiction;

    private final ForkJoinPool threadPool;

    @Getter
    private final ConcurrentLinkedQueue<Long> migratedCases = new ConcurrentLinkedQueue<>();
    @Getter
    private final ConcurrentLinkedQueue<Long> skippedCases = new ConcurrentLinkedQueue<>();
    @Getter
    private final ConcurrentLinkedQueue<Long> failedCases = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<Long> casesToMigrate = new ConcurrentLinkedQueue<>();

    private boolean finishedLoading = false;

    private LocalDateTime startTime = now();

    private boolean retryFailures;
    private final boolean caseFlagsV21Migration;
    private final boolean migrationRollback;

    //@Autowired
    public CaseMigrationProcessor(CoreCaseDataService coreCaseDataService,
                                  ElasticSearchRepository elasticSearchRepository,
                                  IdamRepository idamRepository,
                                  DataMigrationService<Map<String, Object>> dataMigrationService,
                                  @Value("${default.query.size}") int defaultQuerySize,
                                  @Value("${default.thread.limit:8}") int defaultThreadLimit,
                                  @Value("${default.thread.delay:0}") int defaultThreadDelay,
                                  @Value("${case-migration.processing.id}") String migrationId,
                                  @Value("${targetCaseIds:}") String targetCaseIds,
                                  @Value("${caseIdsToSkip:}") String caseIdsToSkip,
                                  @Value("${migration.jurisdiction}") String jurisdiction,
                                  @Value("${migration.caseType}") String caseType,
                                  @Value("${case-migration.retry_failures}") boolean retryFailures,
                                  @Value("${case-migration.case_flags_v2_1:false}") boolean caseFlagsV21Migration,
                                  @Value("${case-migration.rollback:false}") boolean migrationRollback,
                                  @Value("${case-migration.timeout:7200}") int timeout) {
        this.coreCaseDataService = coreCaseDataService;
        this.elasticSearchRepository = elasticSearchRepository;
        this.idamRepository = idamRepository;
        this.dataMigrationService = dataMigrationService;
        this.defaultQuerySize = defaultQuerySize;
        this.defaultThreadLimit = defaultThreadLimit;
        this.defaultThreadDelay = defaultThreadDelay * 1000;
        this.migrationId = migrationId;
        this.targetCaseIds = parseTargetCaseIds(targetCaseIds);
        this.caseIdsToSkip = parseTargetCaseIds(caseIdsToSkip);
        this.jurisdiction = jurisdiction;
        this.caseType = caseType;
        this.retryFailures = retryFailures;
        this.caseFlagsV21Migration = caseFlagsV21Migration;
        this.migrationRollback = migrationRollback;
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
                        getEventId(),
                        getEventSummary(),
                        getEventDescription(),
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
        final List<String> extraSourceField = dataMigrationService.getExtraSourceFields(migrationId);

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
                    searchAfter, extraSourceField);

                if (cases.isEmpty()) {
                    complete = true;
                    continue;
                }

                searchAfter = cases.get(cases.size() - 1).getId().toString();

                addCasesToMigrateQueue(cases);

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
    public void migrateQueryByBatch(EsQuery query, String searchAfter, int batchSize) {
        log.info("Batch mode on!");
        requireNonNull(query);
        requireNonNull(caseType);
        requireNonNull(migrationId);
        final List<String> extraSourceField = dataMigrationService.getExtraSourceFields(migrationId);

        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than 0");
        }

        String userToken =  idamRepository.generateUserToken();
        if (isBlank(searchAfter)) {
            log.warn("searchAfter is blank, will migrate the first batch");
            // Get total cases to migrate if this is the first batch
            try {
                int total = elasticSearchRepository.searchResultsSize(userToken, this.caseType, query);
                log.info("Found {} cases to migrate in total, but batch size is {} ", total, batchSize);
            } catch (Exception e) {
                log.error("Could not determine the number of cases to search for due to {}",
                    e.getMessage(), e
                );
                log.info("Migration finished unsuccessfully.");
                return;
            }
        } else {
            log.info("Batch continue with search_after: {}", searchAfter);
        }

        // Setup ESQuery provider to fill up the queue
        int pages = paginate(batchSize);
        log.debug("Found {} pages", pages);
        boolean complete = false;
        int page = 0;
        int numberOfCasesQueried = 0;
        while (!complete) {
            try {
                int querySize = Math.min(defaultQuerySize, batchSize - (page * defaultQuerySize));

                if (querySize <= 0) {
                    complete = true;
                    continue;
                }
                log.info("Querying page {}, size {}, searchAfter {}", page, querySize, searchAfter);
                List<CaseDetails> cases = elasticSearchRepository.search(userToken, caseType, query, querySize,
                    searchAfter, extraSourceField);

                if (cases.isEmpty()) {
                    complete = true;
                    continue;
                }

                numberOfCasesQueried += cases.size();
                searchAfter = cases.get(cases.size() - 1).getId().toString();

                addCasesToMigrateQueue(cases);

                page++;
            } catch (Exception e) {
                log.error("Could not search for page {}", page, e);
            }
        }


        finishedLoading = true;
        log.info("Number of cases queried: {}", numberOfCasesQueried);

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

        log.info("Search_after for next batch: {}", searchAfter);
        if (numberOfCasesQueried != batchSize) {
            log.info("Number of cases queried is less than the batch size. "
                + "This is probably the last batch!! Good Night!!");
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
        List<Long> targetedCaseIds = caseIds.stream()
            .filter(this::shouldProcessCase)
            .map(Long::parseLong)
            .collect(Collectors.toList());
        if (targetedCaseIds.isEmpty()) {
            log.info("No configured target case references were found in the supplied case list");
            return;
        }
        casesToMigrate.addAll(targetedCaseIds);
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

    private void addCasesToMigrateQueue(List<CaseDetails> cases) {
        final Predicate<CaseDetails> filterFunc = dataMigrationService.accepts(migrationId);

        cases.forEach((caseDetails) -> {
            Long caseId = caseDetails.getId();
            if (!isTargetCase(caseId.toString())) {
                log.info("Skipping case {} as it is not in the target case reference list", caseId);
            } else if (caseIdsToSkip.contains(caseId.toString())) {
                log.info("Skipping case {} as it is in the case reference skip list", caseId);
            } else if (filterFunc.test(caseDetails)) {
                casesToMigrate.add(caseId);
            } else {
                skippedCases.add(caseId);
            }
        });
    }

    private int paginate(int total) {
        return new BigDecimal(total).divide(new BigDecimal(defaultQuerySize), UP).intValue();
    }

    private List<String> parseTargetCaseIds(String configuredTargetCaseIds) {
        return Stream.of(configuredTargetCaseIds.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    }

    private boolean isTargetCase(String caseId) {
        return targetCaseIds.isEmpty() || targetCaseIds.contains(caseId);
    }

    private boolean shouldProcessCase(String caseId) {
        return isTargetCase(caseId) && !caseIdsToSkip.contains(caseId);
    }

    private void publishStats(LocalDateTime startTime) {
        log.info(LOG_STRING);
        log.info(
            "FPLA Data migration completed: Total number of processed cases: {}",
            getMigratedCases().size() + getFailedCases().size()
        );

        String[] task = (caseFlagsV21Migration && migrationRollback) || "DFPL-1124Rollback".equals(migrationId)
            ? new String[]{"Rolled back", "rollbacks"}
            : new String[]{"Migrated", "migrations"};

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

    private String getEventId() {
        if (!caseFlagsV21Migration) {
            return EVENT_ID;
        }
        return migrationRollback ? CASE_FLAGS_ROLLBACK_EVENT_ID : CASE_FLAGS_MIGRATION_EVENT_ID;
    }

    private String getEventSummary() {
        if (!caseFlagsV21Migration) {
            return EVENT_SUMMARY;
        }
        return migrationRollback ? CASE_FLAGS_ROLLBACK_EVENT_SUMMARY : CASE_FLAGS_MIGRATION_EVENT_SUMMARY;
    }

    private String getEventDescription() {
        if (!caseFlagsV21Migration) {
            return EVENT_DESCRIPTION;
        }
        return migrationRollback ? CASE_FLAGS_ROLLBACK_EVENT_DESCRIPTION : CASE_FLAGS_MIGRATION_EVENT_DESCRIPTION;
    }

}
