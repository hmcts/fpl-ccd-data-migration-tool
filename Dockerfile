ARG APP_INSIGHTS_AGENT_VERSION=3.4.12
FROM hmctsprod.azurecr.io/base/java:21-distroless

USER hmcts
COPY lib/applicationinsights.json /opt/app/
COPY build/libs/ccd-case-migration.jar /opt/app/

EXPOSE 4999
CMD [ "ccd-case-migration.jar" ]
