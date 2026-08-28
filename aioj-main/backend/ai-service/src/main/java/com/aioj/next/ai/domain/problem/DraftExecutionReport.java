package com.aioj.next.ai.domain.problem;

public record DraftExecutionReport(
        VerificationReport sandboxReport,
        CrossCheckReport crossCheckReport,
        ComplexityReport complexityReport,
        OfficialTestcasePackageReport officialPackageReport
) {
    public DraftExecutionReport(VerificationReport sandboxReport, CrossCheckReport crossCheckReport) {
        this(sandboxReport, crossCheckReport, null, null);
    }

    public DraftExecutionReport(VerificationReport sandboxReport, CrossCheckReport crossCheckReport,
                                ComplexityReport complexityReport) {
        this(sandboxReport, crossCheckReport, complexityReport, null);
    }

    public boolean passed() {
        return sandboxReport != null
                && sandboxReport.passed()
                && (crossCheckReport == null || crossCheckReport.passed())
                && (complexityReport == null || complexityReport.passed());
    }
}
