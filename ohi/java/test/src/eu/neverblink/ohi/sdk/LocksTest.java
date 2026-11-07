package eu.neverblink.ohi.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocksTest {
    static final String HASH_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String HASH_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    static ObjectNode example(int revision) {
        try {
            ObjectNode data = (ObjectNode) Json.MAPPER.readTree(
                    Files.readString(PublicationsTest.EXAMPLES.resolve("selection.lock.json")));
            if (revision == 2) {
                data.put("format_version", 2);
                file(data, 0).put("sha256", HASH_A);
                file(data, 1).put("sha256", HASH_B);
            }
            return data;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode file(ObjectNode data, int index) {
        return (ObjectNode) data.withArray("files").get(index);
    }

    static LockError assertError(Supplier<?> action, LockErrorCode code, LockOperation operation, String path) {
        LockException failure = assertThrows(LockException.class, action::get);
        LockError error = failure.getError();
        assertEquals(code, error.getCode());
        assertEquals(operation, error.getOperation());
        assertEquals(path, error.getPath());
        assertFalse(error.getMessage().isBlank());
        assertEquals(error.getMessage(), failure.getMessage());
        return error;
    }

    static void reject(JsonNode data, LockErrorCode code) {
        assertError(() -> Locks.decode(data.toString()), code, LockOperation.DECODE, null);
    }

    static void reject(JsonNode data) {
        reject(data, LockErrorCode.INVALID_LOCK);
    }

    /** The JSON of a lock, which must be the example's. */
    static void assertExample(JsonNode data, int revision) {
        ObjectNode expected = example(revision);
        assertEquals(expected, data);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void decodesAndEncodesExactRecordsInSuppliedOrder(int revision) throws IOException {
        Lock lock = Locks.decode(example(revision).toString());
        assertInstanceOf(LockedFile.class, lock.getFiles().get(0));
        assertEquals(Format.OWL_TURTLE, lock.getFiles().get(0).getFormat());
        assertEquals("https://Files.example/Z%2F.ttl?download=1#file", lock.getSelections().get(0).getUrl());
        assertExample(Json.MAPPER.readTree(Locks.encode(lock)), revision);
    }

    @Test
    void encodesALockMadeInCode() throws IOException {
        ArtifactSelection selection = new ArtifactSelection();
        selection.setBaseUrl("urn:pub");
        selection.setUrl("urn:file");
        LockedFile file = new LockedFile();
        file.setBaseUrl("urn:pub");
        file.setUrl("urn:file");
        file.setFormat(Format.OWL_TURTLE);
        Lock lock = new Lock();
        lock.setFormatVersion(1L);
        lock.setSelections(List.of(selection));
        lock.setFiles(List.of(file));
        assertEquals(
                Json.MAPPER.readTree("{\"format_version\":1,\"selections\":[{\"base_url\":\"urn:pub\",\"url\":\"urn:file\"}],"
                        + "\"files\":[{\"base_url\":\"urn:pub\",\"url\":\"urn:file\",\"format\":\"owl-turtle\"}]}"),
                Json.MAPPER.readTree(Locks.encode(lock)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void preservesRequiredEmptyArrays(int revision) throws IOException {
        String text = "{\"format_version\":" + revision + ",\"selections\":[],\"files\":[]}";
        Lock lock = Locks.decode(text);
        assertEquals(List.of(), lock.getSelections());
        assertEquals(Json.MAPPER.readTree(text), Json.MAPPER.readTree(Locks.encode(lock)));
    }

    @Test
    void rejectsRevisionOneHashesAndRevisionTwoWithoutThem() {
        ObjectNode one = example(1);
        file(one, 1).put("sha256", HASH_A);
        reject(one);
        ObjectNode two = example(2);
        file(two, 1).remove("sha256");
        reject(two);
        ObjectNode twoNull = example(2);
        file(twoNull, 1).putNull("sha256");
        reject(twoNull);
    }

    @Test
    void rejectsMalformedJsonALeadingBomAndTrailingData() {
        assertError(() -> Locks.decode("{"), LockErrorCode.INVALID_LOCK, LockOperation.DECODE, null);
        assertError(() -> Locks.decode("﻿" + example(1)), LockErrorCode.INVALID_LOCK, LockOperation.DECODE, null);
        assertError(() -> Locks.decode(example(1) + " {}"), LockErrorCode.INVALID_LOCK, LockOperation.DECODE, null);
    }

    @Test
    void rejectsANonobjectRoot() {
        assertError(() -> Locks.decode("[]"), LockErrorCode.INVALID_LOCK, LockOperation.DECODE, null);
        assertError(() -> Locks.decode("null"), LockErrorCode.INVALID_LOCK, LockOperation.DECODE, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"selections", "files"})
    void rejectsMissingOrNullCollections(String field) {
        ObjectNode missing = example(1);
        missing.remove(field);
        reject(missing);
        ObjectNode none = example(1);
        none.putNull(field);
        reject(none);
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "selections", "files"})
    void rejectsUnknownPropertiesAtEachRecordLevel(String scope) {
        ObjectNode data = example(1);
        ObjectNode target = scope.equals("root") ? data : (ObjectNode) data.withArray(scope).get(0);
        target.put("future", true);
        reject(data);
    }

    @Test
    void rejectsAnUnknownFormat() {
        ObjectNode data = example(1);
        file(data, 0).put("format", "owl");
        reject(data);
    }

    @Test
    void reportsAnUnsupportedRevisionBeforeMissingFields() {
        reject(Json.MAPPER.createObjectNode().put("format_version", 3), LockErrorCode.UNSUPPORTED_VERSION);
    }

    @Test
    void rejectsAMissingOrBooleanRevision() {
        ObjectNode data = example(1);
        data.remove("format_version");
        reject(data);
        data.put("format_version", true);
        reject(data);
    }

    @Test
    void rejectsDuplicatePairs() {
        ObjectNode selections = example(1);
        selections.withArray("selections").add(selections.get("selections").get(0).deepCopy());
        reject(selections);
        ObjectNode files = example(1);
        files.withArray("files").add(file(files, 0).deepCopy().put("format", "rdfs-turtle"));
        reject(files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"base_url", "url"})
    void requiresAFileMatchingBothSelectionFields(String field) {
        ObjectNode data = example(1);
        file(data, 0).put(field, "urn:other");
        reject(data);
    }

    @Test
    void preservesDistinctExactPairsAndOrder() throws IOException {
        List<List<String>> pairs = List.of(
                List.of("https://Example.org/pub", "https://example.org/f%2F"),
                List.of("https://example.org/pub", "https://example.org/f%2f"),
                List.of("urn:pub", "urn:z"),
                List.of("urn:pub", "urn:a"));
        ObjectNode data = Json.MAPPER.createObjectNode().put("format_version", 1);
        for (List<String> pair : pairs) {
            data.withArray("selections").addObject().put("base_url", pair.get(0)).put("url", pair.get(1));
            data.withArray("files").addObject().put("base_url", pair.get(0)).put("url", pair.get(1))
                    .put("format", "owl-turtle");
        }
        Lock lock = Locks.decode(data.toString());
        assertEquals(pairs, lock.getFiles().stream()
                .map(f -> List.of(f.getBaseUrl(), f.getUrl())).collect(Collectors.toList()));
        assertEquals(data, Json.MAPPER.readTree(Locks.encode(lock)));
    }

    @Test
    void encodeRejectsAnInvalidLock() {
        Lock duplicate = Locks.decode(example(1).toString());
        duplicate.setFiles(Stream.concat(duplicate.getFiles().stream(), Stream.of(duplicate.getFiles().get(0)))
                .collect(Collectors.toList()));
        assertError(() -> Locks.encode(duplicate), LockErrorCode.INVALID_LOCK, LockOperation.ENCODE, null);

        Lock unsupported = new Lock();
        unsupported.setFormatVersion(3L);
        assertError(() -> Locks.encode(unsupported), LockErrorCode.UNSUPPORTED_VERSION, LockOperation.ENCODE, null);

        Lock hashed = Locks.decode(example(1).toString());
        hashed.getFiles().get(1).setSha256(HASH_A);
        assertError(() -> Locks.encode(hashed), LockErrorCode.INVALID_LOCK, LockOperation.ENCODE, null);
    }

    // Files

    @TempDir
    Path directory;

    Path path() {
        return directory.resolve("chosen.lock.json");
    }

    List<String> entries() throws IOException {
        try (Stream<Path> list = Files.list(directory)) {
            return list.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void writesCompleteUtf8JsonWithoutBomAndReadsItBack() throws IOException {
        Lock lock = Locks.decode(example(1).toString());
        Locks.write(path(), lock);
        byte[] content = Files.readAllBytes(path());
        assertFalse(content[0] == (byte) 0xEF);
        assertExample(Json.MAPPER.readTree(content), 1);
        assertExample(Json.MAPPER.readTree(Locks.encode(Locks.read(path()))), 1);
        assertEquals(List.of("chosen.lock.json"), entries());
    }

    @Test
    void replacesExistingBytesAsAWhole() throws IOException {
        Files.write(path(), "old invalid bytes".repeat(100).getBytes(StandardCharsets.UTF_8));
        Locks.write(path(), Locks.decode("{\"format_version\":1,\"selections\":[],\"files\":[]}"));
        assertEquals(Json.MAPPER.readTree("{\"format_version\":1,\"selections\":[],\"files\":[]}"),
                Json.MAPPER.readTree(Files.readString(path())));
        assertEquals(List.of("chosen.lock.json"), entries());
    }

    @Test
    void readDoesNotChangeTheFile() throws IOException {
        byte[] content = Files.readAllBytes(PublicationsTest.EXAMPLES.resolve("selection.lock.json"));
        Files.write(path(), content);
        assertExample(Json.MAPPER.readTree(Locks.encode(Locks.read(path()))), 1);
        assertArrayEquals(content, Files.readAllBytes(path()));
    }

    @Test
    void rejectsInvalidEncodings() throws IOException {
        String text = "{\"format_version\": 1, \"selections\": [], \"files\": []}";
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        for (byte[] content : List.of(
                new byte[] {(byte) 0xFF},
                concat(bom, text.getBytes(StandardCharsets.UTF_8)),
                text.getBytes(StandardCharsets.UTF_16))) {
            Files.write(path(), content);
            assertError(() -> Locks.read(path()), LockErrorCode.INVALID_LOCK, LockOperation.READ, path().toString());
            assertArrayEquals(content, Files.readAllBytes(path()));
        }
    }

    @Test
    void reportsAMissingFileWithTheSuppliedPathAndCause() {
        Path supplied = Path.of(directory + "/./missing.lock.json");
        LockException failure = assertThrows(LockException.class, () -> Locks.read(supplied));
        assertEquals(LockErrorCode.STORAGE_ERROR, failure.getError().getCode());
        assertEquals(supplied.toString(), failure.getError().getPath());
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
    }

    @Test
    void readReportsAnUnsupportedRevision() throws IOException {
        Files.writeString(path(), "{\"format_version\": 3}");
        assertError(() -> Locks.read(path()), LockErrorCode.UNSUPPORTED_VERSION, LockOperation.READ, path().toString());
    }

    @Test
    void checksBeforeWritingAnything() throws IOException {
        Files.writeString(path(), "previous bytes");
        Lock lock = Locks.decode(example(1).toString());
        lock.setFormatVersion(2L);
        assertError(() -> {
            Locks.write(path(), lock);
            return null;
        }, LockErrorCode.INVALID_LOCK, LockOperation.WRITE, path().toString());
        assertEquals("previous bytes", Files.readString(path()));
        assertEquals(List.of("chosen.lock.json"), entries());

        Lock unsupported = new Lock();
        unsupported.setFormatVersion(3L);
        Path absent = directory.resolve("absent").resolve("lock.json");
        assertError(() -> {
            Locks.write(absent, unsupported);
            return null;
        }, LockErrorCode.UNSUPPORTED_VERSION, LockOperation.WRITE, absent.toString());
    }

    @Test
    void refusesAnAbsentParentWithoutCreatingDirectories() throws IOException {
        Path absent = directory.resolve("absent").resolve("lock.json");
        LockError error = assertError(() -> {
            Locks.write(absent, Locks.decode(example(1).toString()));
            return null;
        }, LockErrorCode.STORAGE_ERROR, LockOperation.WRITE, absent.toString());
        assertNotNull(error.getMessage());
        assertEquals(List.of(), entries());
    }

    @Test
    void replacesASymbolicLinkWithoutChangingItsTarget() throws IOException {
        Path referent = directory.resolve("referent.json");
        Files.writeString(referent, "referent bytes");
        try {
            Files.createSymbolicLink(path(), referent);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not supported here");
        }
        Locks.write(path(), Locks.decode(example(1).toString()));
        assertFalse(Files.isSymbolicLink(path()));
        assertEquals("referent bytes", Files.readString(referent));
        assertTrue(Files.readString(path()).startsWith("{"));
        assertNull(Locks.read(path()).getFiles().get(0).getSha256());
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
