package uk.gov.hmcts.reform.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class Hearing {

    private final String hearingDescription;
    private final String reason;
    private final String timeFrame;
    private final String sameDayHearingReason;
    private final String withoutNotice;
    private final String reasonForNoNotice;
    private final String reducedNotice;
    private final String reasonForReducedNotice;
    private final String respondentsAware;
    private final String reasonsForRespondentsNotBeingAware;
    private final String createdDate;
    private final String createdBy;
    private final String updatedDate;
    private final String updatedBy;

}
