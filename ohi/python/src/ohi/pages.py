"""Links to pages on ohi.dev."""

from typing import Protocol
from urllib.parse import quote


class Release(Protocol):
    """Fields that identify one release, as found on a Publication or a SearchHit."""

    namespace: str
    name: str
    version: str


def _segment(value: str) -> str:
    # Same characters left as they are as JavaScript's encodeURIComponent.
    return quote(value, safe="!*'()")


def ohi_release_page(release: Release) -> str:
    """Page of one release on ohi.dev, such as https://ohi.dev/o/w3c/sosa@2017."""
    return (f"https://ohi.dev/o/{_segment(release.namespace)}/{_segment(release.name)}"
            f"@{_segment(release.version)}")
