package uk.gov.hmcts.reform.fpl.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.domain.common.CollectionEntry;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@JsonInclude
public class CaseData {
    private final OldChildren children;
    private final List<CollectionEntry<Child>> children1;
}