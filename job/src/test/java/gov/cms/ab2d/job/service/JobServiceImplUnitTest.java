package gov.cms.ab2d.job.service;

import gov.cms.ab2d.eventclient.clients.SQSEventClient;
import gov.cms.ab2d.fhir.FhirVersion;
import gov.cms.ab2d.job.dto.JobPollResult;
import gov.cms.ab2d.job.dto.StartJobDTO;
import gov.cms.ab2d.job.model.Job;
import gov.cms.ab2d.job.model.JobOutput;
import gov.cms.ab2d.job.model.JobStatus;
import gov.cms.ab2d.job.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.net.MalformedURLException;
import java.time.OffsetDateTime;
import java.util.List;

import static gov.cms.ab2d.common.util.Constants.FHIR_NDJSON_CONTENT_TYPE;
import static gov.cms.ab2d.fhir.BundleUtils.EOB;
import static gov.cms.ab2d.job.service.JobServiceImpl.INITIAL_JOB_STATUS_MESSAGE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceImplUnitTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private JobOutputService jobOutputService;
    @Mock
    private SQSEventClient eventLogger;

    private JobServiceImpl jobService;

    private static final String FILE_DOWNLOAD_PATH = "/tmp/efs";

    @BeforeEach
    void setUp() {
        jobService = new JobServiceImpl(jobRepository, jobOutputService, eventLogger, FILE_DOWNLOAD_PATH);
    }

    // --- createJob ---

    @Test
    @DisplayName("createJob persists job with SUBMITTED status, progress 0, and initial message")
    void createJob_setsCorrectFields() {
        StartJobDTO dto = new StartJobDTO("Z0001", "TestOrg", EOB,
                "http://localhost/api/v1/fhir/Patient/$export", FHIR_NDJSON_CONTENT_TYPE,
                null, null, FhirVersion.STU3, null);

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(1L);
            return j;
        });
        when(jobRepository.countJobByContractNumberAndStatus(anyString(), anyList())).thenReturn(1);

        Job created = jobService.createJob(dto);

        assertNotNull(created);
        assertEquals(JobStatus.SUBMITTED, created.getStatus());
        assertEquals(0, created.getProgress());
        assertEquals(INITIAL_JOB_STATUS_MESSAGE, created.getStatusMessage());
        assertEquals("Z0001", created.getContractNumber());
        assertEquals("TestOrg", created.getOrganization());
        assertEquals(EOB, created.getResourceTypes());
        assertEquals(FHIR_NDJSON_CONTENT_TYPE, created.getOutputFormat());
        assertEquals(FhirVersion.STU3, created.getFhirVersion());
        assertNotNull(created.getJobUuid());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    @DisplayName("createJob copies since/until/serviceDates from StartJobDTO")
    void createJob_copiesSinceUntilServiceDates() {
        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        OffsetDateTime until = OffsetDateTime.now().minusDays(1);
        List<String> serviceDates = List.of("ge2023-01-01", "le2023-12-31");

        StartJobDTO dto = new StartJobDTO("Z0002", "Org2", EOB,
                "http://localhost/api/v2/fhir/Patient/$export", FHIR_NDJSON_CONTENT_TYPE,
                since, until, FhirVersion.R4, serviceDates);

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.countJobByContractNumberAndStatus(anyString(), anyList())).thenReturn(1);

        Job created = jobService.createJob(dto);

        assertEquals(since, created.getSince());
        assertEquals(until, created.getUntil());
        assertEquals(serviceDates, created.getServiceDates());
    }

    // --- generateJobUuid ---

    @Test
    @DisplayName("V3 URL produces UUID prefixed with 33333333")
    void generateJobUuid_v3Url_prefixedWith33333333() {
        StartJobDTO dto = new StartJobDTO("Z0003", "OrgV3", EOB,
                "http://localhost/api/v3/fhir/Patient/$export", FHIR_NDJSON_CONTENT_TYPE,
                null, null, FhirVersion.R4V3, null);

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.countJobByContractNumberAndStatus(anyString(), anyList())).thenReturn(1);

        Job created = jobService.createJob(dto);

        assertTrue(created.getJobUuid().startsWith("33333333"),
                "V3 job UUID should start with 33333333, got: " + created.getJobUuid());
        assertEquals(36, created.getJobUuid().length());
    }

    @Test
    @DisplayName("Non-V3 URL produces standard UUID without 33333333 prefix")
    void generateJobUuid_nonV3Url_standardUuid() {
        StartJobDTO dto = new StartJobDTO("Z0004", "OrgV1", EOB,
                "http://localhost/api/v1/fhir/Patient/$export", FHIR_NDJSON_CONTENT_TYPE,
                null, null, FhirVersion.STU3, null);

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.countJobByContractNumberAndStatus(anyString(), anyList())).thenReturn(1);

        Job created = jobService.createJob(dto);

        assertFalse(created.getJobUuid().startsWith("33333333"),
                "Non-V3 job UUID should not start with 33333333");
        assertEquals(36, created.getJobUuid().length());
    }

    @Test
    @DisplayName("V2 URL produces standard UUID without 33333333 prefix")
    void generateJobUuid_v2Url_standardUuid() {
        StartJobDTO dto = new StartJobDTO("Z0005", "OrgV2", EOB,
                "http://localhost/api/v2/fhir/Patient/$export", FHIR_NDJSON_CONTENT_TYPE,
                null, null, FhirVersion.R4, null);

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.countJobByContractNumberAndStatus(anyString(), anyList())).thenReturn(1);

        Job created = jobService.createJob(dto);

        assertFalse(created.getJobUuid().startsWith("33333333"));
    }

    // --- poll ---

    @Test
    @DisplayName("poll returns JobPollResult with expected fields for non-admin user")
    void poll_nonAdmin_returnsResult() {
        Job job = buildJob("poll-uuid", "TestOrg", JobStatus.IN_PROGRESS);
        job.setProgress(50);
        job.setRequestUrl("http://localhost/api/v1/fhir/Patient/$export");
        job.setFhirVersion(FhirVersion.STU3);
        job.setCreatedAt(OffsetDateTime.now().minusHours(1));
        job.setExpiresAt(OffsetDateTime.now().plusHours(24));

        when(jobRepository.findByJobUuid("poll-uuid")).thenReturn(job);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        JobPollResult result = jobService.poll(false, "poll-uuid", "TestOrg", 0);

        assertNotNull(result);
        assertEquals(JobStatus.IN_PROGRESS, result.getStatus());
        assertEquals(50, result.getProgress());
        assertNotNull(result.getTransactionTime());
        assertEquals("http://localhost/api/v1/fhir/Patient/$export", result.getRequestUrl());
    }

    @Test
    @DisplayName("poll for admin user does not check organization")
    void poll_admin_bypasses_orgCheck() {
        Job job = buildJob("admin-poll-uuid", "OtherOrg", JobStatus.SUBMITTED);
        job.setRequestUrl("http://localhost/api/v1/fhir/Patient/$export");
        job.setFhirVersion(FhirVersion.STU3);
        job.setCreatedAt(OffsetDateTime.now());

        when(jobRepository.findByJobUuid("admin-poll-uuid")).thenReturn(job);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        JobPollResult result = jobService.poll(true, "admin-poll-uuid", "DifferentOrg", 0);

        assertNotNull(result);
        assertEquals(JobStatus.SUBMITTED, result.getStatus());
    }

    @Test
    @DisplayName("poll for non-admin user throws when organization doesn't match")
    void poll_nonAdmin_wrongOrg_throws() {
        Job job = buildJob("secured-uuid", "OrgA", JobStatus.SUBMITTED);

        when(jobRepository.findByJobUuid("secured-uuid")).thenReturn(job);

        assertThrows(InvalidJobAccessException.class,
                () -> jobService.poll(false, "secured-uuid", "OrgB", 0));
    }

    // --- cancelJob ---

    @Test
    @DisplayName("cancelJob transitions SUBMITTED job to CANCELLED")
    void cancelJob_submitted_transitionsToCancelled() {
        Job job = buildJob("cancel-uuid", "TestOrg", JobStatus.SUBMITTED);
        when(jobRepository.findByJobUuid("cancel-uuid")).thenReturn(job);

        jobService.cancelJob("cancel-uuid", "TestOrg");

        verify(jobRepository).cancelJobByJobUuid("cancel-uuid");
    }

    @Test
    @DisplayName("cancelJob transitions IN_PROGRESS job to CANCELLED")
    void cancelJob_inProgress_transitionsToCancelled() {
        Job job = buildJob("cancel-ip-uuid", "TestOrg", JobStatus.IN_PROGRESS);
        when(jobRepository.findByJobUuid("cancel-ip-uuid")).thenReturn(job);

        jobService.cancelJob("cancel-ip-uuid", "TestOrg");

        verify(jobRepository).cancelJobByJobUuid("cancel-ip-uuid");
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"SUCCESSFUL", "CANCELLED", "FAILED"})
    @DisplayName("cancelJob throws InvalidJobStateTransition for non-cancellable statuses")
    void cancelJob_nonCancellable_throws(JobStatus status) {
        Job job = buildJob("terminal-uuid", "TestOrg", status);
        when(jobRepository.findByJobUuid("terminal-uuid")).thenReturn(job);

        assertThrows(InvalidJobStateTransition.class,
                () -> jobService.cancelJob("terminal-uuid", "TestOrg"));
    }

    // --- getResourceForJob ---

    @Test
    @DisplayName("getResourceForJob throws ResourceNotFoundException when file path doesn't match")
    void getResourceForJob_noMatchingPath_throws() {
        Job job = buildJob("resource-uuid", "TestOrg", JobStatus.SUCCESSFUL);
        JobOutput output = new JobOutput();
        output.setFilePath("data.ndjson");
        output.setError(false);
        job.setJobOutputs(List.of(output));

        when(jobRepository.findByJobUuid("resource-uuid")).thenReturn(job);

        assertThrows(gov.cms.ab2d.common.service.ResourceNotFoundException.class,
                () -> jobService.getResourceForJob("resource-uuid", "wrong.ndjson", "TestOrg"));
    }

    @Test
    @DisplayName("getResourceForJob throws JobOutputMissingException when downloads exceed max")
    void getResourceForJob_maxDownloadsExceeded_throws() {
        Job job = buildJob("maxdl-uuid", "TestOrg", JobStatus.SUCCESSFUL);
        JobOutput output = new JobOutput();
        output.setFilePath("data.ndjson");
        output.setError(false);
        output.setDownloaded(100);
        job.setJobOutputs(List.of(output));

        when(jobRepository.findByJobUuid("maxdl-uuid")).thenReturn(job);

        assertThrows(JobOutputMissingException.class,
                () -> jobService.getResourceForJob("maxdl-uuid", "data.ndjson", "TestOrg"));
    }

    @Test
    @DisplayName("getResourceForJob throws JobOutputMissingException with expired message when job expired")
    void getResourceForJob_expired_throwsWithExpiredMessage() {
        Job job = buildJob("expired-uuid", "TestOrg", JobStatus.SUCCESSFUL);
        job.setExpiresAt(OffsetDateTime.now().minusDays(1));
        JobOutput output = new JobOutput();
        output.setFilePath("data.ndjson");
        output.setError(false);
        output.setDownloaded(0);
        job.setJobOutputs(List.of(output));

        when(jobRepository.findByJobUuid("expired-uuid")).thenReturn(job);

        JobOutputMissingException ex = assertThrows(JobOutputMissingException.class,
                () -> jobService.getResourceForJob("expired-uuid", "data.ndjson", "TestOrg"));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("getResourceForJob throws JobOutputMissingException with error message when file missing but not expired")
    void getResourceForJob_fileMissing_throwsWithErrorMessage() {
        Job job = buildJob("missing-uuid", "TestOrg", JobStatus.SUCCESSFUL);
        job.setExpiresAt(OffsetDateTime.now().plusDays(1));
        JobOutput output = new JobOutput();
        output.setFilePath("data.ndjson");
        output.setError(false);
        output.setDownloaded(0);
        job.setJobOutputs(List.of(output));

        when(jobRepository.findByJobUuid("missing-uuid")).thenReturn(job);

        JobOutputMissingException ex = assertThrows(JobOutputMissingException.class,
                () -> jobService.getResourceForJob("missing-uuid", "data.ndjson", "TestOrg"));
        assertTrue(ex.getMessage().contains("error"));
    }

    private Job buildJob(String uuid, String organization, JobStatus status) {
        Job job = new Job();
        job.setJobUuid(uuid);
        job.setOrganization(organization);
        job.setStatus(status);
        job.setFhirVersion(FhirVersion.STU3);
        job.setCreatedAt(OffsetDateTime.now());
        return job;
    }
}
