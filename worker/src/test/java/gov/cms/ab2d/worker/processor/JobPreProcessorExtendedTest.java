package gov.cms.ab2d.worker.processor;

import gov.cms.ab2d.contracts.model.Contract;
import gov.cms.ab2d.contracts.model.ContractDTO;
import gov.cms.ab2d.eventclient.clients.SQSEventClient;
import gov.cms.ab2d.fhir.FhirVersion;
import gov.cms.ab2d.job.model.Job;
import gov.cms.ab2d.job.model.JobStatus;
import gov.cms.ab2d.job.repository.JobRepository;
import gov.cms.ab2d.worker.processor.coverage.CoverageDriver;
import gov.cms.ab2d.worker.service.ContractWorkerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static gov.cms.ab2d.fhir.FhirVersion.R4;
import static gov.cms.ab2d.fhir.FhirVersion.STU3;
import static gov.cms.ab2d.job.model.JobStatus.FAILED;
import static gov.cms.ab2d.job.model.JobStatus.SUBMITTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobPreProcessorExtendedTest {

    private static final String JOB_UUID = "test-preprocess-uuid";

    @Mock
    private ContractWorkerClient contractWorkerClient;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private SQSEventClient eventLogger;
    @Mock
    private CoverageDriver coverageDriver;

    private JobPreProcessorImpl preprocessor;
    private ContractDTO contract;

    @BeforeEach
    void setUp() {
        preprocessor = new JobPreProcessorImpl(contractWorkerClient, jobRepository, eventLogger, coverageDriver);
        contract = new ContractDTO(null, "Z9876", "Z9876", OffsetDateTime.now(), Contract.ContractType.NORMAL, 0, 0);
    }

    // --- Job not found ---

    @Test
    @DisplayName("preprocess throws IllegalArgumentException when job UUID not found in database")
    void preprocess_jobNotFound_throwsIllegalArgument() {
        when(jobRepository.findByJobUuid("nonexistent-uuid")).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preprocessor.preprocess("nonexistent-uuid"));

        assertEquals("Job nonexistent-uuid was not found", ex.getMessage());
    }

    // --- Non-SUBMITTED status rejection ---

    @Test
    @DisplayName("preprocess throws IllegalArgumentException when job is IN_PROGRESS")
    void preprocess_inProgressStatus_throwsIllegalArgument() {
        Job job = createJob(JOB_UUID, R4, JobStatus.IN_PROGRESS);
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preprocessor.preprocess(JOB_UUID));

        assertTrue(ex.getMessage().contains("is not in SUBMITTED status"));
    }

    @Test
    @DisplayName("preprocess throws IllegalArgumentException when job is CANCELLED")
    void preprocess_cancelledStatus_throwsIllegalArgument() {
        Job job = createJob(JOB_UUID, R4, JobStatus.CANCELLED);
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preprocessor.preprocess(JOB_UUID));

        assertTrue(ex.getMessage().contains("is not in SUBMITTED status"));
    }

    // --- STU3 with _until failure ---

    @Test
    @DisplayName("preprocess sets job to FAILED with correct message when STU3 has _until parameter")
    void preprocess_stu3WithUntil_failsWithMessage() {
        Job job = createJob(JOB_UUID, STU3, SUBMITTED);
        job.setUntil(OffsetDateTime.of(2023, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);

        Job result = preprocessor.preprocess(JOB_UUID);

        assertEquals(FAILED, result.getStatus());
        assertEquals("failed because the _until parameter is only available with version 2 and version 3 of the API.",
                result.getStatusMessage());
    }

    @Test
    @DisplayName("preprocess does not fail R4 job with _until parameter")
    void preprocess_r4WithUntil_doesNotFail() throws InterruptedException {
        Job job = createJob(JOB_UUID, R4, SUBMITTED);
        job.setUntil(OffsetDateTime.of(2023, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);
        when(contractWorkerClient.getContractByContractNumber(job.getContractNumber())).thenReturn(contract);
        when(coverageDriver.isCoverageAvailable(job, contract)).thenReturn(true);

        Job result = preprocessor.preprocess(JOB_UUID);

        assertNotEquals(FAILED, result.getStatus());
    }

    // --- _until before _since failure ---

    @Test
    @DisplayName("preprocess sets job to FAILED when _until is before _since")
    void preprocess_untilBeforeSince_failsWithMessage() {
        Job job = createJob(JOB_UUID, R4, SUBMITTED);
        job.setSince(OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        job.setUntil(OffsetDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);

        Job result = preprocessor.preprocess(JOB_UUID);

        assertEquals(FAILED, result.getStatus());
        assertEquals("failed because the _until is before _since.", result.getStatusMessage());
    }

    @Test
    @DisplayName("preprocess does not fail when _until equals _since")
    void preprocess_untilEqualsSince_doesNotFail() throws InterruptedException {
        OffsetDateTime sameTime = OffsetDateTime.of(2023, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        Job job = createJob(JOB_UUID, R4, SUBMITTED);
        job.setSince(sameTime);
        job.setUntil(sameTime);
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);
        when(contractWorkerClient.getContractByContractNumber(job.getContractNumber())).thenReturn(contract);
        when(coverageDriver.isCoverageAvailable(job, contract)).thenReturn(true);

        Job result = preprocessor.preprocess(JOB_UUID);

        assertNotEquals(FAILED, result.getStatus());
    }

    @Test
    @DisplayName("preprocess does not fail when _until is after _since")
    void preprocess_untilAfterSince_doesNotFail() throws InterruptedException {
        Job job = createJob(JOB_UUID, R4, SUBMITTED);
        job.setSince(OffsetDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        job.setUntil(OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);
        when(contractWorkerClient.getContractByContractNumber(job.getContractNumber())).thenReturn(contract);
        when(coverageDriver.isCoverageAvailable(job, contract)).thenReturn(true);

        Job result = preprocessor.preprocess(JOB_UUID);

        assertNotEquals(FAILED, result.getStatus());
    }

    // --- Contract not found ---

    @Test
    @DisplayName("preprocess throws IllegalArgumentException when contract is null")
    void preprocess_contractNull_throwsIllegalArgument() {
        Job job = createJob(JOB_UUID, R4, SUBMITTED);
        when(jobRepository.findByJobUuid(JOB_UUID)).thenReturn(job);
        when(contractWorkerClient.getContractByContractNumber(job.getContractNumber())).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preprocessor.preprocess(JOB_UUID));

        assertEquals("A job must always have a contract.", ex.getMessage());
    }

    private Job createJob(String uuid, FhirVersion version, JobStatus status) {
        Job job = new Job();
        job.setJobUuid(uuid);
        job.setFhirVersion(version);
        job.setStatus(status);
        job.setStatusMessage("0%");
        job.setContractNumber(contract.getContractNumber());
        return job;
    }
}
