package dev.irv;

import static dev.irv.Util.*;

import java.net.*;
import java.nio.file.*;
import org.springframework.stereotype.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class Blobs {
  private final java.util.concurrent.ConcurrentMap<String, Object> locks =
      new java.util.concurrent.ConcurrentHashMap<>();

  public Object lock(String id) {
    return locks.computeIfAbsent(id, k -> new Object());
  }

  final S3Client s3;
  final String bucket = env("S3_BUCKET", "");
  final Path local = Path.of(env("IRV_BLOB_DIR", "./data/blobs"));

  public Blobs() {
    s3 =
        bucket.isEmpty()
            ? null
            : S3Client.builder()
                .region(Region.of(env("AWS_REGION", "auto")))
                .endpointOverride(URI.create(env("S3_ENDPOINT", "https://fly.storage.tigris.dev")))
                .forcePathStyle(true)
                .build();
  }

  String key(String sandbox, String hash) {
    if (!sandbox.matches("[a-f0-9-]{36}") || !hash.matches("[a-f0-9]{64}"))
      throw bad("Invalid blob key");
    return sandbox + "/chunks/" + hash;
  }

  public void put(String sandbox, String hash, byte[] bytes) {
    String k = key(sandbox, hash);
    if (!hash(bytes).equals(hash)) throw bad("Chunk hash mismatch");
    try {
      if (s3 != null)
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(k).build(), RequestBody.fromBytes(bytes));
      else {
        Path p = local.resolve(k);
        Files.createDirectories(p.getParent());
        Path tmp = Files.createTempFile(p.getParent(), "chunk-", ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Chunk upload failed", e);
    }
  }

  public byte[] get(String sandbox, String hash) {
    String k = key(sandbox, hash);
    try {
      return s3 != null
          ? s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(k).build())
              .asByteArray()
          : Files.readAllBytes(local.resolve(k));
    } catch (Exception e) {
      throw new IllegalStateException("Chunk unavailable", e);
    }
  }

  public void delete(String sandbox, String hash) {
    String k = key(sandbox, hash);
    try {
      if (s3 != null) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k).build());
      else Files.deleteIfExists(local.resolve(k));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
