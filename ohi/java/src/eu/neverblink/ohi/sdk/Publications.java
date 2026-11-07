package eu.neverblink.ohi.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashSet;
import java.util.Set;

/** Publication metadata. */
public final class Publications {
    private Publications() {
    }

    /**
     * Decodes publication metadata without fetching what it references. Properties that the model
     * doesn't have are discarded.
     *
     * @throws IllegalArgumentException if the text is not valid publication metadata
     */
    public static Publication decode(String text) {
        Publication publication;
        try {
            publication = Json.MAPPER.readValue(text, Publication.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid publication metadata: " + e.getOriginalMessage(), e);
        }
        if (publication == null) {
            throw new IllegalArgumentException("Invalid publication metadata: null");
        }
        if (publication.getPreferredPrefix() != null && publication.getNamespaceIri() == null) {
            throw new IllegalArgumentException("preferred_prefix requires namespace_iri");
        }
        Set<String> urls = new HashSet<>();
        for (Artifact artifact : publication.getArtifacts()) {
            if (!urls.add(artifact.getUrl())) {
                throw new IllegalArgumentException("artifacts must have unique URLs");
            }
            artifact.getExtraProperties().clear();
        }
        Set<String> bases = new HashSet<>();
        for (Dependency dependency : publication.getDependencies()) {
            if (!bases.add(dependency.getBaseUrl())) {
                throw new IllegalArgumentException("dependencies must have unique base_url values");
            }
            dependency.getExtraProperties().clear();
        }
        if (bases.contains(publication.getBaseUrl())) {
            throw new IllegalArgumentException("dependencies must not contain the publication itself");
        }
        publication.getExtraProperties().clear();
        return publication;
    }
}
