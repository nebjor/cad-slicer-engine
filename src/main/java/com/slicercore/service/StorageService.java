package com.slicercore.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class StorageService {

    private final MinioClient minioClient;

    @Value("${s3.bucket-name}")
    private String bucketName;

    public StorageService(
            @Value("${s3.endpoint}") String endpoint,
            @Value("${s3.access-key}") String accessKey,
            @Value("${s3.secret-key}") String secretKey) {

        // Initialize S3 client using credentials configured in application.yml
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    public void uploadFile(String objectName, InputStream inputStream, String contentType) throws Exception {
        // Upload file (STL or G-code) directly to MinIO bucket
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        // -1 indicates unknown object size, 10485760 sets chunk size to 10MB
                        .stream(inputStream, -1L, 10485760L)
                        .contentType(contentType)
                        .build()
        );
    }

    public InputStream downloadFile(String objectName) throws Exception {
        // Download file as a stream to feed into the slicer execution engine
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
    }
}