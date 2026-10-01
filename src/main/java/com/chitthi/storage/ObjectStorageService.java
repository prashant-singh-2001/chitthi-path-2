package com.chitthi.storage;

import com.chitthi.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;

@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    public ObjectStorageService(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    /**
     * Upload bytes to the configured bucket under a given key.
     */
    public String uploadFile(String key, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));
        log.info("Uploaded object to bucket: {}, key: {}, size: {} bytes", properties.bucket(), key, content.length);
        return key;
    }

    /**
     * Download bytes from the configured bucket.
     */
    public byte[] downloadFile(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();

        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        return response.asByteArray();
    }

    /**
     * Check if an object exists in the configured bucket.
     */
    public boolean fileExists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate a presigned download URL for a key.
     */
    public String generatePresignedUrl(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(properties.presignedUrlExpirationMinutes()))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }

    /**
     * Delete an object.
     */
    public void deleteFile(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();

        s3Client.deleteObject(request);
        log.info("Deleted object from bucket: {}, key: {}", properties.bucket(), key);
    }

    /**
     * Delete all objects matching a given prefix.
     */
    public void deletePrefix(String prefix) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(properties.bucket())
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listResponse;
            int totalDeleted = 0;
            do {
                listResponse = s3Client.listObjectsV2(listRequest);
                if (listResponse.hasContents() && !listResponse.contents().isEmpty()) {
                    java.util.List<ObjectIdentifier> toDelete = listResponse.contents().stream()
                            .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                            .toList();

                    DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                            .bucket(properties.bucket())
                            .delete(Delete.builder().objects(toDelete).build())
                            .build();

                    s3Client.deleteObjects(deleteRequest);
                    totalDeleted += toDelete.size();
                }

                String token = listResponse.nextContinuationToken();
                listRequest = listRequest.toBuilder().continuationToken(token).build();
            } while (listResponse.isTruncated());

            log.info("Deleted {} objects under prefix: {} from bucket: {}", totalDeleted, prefix, properties.bucket());
        } catch (Exception e) {
            log.error("Failed to delete objects under prefix: {} from bucket: {}", prefix, properties.bucket(), e);
            throw new RuntimeException("Failed to purge storage prefix: " + prefix, e);
        }
    }
}
