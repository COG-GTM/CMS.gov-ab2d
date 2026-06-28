package gov.cms.ab2d.api.controller;

import gov.cms.ab2d.api.controller.common.ApiCommon;
import gov.cms.ab2d.api.controller.common.CheckValidParametersDTO;
import gov.cms.ab2d.api.controller.v1.BulkDataAccessAPIV1;
import gov.cms.ab2d.api.controller.v2.BulkDataAccessAPIV2;
import gov.cms.ab2d.api.controller.v3.BulkDataAccessAPIV3;
import gov.cms.ab2d.api.remote.JobClient;
import gov.cms.ab2d.fhir.FhirVersion;
import gov.cms.ab2d.job.dto.StartJobDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;

import static gov.cms.ab2d.common.util.Constants.*;
import static gov.cms.ab2d.fhir.BundleUtils.EOB;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VersionedExportControllerTest {

    @Mock
    private JobClient jobClient;
    @Mock
    private ApiCommon apiCommon;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("api.example.com");
        request.setServerPort(443);
        request.setRequestURI("/api/v1/fhir/Patient/$export");
        request.setAttribute("requestId", "req-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // --- V1 pins STU3 ---

    @Test
    @DisplayName("V1 exportAllPatients passes STU3 version to checkValidCreateJob")
    void v1_exportAllPatients_usesSTU3() {
        StartJobDTO dto = new StartJobDTO("Z0000", "org", EOB, "http://localhost", FHIR_NDJSON_CONTENT_TYPE, null, null, FhirVersion.STU3, null);
        when(apiCommon.checkValidCreateJob(any(), isNull(), isNull(), isNull(), eq(EOB), eq(FHIR_NDJSON_CONTENT_TYPE), eq(FhirVersion.STU3)))
                .thenReturn(dto);
        when(jobClient.createJob(dto)).thenReturn("job-v1-uuid");
        when(apiCommon.returnStatusForJobCreation(eq("job-v1-uuid"), eq(API_PREFIX_V1), any(), any()))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.ACCEPTED));

        BulkDataAccessAPIV1 v1 = new BulkDataAccessAPIV1(jobClient, apiCommon);
        ResponseEntity<Void> response = v1.exportAllPatients(request, EOB, FHIR_NDJSON_CONTENT_TYPE, null, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(apiCommon).checkValidCreateJob(any(), isNull(), isNull(), isNull(), eq(EOB), eq(FHIR_NDJSON_CONTENT_TYPE), eq(FhirVersion.STU3));
    }

    // --- V2 pins R4 ---

    @Test
    @DisplayName("V2 exportAllPatients passes R4 version to checkValidCreateJob")
    void v2_exportAllPatients_usesR4() {
        StartJobDTO dto = new StartJobDTO("Z0000", "org", EOB, "http://localhost", FHIR_NDJSON_CONTENT_TYPE, null, null, FhirVersion.R4, null);
        when(apiCommon.checkValidCreateJob(any(), isNull(), isNull(), isNull(), eq(EOB), eq(FHIR_NDJSON_CONTENT_TYPE), eq(FhirVersion.R4)))
                .thenReturn(dto);
        when(jobClient.createJob(dto)).thenReturn("job-v2-uuid");
        when(apiCommon.returnStatusForJobCreation(eq("job-v2-uuid"), eq(API_PREFIX_V2), any(), any()))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.ACCEPTED));

        BulkDataAccessAPIV2 v2 = new BulkDataAccessAPIV2(jobClient, apiCommon);
        ResponseEntity<Void> response = v2.exportAllPatients(request, EOB, FHIR_NDJSON_CONTENT_TYPE, null, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(apiCommon).checkValidCreateJob(any(), isNull(), isNull(), isNull(), eq(EOB), eq(FHIR_NDJSON_CONTENT_TYPE), eq(FhirVersion.R4));
    }

    // --- V3 pins R4V3 and handles _typeFilter ---

    @Test
    @DisplayName("V3 exportAllPatients passes R4V3 version and handles typeFilter")
    void v3_exportAllPatients_usesR4V3WithTypeFilter() {
        String typeFilter = "ExplanationOfBenefit%3Fservice-date%3Dge2023-01-01";
        List<String> serviceDates = List.of("ge2023-01-01");

        when(apiCommon.getServiceDates(typeFilter)).thenReturn(serviceDates);

        StartJobDTO dto = new StartJobDTO("Z0000", "org", EOB, "http://localhost", FHIR_NDJSON_CONTENT_TYPE, null, null, FhirVersion.R4V3, serviceDates);
        when(apiCommon.checkValidCreateJob(any(), isNull(), eq(FhirVersion.R4V3), any(CheckValidParametersDTO.class)))
                .thenReturn(dto);
        when(jobClient.createJob(dto)).thenReturn("job-v3-uuid");
        when(apiCommon.returnStatusForJobCreation(eq("job-v3-uuid"), eq(API_PREFIX_V3), any(), any()))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.ACCEPTED));

        BulkDataAccessAPIV3 v3 = new BulkDataAccessAPIV3(jobClient, apiCommon);
        ResponseEntity<Void> response = v3.exportAllPatients(request, EOB, FHIR_NDJSON_CONTENT_TYPE, null, null, typeFilter);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(apiCommon).checkContractIsAllowListedForV3();
        verify(apiCommon).getServiceDates(typeFilter);

        ArgumentCaptor<CheckValidParametersDTO> paramsCaptor = ArgumentCaptor.forClass(CheckValidParametersDTO.class);
        verify(apiCommon).checkValidCreateJob(any(), isNull(), eq(FhirVersion.R4V3), paramsCaptor.capture());
        CheckValidParametersDTO captured = paramsCaptor.getValue();
        assertEquals(serviceDates, captured.getServiceDates());
        assertEquals(EOB, captured.getResourceTypes());
    }

    @Test
    @DisplayName("V3 exportAllPatients with null typeFilter passes empty service dates")
    void v3_exportAllPatients_nullTypeFilter() {
        when(apiCommon.getServiceDates(null)).thenReturn(List.of());

        StartJobDTO dto = new StartJobDTO("Z0000", "org", EOB, "http://localhost", FHIR_NDJSON_CONTENT_TYPE, null, null, FhirVersion.R4V3, List.of());
        when(apiCommon.checkValidCreateJob(any(), isNull(), eq(FhirVersion.R4V3), any(CheckValidParametersDTO.class)))
                .thenReturn(dto);
        when(jobClient.createJob(dto)).thenReturn("job-v3-uuid");
        when(apiCommon.returnStatusForJobCreation(eq("job-v3-uuid"), eq(API_PREFIX_V3), any(), any()))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.ACCEPTED));

        BulkDataAccessAPIV3 v3 = new BulkDataAccessAPIV3(jobClient, apiCommon);
        ResponseEntity<Void> response = v3.exportAllPatients(request, EOB, FHIR_NDJSON_CONTENT_TYPE, null, null, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(apiCommon).checkContractIsAllowListedForV3();
    }

    // --- V1 Group export uses STU3 ---

    @Test
    @DisplayName("V1 exportPatientsWithContract passes STU3 and contractNumber")
    void v1_exportWithContract_usesSTU3() {
        String contractNumber = "Z9999";
        StartJobDTO dto = new StartJobDTO(contractNumber, "org", EOB, "http://localhost", FHIR_NDJSON_CONTENT_TYPE, null, null, FhirVersion.STU3, null);
        when(apiCommon.checkValidCreateJob(any(), eq(contractNumber), isNull(), isNull(), isNull(), isNull(), eq(FhirVersion.STU3)))
                .thenReturn(dto);
        when(jobClient.createJob(dto)).thenReturn("job-group-uuid");
        when(apiCommon.returnStatusForJobCreation(eq("job-group-uuid"), eq(API_PREFIX_V1), any(), any()))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.ACCEPTED));

        BulkDataAccessAPIV1 v1 = new BulkDataAccessAPIV1(jobClient, apiCommon);
        ResponseEntity<Void> response = v1.exportPatientsWithContract(request, contractNumber, null, null, null, null);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(apiCommon).checkValidCreateJob(any(), eq(contractNumber), isNull(), isNull(), isNull(), isNull(), eq(FhirVersion.STU3));
    }
}
