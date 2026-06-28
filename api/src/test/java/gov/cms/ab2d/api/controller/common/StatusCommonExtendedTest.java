package gov.cms.ab2d.api.controller.common;

import gov.cms.ab2d.api.controller.JobCompletedResponse;
import gov.cms.ab2d.api.controller.JobProcessingException;
import gov.cms.ab2d.api.remote.JobClient;
import gov.cms.ab2d.common.model.PdpClient;
import gov.cms.ab2d.common.service.PdpClientService;
import gov.cms.ab2d.eventclient.clients.SQSEventClient;
import gov.cms.ab2d.job.dto.JobPollResult;
import gov.cms.ab2d.job.model.JobOutput;
import gov.cms.ab2d.job.model.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;

import static gov.cms.ab2d.api.controller.common.ApiText.X_PROG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusCommonExtendedTest {

    @Mock
    private PdpClientService pdpClientService;
    @Mock
    private JobClient jobClient;
    @Mock
    private SQSEventClient eventLogger;

    private StatusCommon statusCommon;
    private PdpClient pdpClient;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        pdpClient = new PdpClient();
        pdpClient.setOrganization("TestOrg");
        pdpClient.setEnabled(true);

        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);

        statusCommon = new StatusCommon(pdpClientService, jobClient, eventLogger, 30);

        request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // --- SUBMITTED/IN_PROGRESS: 202 with X-Progress and Retry-After ---

    @Test
    @DisplayName("doStatus returns 202 with X-Progress header for SUBMITTED job")
    void doStatus_submitted_returns202WithXProgress() {
        JobPollResult pollResult = new JobPollResult(
                "http://localhost/api/v1/fhir/Patient/$export",
                JobStatus.SUBMITTED, 0, null, OffsetDateTime.now().plusHours(24), List.of());
        when(jobClient.poll(anyBoolean(), eq("job-1"), any(), anyInt())).thenReturn(pollResult);

        ResponseEntity response = statusCommon.doStatus("job-1", request, "/api/v1");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst(X_PROG));
        assertTrue(response.getHeaders().getFirst(X_PROG).contains("0% complete"));
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals("30", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("doStatus returns 202 with progress percentage for IN_PROGRESS job")
    void doStatus_inProgress_returns202WithProgress() {
        JobPollResult pollResult = new JobPollResult(
                "http://localhost/api/v1/fhir/Patient/$export",
                JobStatus.IN_PROGRESS, 75, null, OffsetDateTime.now().plusHours(24), List.of());
        when(jobClient.poll(anyBoolean(), eq("job-2"), any(), anyInt())).thenReturn(pollResult);

        ResponseEntity response = statusCommon.doStatus("job-2", request, "/api/v1");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getHeaders().getFirst(X_PROG).contains("75% complete"));
    }

    // --- SUCCESSFUL: 200 with outputs ---

    @Test
    @DisplayName("doStatus returns 200 with Expires header and body for SUCCESSFUL job")
    void doStatus_successful_returns200WithOutputs() {
        JobOutput output = new JobOutput();
        output.setFilePath("Z0001_0001.ndjson");
        output.setFhirResourceType("ExplanationOfBenefit");
        output.setError(false);
        output.setChecksum("abc123");
        output.setFileLength(1024L);

        JobPollResult pollResult = new JobPollResult(
                "http://localhost/api/v1/fhir/Patient/$export",
                JobStatus.SUCCESSFUL, 100, "2023-06-01T00:00:00Z",
                OffsetDateTime.now().plusHours(24), List.of(output));
        when(jobClient.poll(anyBoolean(), eq("job-3"), any(), anyInt())).thenReturn(pollResult);

        ResponseEntity response = statusCommon.doStatus("job-3", request, "/api/v1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.EXPIRES));
        assertNotNull(response.getBody());
    }

    // --- CANCELLED: 404 with OperationOutcome ---

    @Test
    @DisplayName("doStatus returns 404 OperationOutcome for CANCELLED job")
    void doStatus_cancelled_returns404() {
        JobPollResult pollResult = new JobPollResult(
                "http://localhost/api/v1/fhir/Patient/$export",
                JobStatus.CANCELLED, 0, null, OffsetDateTime.now(), List.of());
        when(jobClient.poll(anyBoolean(), eq("job-4"), any(), anyInt())).thenReturn(pollResult);

        ResponseEntity response = statusCommon.doStatus("job-4", request, "/api/v1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // --- FAILED: throws JobProcessingException ---

    @Test
    @DisplayName("doStatus throws JobProcessingException for FAILED job")
    void doStatus_failed_throwsException() {
        JobPollResult pollResult = new JobPollResult(
                "http://localhost/api/v1/fhir/Patient/$export",
                JobStatus.FAILED, 0, null, OffsetDateTime.now(), List.of());
        when(jobClient.poll(anyBoolean(), eq("job-5"), any(), anyInt())).thenReturn(pollResult);

        assertThrows(JobProcessingException.class,
                () -> statusCommon.doStatus("job-5", request, "/api/v1"));
    }

    // --- cancelJob returns 202 ---

    @Test
    @DisplayName("cancelJob returns 202 ACCEPTED")
    void cancelJob_returns202() {
        ResponseEntity response = statusCommon.cancelJob("job-6", request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    }
}
