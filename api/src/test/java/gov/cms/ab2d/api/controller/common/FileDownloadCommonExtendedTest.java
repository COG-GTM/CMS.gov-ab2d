package gov.cms.ab2d.api.controller.common;

import gov.cms.ab2d.api.remote.JobClient;
import gov.cms.ab2d.common.model.PdpClient;
import gov.cms.ab2d.common.service.PdpClientService;
import gov.cms.ab2d.eventclient.clients.SQSEventClient;
import gov.cms.ab2d.job.service.JobOutputMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static gov.cms.ab2d.common.util.Constants.FHIR_NDJSON_CONTENT_TYPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileDownloadCommonExtendedTest {

    @Mock
    private JobClient jobClient;
    @Mock
    private SQSEventClient eventLogger;
    @Mock
    private PdpClientService pdpClientService;

    private FileDownloadCommon fileDownloadCommon;
    private PdpClient pdpClient;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        pdpClient = new PdpClient();
        pdpClient.setOrganization("TestOrg");
        when(pdpClientService.getCurrentClient()).thenReturn(pdpClient);

        fileDownloadCommon = new FileDownloadCommon(jobClient, eventLogger, pdpClientService);
    }

    // --- downloadFile: uncompressed file without Accept-Encoding: gzip ---

    @Test
    @DisplayName("downloadFile returns 200 with correct content-type for uncompressed file")
    void downloadFile_uncompressed_noGzipHeader_returnsOk() throws IOException {
        File testFile = createTestFile("test_data.ndjson", "test content line\n");
        Resource resource = new FileSystemResource(testFile);
        when(jobClient.getResourceForJob("job-1", "test_data.ndjson.gz", "TestOrg"))
                .thenReturn(resource);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> result = fileDownloadCommon.downloadFile("job-1", "test_data.ndjson", request, response);

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals(FHIR_NDJSON_CONTENT_TYPE, response.getHeader("Content-Type"));
        assertNull(response.getHeader("Content-Encoding"));
        assertNotNull(response.getHeader("Content-Disposition"));
        assertTrue(response.getHeader("Content-Disposition").contains("test_data.ndjson"));
        verify(jobClient).incrementDownload(testFile, "job-1");
    }

    // --- downloadFile: gzip compressed file with Accept-Encoding: gzip ---

    @Test
    @DisplayName("downloadFile sets Content-Encoding gzip when Accept-Encoding gzip and file is .gz")
    void downloadFile_gzipFile_withGzipAccept_setsGzipEncoding() throws IOException {
        File gzFile = createGzipFile("test_data.ndjson.gz", "compressed content\n");
        Resource resource = new FileSystemResource(gzFile);
        when(jobClient.getResourceForJob("job-2", "test_data.ndjson.gz", "TestOrg"))
                .thenReturn(resource);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Encoding", "gzip, deflate, br");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> result = fileDownloadCommon.downloadFile("job-2", "test_data.ndjson", request, response);

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals("gzip", response.getHeader("Content-Encoding"));
        verify(jobClient).incrementDownload(gzFile, "job-2");
    }

    // --- downloadFile: uncompressed file with Accept-Encoding: gzip (should compress) ---

    @Test
    @DisplayName("downloadFile compresses output when file is uncompressed but client accepts gzip")
    void downloadFile_uncompressedFile_withGzipAccept_compresses() throws IOException {
        File testFile = createTestFile("plain.ndjson", "plain content\n");
        Resource resource = new FileSystemResource(testFile);
        when(jobClient.getResourceForJob("job-3", "plain.ndjson.gz", "TestOrg"))
                .thenReturn(resource);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Encoding", "gzip");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> result = fileDownloadCommon.downloadFile("job-3", "plain.ndjson", request, response);

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals("gzip", response.getHeader("Content-Encoding"));
    }

    // --- downloadFile increments download count ---

    @Test
    @DisplayName("downloadFile calls incrementDownload on the jobClient")
    void downloadFile_incrementsDownloadCount() throws IOException {
        File testFile = createTestFile("count.ndjson", "data\n");
        Resource resource = new FileSystemResource(testFile);
        when(jobClient.getResourceForJob("job-4", "count.ndjson.gz", "TestOrg"))
                .thenReturn(resource);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileDownloadCommon.downloadFile("job-4", "count.ndjson", request, response);

        verify(jobClient).incrementDownload(testFile, "job-4");
    }

    // --- getDownloadResource: fallback to uncompressed when gz not found ---

    @Test
    @DisplayName("getDownloadResource falls back to uncompressed file when .gz throws RuntimeException")
    void getDownloadResource_fallsBackToUncompressed() {
        Resource uncompressedResource = mock(Resource.class);
        when(jobClient.getResourceForJob("job-5", "data.ndjson.gz", "TestOrg"))
                .thenThrow(new RuntimeException("not found"));
        when(jobClient.getResourceForJob("job-5", "data.ndjson", "TestOrg"))
                .thenReturn(uncompressedResource);

        Resource result = fileDownloadCommon.getDownloadResource("job-5", "data.ndjson");

        assertEquals(uncompressedResource, result);
    }

    // --- getDownloadResource: re-throws JobOutputMissingException from gz lookup ---

    @Test
    @DisplayName("getDownloadResource re-throws JobOutputMissingException from compressed file lookup")
    void getDownloadResource_rethrowsJobOutputMissing() {
        when(jobClient.getResourceForJob("job-6", "data.ndjson.gz", "TestOrg"))
                .thenThrow(new JobOutputMissingException("expired"));

        assertThrows(JobOutputMissingException.class,
                () -> fileDownloadCommon.getDownloadResource("job-6", "data.ndjson"));
    }

    private File createTestFile(String name, String content) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private File createGzipFile(String name, String content) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(file))) {
            gos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
