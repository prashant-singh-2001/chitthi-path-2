package com.chitthi.storage;

import com.chitthi.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObjectStorageServiceTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private S3Presigner s3Presigner;

    private StorageProperties properties;
    private ObjectStorageService objectStorageService;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties(
                "http://localhost:9000",
                "us-east-1",
                "minioadmin",
                "minioadmin",
                "test-bucket",
                60
        );
        objectStorageService = new ObjectStorageService(s3Client, s3Presigner, properties);
    }

    @Test
    void uploadFile_shouldPutObjectToS3() {
        byte[] content = "Hello Chitthi".getBytes();
        String key = objectStorageService.uploadFile("documents/doc1/original.pdf", content, "application/pdf");

        assertThat(key).isEqualTo("documents/doc1/original.pdf");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void downloadFile_shouldRetrieveBytes() {
        byte[] expected = "File content".getBytes();
        GetObjectResponse getObjectResponse = GetObjectResponse.builder().contentLength((long) expected.length).build();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(getObjectResponse, expected);

        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] result = objectStorageService.downloadFile("documents/doc1/original.pdf");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void fileExists_whenExists_shouldReturnTrue() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertThat(objectStorageService.fileExists("documents/doc1/original.pdf")).isTrue();
    }

    @Test
    void fileExists_whenNoSuchKey_shouldReturnFalse() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(objectStorageService.fileExists("documents/doc1/missing.pdf")).isFalse();
    }

    @Test
    void deleteFile_shouldDeleteObject() {
        objectStorageService.deleteFile("documents/doc1/original.pdf");
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deletePrefix_shouldListAndBatchDeleteAllMatchingObjects() {
        String prefix = "documents/doc123/";
        S3Object obj1 = S3Object.builder().key("documents/doc123/original.pdf").build();
        S3Object obj2 = S3Object.builder().key("documents/doc123/audio/full_en.wav").build();

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(List.of(obj1, obj2))
                .isTruncated(false)
                .build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        objectStorageService.deletePrefix(prefix);

        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void deletePrefix_whenNoObjectsMatch_shouldNotCallDeleteObjects() {
        String prefix = "documents/empty/";
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(List.of())
                .isTruncated(false)
                .build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        objectStorageService.deletePrefix(prefix);

        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
