package gov.cms.ab2d.api.controller.common;

import gov.cms.ab2d.api.controller.InMaintenanceModeException;
import gov.cms.ab2d.api.controller.TooManyRequestsException;
import gov.cms.ab2d.api.remote.JobClient;
import gov.cms.ab2d.common.model.PdpClient;
import gov.cms.ab2d.common.properties.PropertiesService;
import gov.cms.ab2d.common.service.ContractService;
import gov.cms.ab2d.common.service.InvalidClientInputException;
import gov.cms.ab2d.common.service.PdpClientService;
import gov.cms.ab2d.contracts.model.Contract;
import gov.cms.ab2d.eventclient.clients.SQSEventClient;
import gov.cms.ab2d.fhir.FhirVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;

import static gov.cms.ab2d.common.util.Constants.ZIPFORMAT;
import static gov.cms.ab2d.common.util.Constants.ZIP_SUPPORT_ON;
import static gov.cms.ab2d.common.util.PropertyConstants.MAINTENANCE_MODE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiCommonExtendedTest {

    private static final String CONTRACT_NUMBER = "Z1234";
    private static final Long CONTRACT_ID = 200L;

    @Mock
    private SQSEventClient eventLogger;
    @Mock
    private JobClient jobClient;
    @Mock
    private PropertiesService propertiesService;
    @Mock
    private PdpClientService pdpClientService;
    @Mock
    private ContractService contractService;

    private ApiCommon apiCommon;
    private PdpClient pdpClient;
    private Contract contract;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        contract = new Contract();
        contract.setContractNumber(CONTRACT_NUMBER);
        contract.setId(CONTRACT_ID);
        contract.setAttestedOn(OffsetDateTime.now().minusDays(30));

        pdpClient = new PdpClient();
        pdpClient.setContractId(CONTRACT_ID);
        pdpClient.setOrganization("TestOrg");
        pdpClient.setMaxParallelJobs(3);

        apiCommon = new ApiCommon(eventLogger, jobClient, propertiesService, pdpClientService, contractService);

        request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("api.example.com");
        request.setServerPort(443);
        request.setRequestURI("/api/v1/fhir/Patient/$export");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // --- checkIfInMaintenanceMode ---

    @Test
    @DisplayName("checkValidCreateJob throws InMaintenanceModeException when maintenance mode is on")
    void checkValidCreateJob_maintenanceModeOn_throwsException() {
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);
        when(contractService.getContractByContractId(CONTRACT_ID)).thenReturn(contract);
        when(propertiesService.isToggleOn(MAINTENANCE_MODE, false)).thenReturn(true);

        assertThrows(InMaintenanceModeException.class,
                () -> apiCommon.checkValidCreateJob(request, null, null, null,
                        null, null, FhirVersion.STU3));
    }

    @Test
    @DisplayName("checkIfInMaintenanceMode does not throw when mode is off")
    void checkIfInMaintenanceMode_modeOff_noException() {
        when(propertiesService.isToggleOn(MAINTENANCE_MODE, false)).thenReturn(false);
        assertDoesNotThrow(() -> apiCommon.checkIfInMaintenanceMode());
    }

    // --- checkResourceTypesAndOutputFormat ---

    @Test
    @DisplayName("Invalid _type parameter throws InvalidClientInputException")
    void checkResourceTypes_invalidType_throws() {
        assertThrows(InvalidClientInputException.class,
                () -> apiCommon.checkResourceTypesAndOutputFormat("Patient", null));
    }

    @Test
    @DisplayName("Valid EOB _type parameter does not throw")
    void checkResourceTypes_validEOB_noThrow() {
        assertDoesNotThrow(
                () -> apiCommon.checkResourceTypesAndOutputFormat("ExplanationOfBenefit", null));
    }

    @Test
    @DisplayName("Null _type parameter is acceptable")
    void checkResourceTypes_null_noThrow() {
        assertDoesNotThrow(
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, null));
    }

    @Test
    @DisplayName("Invalid _outputFormat throws InvalidClientInputException")
    void checkOutputFormat_invalid_throws() {
        assertThrows(InvalidClientInputException.class,
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, "text/csv"));
    }

    @Test
    @DisplayName("Valid _outputFormat values do not throw")
    void checkOutputFormat_validFormats_noThrow() {
        assertDoesNotThrow(
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, "application/fhir+ndjson"));
        assertDoesNotThrow(
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, "application/ndjson"));
        assertDoesNotThrow(
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, "ndjson"));
    }

    @Test
    @DisplayName("Zip format rejected when zip support is off")
    void checkOutputFormat_zipNotSupported_throws() {
        when(propertiesService.isToggleOn(ZIP_SUPPORT_ON, false)).thenReturn(false);
        assertThrows(InvalidClientInputException.class,
                () -> apiCommon.checkResourceTypesAndOutputFormat(null, ZIPFORMAT));
    }

    // --- checkIfCurrentClientCanAddJob ---

    @Test
    @DisplayName("TooManyRequestsException thrown when active jobs reach maxParallelJobs")
    void checkIfCurrentClientCanAddJob_atLimit_throws() {
        pdpClient.setMaxParallelJobs(2);
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);
        when(jobClient.activeJobs("TestOrg")).thenReturn(2);
        when(jobClient.getActiveJobIds("TestOrg")).thenReturn(List.of("job-1", "job-2"));

        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class,
                () -> apiCommon.checkIfCurrentClientCanAddJob());
        assertNotNull(ex.getJobIds());
        assertEquals(2, ex.getJobIds().size());
    }

    @Test
    @DisplayName("No exception when active jobs under maxParallelJobs")
    void checkIfCurrentClientCanAddJob_underLimit_noThrow() {
        pdpClient.setMaxParallelJobs(3);
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);
        when(jobClient.activeJobs("TestOrg")).thenReturn(1);

        assertDoesNotThrow(() -> apiCommon.checkIfCurrentClientCanAddJob());
    }

    @Test
    @DisplayName("No exception when no active jobs exist")
    void checkIfCurrentClientCanAddJob_zeroActive_noThrow() {
        pdpClient.setMaxParallelJobs(3);
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);
        when(jobClient.activeJobs("TestOrg")).thenReturn(0);

        assertDoesNotThrow(() -> apiCommon.checkIfCurrentClientCanAddJob());
    }

    // --- returnStatusForJobCreation ---

    @Test
    @DisplayName("returnStatusForJobCreation returns 202 with Content-Location header")
    void returnStatusForJobCreation_returns202WithContentLocation() {
        request.addHeader("X-Forwarded-Proto", "https");

        ResponseEntity<Void> response = apiCommon.returnStatusForJobCreation(
                "test-job-uuid", "/api/v1", "req-123", request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        String contentLocation = response.getHeaders().getFirst("Content-Location");
        assertNotNull(contentLocation);
        assertTrue(contentLocation.contains("/api/v1/fhir/Job/test-job-uuid/$status"));
    }

    // --- checkValidCreateJob full flow ---

    @Test
    @DisplayName("checkValidCreateJob succeeds with valid parameters and returns StartJobDTO")
    void checkValidCreateJob_validParams_returnsDto() {
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);
        when(contractService.getContractByContractId(CONTRACT_ID)).thenReturn(contract);
        when(propertiesService.isToggleOn(MAINTENANCE_MODE, false)).thenReturn(false);
        when(jobClient.activeJobs("TestOrg")).thenReturn(0);

        var dto = apiCommon.checkValidCreateJob(request, null,
                OffsetDateTime.now().minusDays(1), null,
                "ExplanationOfBenefit", "application/fhir+ndjson", FhirVersion.R4);

        assertNotNull(dto);
        assertEquals(CONTRACT_NUMBER, dto.getContractNumber());
        assertEquals("TestOrg", dto.getOrganization());
        assertEquals(FhirVersion.R4, dto.getVersion());
    }
}
