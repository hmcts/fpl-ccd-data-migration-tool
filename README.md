# Adoption CCD Data Migration Tool

## Basic overview

The basic premise of this tool is to be an implementaion of [hmcts/ccd-case-migration-starter](https://github.com/hmcts/ccd-case-migration-starter).

It works by accessing the ccd-data-store-api as the system user, grabbing and filtering all cases, and then migrating the filtered cases.
To perform the migration there needs to be an event defined in the consuming case type that is defined with the ID `migrateCase`, this is defined
[here](https://github.com/hmcts/fpl-ccd-configuration/blob/bc67b4f1590e0d5999abad30819c8f5a7fc0e391/ccd-definition/CaseEvent/CareSupervision/MultiState.json#L5)
in the FPL repo.
This event is then triggered by the `CaseMigrationProcessor` defined in the [hmcts/ccd-case-migration-starter](https://github.com/hmcts/ccd-case-migration-starter),
and as it is a CCD event it can have the standard CCD hooks, i.e. `about-to-start`, `about-to-submit`, `submitted`. FPL makes use of the `about-to-submit` hook to then perform the [main part of the migration](https://github.com/hmcts/fpl-ccd-configuration/blob/master/service/src/main/java/uk/gov/hmcts/reform/fpl/controllers/support/MigrateCaseController.java).

### More info

For more info on the tool and how to use it check out [hmcts/ccd-case-migration-starter](https://github.com/hmcts/ccd-case-migration-starter)

## Build

To build the project run

```shell
./gradlew clean build
```

this will generate a jar in the `build/libs` directory which can then be used when running the migration.

## Running

To run the jar you will need to do the following

```shell
java -jar \
-Dspring.application.name="fpl-ccd-case-migration-tool" \
-Didam.api.url="https://idam-api.aat.platform.hmcts.net" \
-Didam.client.id="fpl_case_service" \
-Didam.client.secret="[VALUE IN VAULT]" \
-Didam.client.redirect_uri="https://fpl-case-service-aat.service.core-compute-aat.internal/oauth2/callback" \
-Dcore_case_data.api.url="http://ccd-data-store-api-aat.service.core-compute-aat.internal" \
-Didam.s2s-auth.url="http://rpe-service-auth-provider-aat.service.core-compute-aat.internal" \
-Didam.s2s-auth.microservice="fpl_case_service" \
-Didam.s2s-auth.totp_secret="[VALUE IN VAULT]" \
-Dmigration.idam.username="fpl-system-update@mailnesia.com" \
-Dmigration.idam.password="[VALUE IN VAULT]" \
-Dmigration.jurisdiction="PUBLICLAW" \
-Dmigration.caseType="CARE_SUPERVISION_EPO" \
-Dlogging.level.root="ERROR" \
-Dlogging.level.uk.gov.hmcts.reform="INFO" \
-Dfeign.client.config.default.connectTimeout="60000" \
-Dfeign.client.config.default.readTimeout="60000" \
PATH/TO/MIGRATION.jar
```

where

- `idam.client.secret`
- `idam.s2s-auth.totp_secret`
- `migration.idam.password`

can all be found in the fpl-case-service vault.

Note that the parameters given are using AAT environment as an example.

### Extra env vars
```shell
case-migration.timeout=${CASE_MIGRATION_TIMEOUT:7200} # global timeout for the migration tool (seconds) default = 2 hours

case-migration.case_id_list.mapping=${CASE_ID_LIST_MAPPING:} # format DFPL-ID=>CASEID1|CASEID2|CASEID3;DFPL-ID2=>CASEID4
case-migration.use_case_id_mapping=${USE_CASE_ID_MAPPING:true} # whether to use the mapping or the ES query - if false make sure to have an ES query in DataMigrationServiceImpl
case-migration.retry_failures=${RETRY_FAILURES:false} # whether to retry failed cases

default.thread.delay=${DEFAULT_THREAD_DELAY:0} # whether to artificially slow down the tool by sleeping a thread after a successful migration (seconds) default = no delay
```

## Common issues

### Running

When running the migration we are making the requests as the system user, as such if the user does not have
[permission](https://github.com/hmcts/fpl-ccd-configuration/blob/master/ccd-definition/AuthorisationCaseField/CareSupervision/system-update.json)
to the fields that it needs to update then the ccd-data-store-api could return errors when validating the case data at
the end of the migration. To ensure that this doesn't occur make sure that the system user has access to all fields
being operated on in both the
[DataMigrationServiceImpl](src/main/java/uk/gov/hmcts/reform/migration/service/DataMigrationServiceImpl.java)
and the
[controller](https://github.com/hmcts/fpl-ccd-configuration/blob/master/service/src/main/java/uk/gov/hmcts/reform/fpl/controllers/support/MigrateCaseController.java)
handling the callback.

### Dependencies

With the deprecation of JCenter the dependencies

```groovy
compile group: 'uk.gov.hmcts.reform.ccd-case-migration', name: 'processor', version: '3.0.0'
compile group: 'uk.gov.hmcts.reform.ccd-case-migration', name: 'domain', version: '3.0.0'
```

are not available to be downloaded, to ensure that you can build and run migrations you can clone
the [stater repo](https://github.com/hmcts/ccd-case-migration-starter) and compile the dependencies locally using

```shell
./gradlew clean build publishToMavenLocal
```
