"""Decode publication metadata without fetching its references."""

import json
from importlib.resources import files

from jsonschema import Draft202012Validator, ValidationError

from .generated import Publication


_validator = Draft202012Validator(
    json.loads(files(__package__).joinpath("package.schema.json").read_text(encoding="utf-8")),
    format_checker=Draft202012Validator.FORMAT_CHECKER,
)


def decode_publication(text: str) -> Publication:
    """Return validated publication fields, discarding extras, or raise ValueError."""
    data = json.loads(text)
    try:
        _validator.validate(data)
    except ValidationError as error:
        raise ValueError(f"{error.json_path}: {error.message}") from error
    # JSON Schema accepts integral numbers such as 42.0, unlike StrictInt.
    data["schema_version"] = int(data["schema_version"])
    for artifact in data["artifacts"]:
        artifact["size_bytes"] = int(artifact["size_bytes"])
    publication = Publication.model_validate(data, extra="ignore")
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
