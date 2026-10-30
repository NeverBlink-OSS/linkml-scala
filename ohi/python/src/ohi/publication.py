"""Decode publication metadata without fetching its references."""

from .generated import Publication


def decode_publication(text: str) -> Publication:
    """Return validated publication fields, discarding extras, or raise ValueError."""
    publication = Publication.model_validate_json(text, extra="ignore")
    if publication.preferred_prefix is not None and publication.namespace_iri is None:
        raise ValueError("preferred_prefix requires namespace_iri")
    artifact_urls = [artifact.url for artifact in publication.artifacts]
    if len(set(artifact_urls)) != len(artifact_urls):
        raise ValueError("artifacts must have unique URLs")
    dependency_urls = [dependency.base_url for dependency in publication.dependencies]
    if len(set(dependency_urls)) != len(dependency_urls):
        raise ValueError("dependencies must have unique base_url values")
    if publication.base_url in dependency_urls:
        raise ValueError("dependencies must not contain the publication itself")
    return publication
