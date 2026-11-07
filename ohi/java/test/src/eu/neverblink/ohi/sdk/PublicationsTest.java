package eu.neverblink.ohi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicationsTest {
    static final Path EXAMPLES = Path.of(System.getenv("OHI_EXAMPLES"));

    static ObjectNode example(String name) {
        try {
            return (ObjectNode) Json.MAPPER.readTree(Files.readString(EXAMPLES.resolve(name + "-package.json")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Publication decode(JsonNode data) {
        return Publications.decode(data.toString());
    }

    ObjectNode data;

    @BeforeEach
    void setUp() {
        data = example("sensors");
    }

    void reject() {
        assertThrows(IllegalArgumentException.class, () -> decode(data));
    }

    ObjectNode artifact(int index) {
        return (ObjectNode) data.withArray("artifacts").get(index);
    }

    @Test
    void mapsPublicationFields() {
        Publication publication = decode(data);
        assertEquals(2, publication.getSchemaVersion());
        assertEquals("https://example.org/packages/sensors/1.0.0/", publication.getBaseUrl());
        assertEquals("1.0.0", publication.getVersion());
        assertEquals("example", publication.getNamespace());
        assertEquals("sensors", publication.getName());
        assertEquals("Sensors", publication.getTitle());
        assertEquals(OffsetDateTime.of(2026, 10, 13, 12, 30, 0, 0, ZoneOffset.UTC), publication.getPublishedAt());
        assertEquals("Example Project", publication.getPublisher());
        assertEquals(List.of("Apache-2.0"), publication.getLicenses());
        assertEquals("https://example.org/sensors#", publication.getNamespaceIri());
        assertEquals("sensors", publication.getPreferredPrefix());
        assertEquals("Sensors and their observations.", publication.getDescription());
        assertEquals(List.of("sensors", "observations"), publication.getKeywords());
        Artifact first = publication.getArtifacts().get(0);
        assertEquals(Format.OWL_TURTLE, first.getFormat());
        assertEquals(ArtifactOrigin.UPSTREAM, first.getOrigin());
        assertEquals("https://example.org/packages/sensors/1.0.0/sensors.ttl", first.getUrl());
        assertEquals(List.of("https://example.org/packages/sensors/1.0.0/sensors.ttl"), first.getRoots());
        assertEquals(42, first.getSizeBytes());
        assertEquals("0123456789abcdef".repeat(4), first.getSha256());
        assertEquals(Format.LINKML_YAML, publication.getArtifacts().get(1).getFormat());
        assertEquals(ArtifactOrigin.GENERATED, publication.getArtifacts().get(1).getOrigin());
        assertEquals(List.of("https://example.org/packages/units/2.0.0/"), bases(publication));
    }

    @Test
    void acceptsNoDependenciesAndEmptyKeywords() {
        Publication publication = decode(example("units"));
        assertEquals("https://example.org/packages/units/2.0.0/", publication.getBaseUrl());
        assertEquals(List.of(), publication.getDependencies());
        assertEquals(List.of(), publication.getKeywords());
        assertNull(publication.getNamespaceIri());
        assertNull(publication.getPreferredPrefix());
    }

    @ParameterizedTest
    @ValueSource(strings = {"namespace", "keywords", "dependencies", "title", "published_at", "publisher", "licenses"})
    void requiresPublicationFields(String field) {
        data.remove(field);
        reject();
    }

    @Test
    void requiresArtifactRootsDespitePublicationRoots() {
        data.set("roots", artifact(0).remove("roots"));
        reject();
    }

    @Test
    void requiresDependencyBaseDespiteFileMetadata() {
        data.withArray("dependencies").set(0, Json.MAPPER.createObjectNode().put("url", "https://example.org/units.ttl"));
        reject();
    }

    @Test
    void requiresArtifactSizeAndDigest() {
        artifact(0).remove("size_bytes");
        reject();
        data = example("sensors");
        artifact(0).remove("sha256");
        reject();
    }

    @Test
    void rejectsNonobjectRecords() {
        assertThrows(IllegalArgumentException.class, () -> Publications.decode("[]"));
        data.withArray("artifacts").setNull(0);
        reject();
        data = example("sensors");
        data.withArray("dependencies").set(0, "units");
        reject();
    }

    @Test
    void rejectsScalarKeywords() {
        data.put("keywords", "sensors");
        reject();
    }

    @Test
    void rejectsUnknownFormatAndOrigin() {
        artifact(0).put("format", "future-format");
        reject();
        data = example("sensors");
        artifact(0).put("origin", "FUTURE");
        reject();
    }

    @Test
    void rejectsMalformedDatetime() {
        data.put("published_at", "2026-02-30T12:30:00Z");
        reject();
    }

    @Test
    void preservesTimestampPrecisionAndOffset() {
        data.put("published_at", "2026-10-13T12:30:00.123456789+02:00");
        assertEquals(OffsetDateTime.of(2026, 10, 13, 12, 30, 0, 123456789, ZoneOffset.ofHours(2)),
                decode(data).getPublishedAt());
    }

    @Test
    void preservesOpaqueVersion() {
        data.put("version", " release + amendment ");
        assertEquals(" release + amendment ", decode(data).getVersion());
    }

    @Test
    void requiresNamespaceIriForAPreferredPrefix() {
        data.remove("namespace_iri");
        reject();
    }

    @Test
    void discardsExtrasAtEachRecordLevel() {
        data.put("package", "obsolete");
        data.putArray("roots").add("obsolete");
        artifact(0).put("dependencies", "obsolete");
        ((ObjectNode) data.withArray("dependencies").get(0)).put("url", "obsolete");
        Publication publication = decode(data);
        assertTrue(publication.getExtraProperties().isEmpty());
        assertTrue(publication.getArtifacts().get(0).getExtraProperties().isEmpty());
        assertTrue(publication.getDependencies().get(0).getExtraProperties().isEmpty());
        assertEquals("example", publication.getNamespace());
        assertEquals("https://example.org/packages/units/2.0.0/", bases(publication).get(0));
    }

    @Test
    void acceptsHttpReferencesAndOtherUriSchemesAsSpelled() {
        Publication local = Publications.decode(data.toString().replace("https://example.org", "http://localhost:8080"));
        assertEquals("http://localhost:8080/packages/sensors/1.0.0/", local.getBaseUrl());
        assertEquals("http://localhost:8080/packages/units/2.0.0/", bases(local).get(0));
        data.put("base_url", "urn:ohi:sensors:1");
        assertEquals("urn:ohi:sensors:1", decode(data).getBaseUrl());
        data.put("base_url", "https://Example.org/publication?revision=1#entry");
        assertEquals("https://Example.org/publication?revision=1#entry", decode(data).getBaseUrl());
    }

    @Test
    void rejectsDuplicateArtifactUrls() {
        artifact(1).put("url", artifact(0).get("url").asText());
        reject();
    }

    @Test
    void acceptsRepeatedArtifactFormats() {
        artifact(1).put("format", "owl-turtle");
        Publication publication = decode(data);
        assertEquals(Format.OWL_TURTLE, publication.getArtifacts().get(1).getFormat());
    }

    @Test
    void rejectsDuplicateDependencyBasesAndSelfDependency() {
        data.withArray("dependencies").addObject()
                .put("base_url", "https://example.org/packages/units/2.0.0/").put("url", "ignored");
        reject();
        data = example("sensors");
        ((ObjectNode) data.withArray("dependencies").get(0)).put("base_url", "https://example.org/packages/sensors/1.0.0/");
        reject();
    }

    @Test
    void preservesDependencyIdentityOrderAndRoots() {
        ArrayNode dependencies = data.putArray("dependencies");
        dependencies.addObject().put("base_url", "https://example.org/units");
        dependencies.addObject().put("base_url", "https://Example.org/units");
        artifact(0).putArray("roots").add("https://example.org/b.ttl").add("https://example.org/a.ttl")
                .add("https://example.org/b.ttl");
        Publication publication = decode(data);
        assertEquals(List.of("https://example.org/units", "https://Example.org/units"), bases(publication));
        assertEquals(List.of("https://example.org/b.ttl", "https://example.org/a.ttl", "https://example.org/b.ttl"),
                publication.getArtifacts().get(0).getRoots());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> Publications.decode("{"));
        assertThrows(IllegalArgumentException.class, () -> Publications.decode(data + " {}"));
    }

    static List<String> bases(Publication publication) {
        return publication.getDependencies().stream().map(Dependency::getBaseUrl).collect(Collectors.toList());
    }
}
