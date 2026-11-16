package com.tcl.csot.zhiye.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** S3 兼容对象存储（OSS / COS / MinIO）。本期只做了编译验证。 */
public class S3ObjectStore implements ObjectStore {
    private final S3Client s3;
    private final String bucket;

    public S3ObjectStore(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {
        var b = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .forcePathStyle(pathStyle);
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        if (accessKey != null && !accessKey.isBlank())
            b.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        else b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        this.s3 = b.build();
        this.bucket = bucket;
    }

    @Override
    public void put(String key, Path file) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
    }

    @Override
    public void put(String key, byte[] data) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(data));
    }

    @Override
    public Optional<InputStream> get(String key) {
        try {
            return Optional.of(s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.endsWith("/") ? prefix : prefix + "/";
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(p).build())
                .contents().forEach(o -> out.add(o.key()));
        return out;
    }

    @Override
    public void delete(String key) throws IOException {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deletePrefix(String prefix) throws IOException {
        for (String k : list(prefix)) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k).build());
    }
}
