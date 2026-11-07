package eu.neverblink.ohi.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Supplied file locks: decoding, encoding, reading and writing them. */
public final class Locks {
    private Locks() {
    }

    /**
     * Decodes a lock without inspecting its publications or retrieving its files.
     *
     * @throws LockException if the lock is invalid or of an unsupported revision
     */
    public static Lock decode(String text) {
        return decode(text, LockOperation.DECODE, null);
    }

    /**
     * Encodes a complete lock as JSON text, keeping the order of its records.
     *
     * @throws LockException if the lock is invalid or of an unsupported revision
     */
    public static String encode(Lock lock) {
        return encode(lock, LockOperation.ENCODE, null);
    }

    /**
     * Reads a lock from a UTF-8 file without a BOM.
     *
     * @throws LockException if the file can't be read, or the lock in it is invalid or of an
     *     unsupported revision
     */
    public static Lock read(Path path) {
        String supplied = path.toString();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            throw failure(LockErrorCode.STORAGE_ERROR, LockOperation.READ,
                    "Cannot read lock: " + e + ". Check the path and permissions.", supplied, e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw failure(LockErrorCode.INVALID_LOCK, LockOperation.READ,
                    "Invalid lock encoding. Supply UTF-8 JSON without a BOM.", supplied, e);
        }
        return decode(text, LockOperation.READ, supplied);
    }

    /**
     * Writes a lock to a file, replacing the file in one step. The lock is checked before anything
     * is written. The parent directory must exist, and a symbolic link at the path is replaced, not
     * followed. If writing fails, the previous file stays as it was.
     *
     * @throws LockException if the lock is invalid or of an unsupported revision, or the file can't
     *     be written
     */
    public static void write(Path path, Lock lock) {
        String supplied = path.toString();
        byte[] content = encode(lock, LockOperation.WRITE, supplied).getBytes(StandardCharsets.UTF_8);
        Path absolute = path.toAbsolutePath();
        Path staged = null;
        try {
            staged = Files.createTempFile(absolute.getParent(), "." + absolute.getFileName() + ".", ".tmp");
            Files.write(staged, content);
            Files.move(staged, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            String cleanup = "";
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException | RuntimeException cleanupFailure) {
                    cleanup = " Remove temporary file " + staged + " if it remains.";
                }
            }
            throw failure(LockErrorCode.STORAGE_ERROR, LockOperation.WRITE,
                    "Cannot replace lock: " + e
                            + ". Check the path, permissions and atomic replacement support." + cleanup,
                    supplied, e);
        }
    }

    private static Lock decode(String text, LockOperation operation, String path) {
        if (text.startsWith("﻿")) {
            throw failure(LockErrorCode.INVALID_LOCK, operation,
                    "Invalid lock JSON. Supply UTF-8 JSON without a BOM.", path, null);
        }
        JsonNode data;
        try {
            data = Json.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw failure(LockErrorCode.INVALID_LOCK, operation,
                    "Invalid lock JSON: " + e.getOriginalMessage() + ". Supply UTF-8 JSON without a BOM.",
                    path, e);
        }
        return checked(data, operation, path);
    }

    private static String encode(Lock lock, LockOperation operation, String path) {
        Lock checked = checked(Json.MAPPER.valueToTree(lock), operation, path);
        try {
            return Json.MAPPER.writeValueAsString(checked);
        } catch (JsonProcessingException e) {
            throw failure(LockErrorCode.INVALID_LOCK, operation, "Invalid lock: " + e.getOriginalMessage()
                    + ". Correct the supplied record.", path, e);
        }
    }

    /** Checks the revision first, then the record, then the rules about hashes and pairs. */
    private static Lock checked(JsonNode data, LockOperation operation, String path) {
        JsonNode version = data == null ? null : data.get("format_version");
        if (version != null && version.isIntegralNumber() && version.asLong() != 1 && version.asLong() != 2) {
            throw failure(LockErrorCode.UNSUPPORTED_VERSION, operation, "Lock revision " + version
                    + " is unsupported. Supply revision 1 or 2.", path, null);
        }
        try {
            if (data == null || !data.isObject()) {
                throw new IllegalArgumentException("a lock must be a JSON object");
            }
            Lock lock = Json.MAPPER.treeToValue(data, Lock.class);
            // Jackson checks that they are there, but they may still be null
            if (lock.getFormatVersion() == null || lock.getSelections() == null || lock.getFiles() == null) {
                throw new IllegalArgumentException("format_version, selections and files must not be null");
            }
            long revision = lock.getFormatVersion();
            for (LockedFile file : lock.getFiles()) {
                if (revision == 1 && file.getSha256() != null) {
                    throw new IllegalArgumentException("revision 1 files must omit sha256");
                }
                if (revision == 2 && file.getSha256() == null) {
                    throw new IllegalArgumentException("revision 2 files must include sha256");
                }
            }
            Set<List<String>> selections = pairs(lock.getSelections(), "selection");
            Set<List<String>> files = pairs(lock.getFiles(), "file");
            if (!files.containsAll(selections)) {
                throw new IllegalArgumentException("each selection must have a file with the same base_url and url");
            }
            return lock;
        } catch (JsonProcessingException e) {
            throw failure(LockErrorCode.INVALID_LOCK, operation, "Invalid lock: " + e.getOriginalMessage()
                    + ". Correct the supplied record.", path, e);
        } catch (IllegalArgumentException e) {
            throw failure(LockErrorCode.INVALID_LOCK, operation, "Invalid lock: " + e.getMessage()
                    + ". Correct the supplied record.", path, e);
        }
    }

    private static Set<List<String>> pairs(List<? extends ArtifactSelection> records, String kind) {
        Set<List<String>> pairs = new HashSet<>();
        for (ArtifactSelection record : records) {
            if (!pairs.add(List.of(record.getBaseUrl(), record.getUrl()))) {
                throw new IllegalArgumentException("each " + kind + " pair must occur once");
            }
        }
        return pairs;
    }

    private static LockException failure(
            LockErrorCode code, LockOperation operation, String message, String path, Throwable cause) {
        LockError error = new LockError();
        error.setCode(code);
        error.setOperation(operation);
        error.setMessage(message);
        error.setPath(path);
        return new LockException(error, cause);
    }
}
