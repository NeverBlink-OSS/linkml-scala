/** Fields that identify one release, as found on a `Publication` or a `SearchHit`. */
export type Release = { namespace: string; name: string; version: string };

/** Page of one release on ohi.dev, such as https://ohi.dev/o/w3c/sosa@2017. */
export function ohiReleasePage(release: Release): string {
  return `https://ohi.dev/o/${encodeURIComponent(release.namespace)}/${encodeURIComponent(release.name)}@${encodeURIComponent(release.version)}`;
}
