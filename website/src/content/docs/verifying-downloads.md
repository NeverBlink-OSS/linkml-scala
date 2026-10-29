---
title: Verifying downloads
description: Check the checksums and build provenance of release downloads.
---

LinkML and ohi release downloads include checksums and build provenance:

| Release channel                                                                                                  | Signature type                                                | How to check it           |
|------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|---------------------------|
| [GitHub Releases](https://github.com/NeverBlink-OSS/linkml-scala/releases) (native binaries, shared libraries, `linkml-scala.jar`, `ohi.jar`) | `SHA256SUMS` manifest + Sigstore build provenance attestation | [below](#github-releases) |
| npm (`@neverblink/linkml`, `@neverblink/ohi`)                                   | Registry signature + SLSA provenance (npm trusted publishing) | [below](#npm)             |
| [Maven Central](https://central.sonatype.com/namespace/eu.neverblink.linkml)                                     | PGP signature on every artifact                               | [below](#maven-central)   |

## GitHub Releases

### Checksums

Each release publishes a `SHA256SUMS` file covering its native binaries, compressed binaries, shared-library archives and CLI JARs. Download the manifest from the same release as the artifact and compare:

```shell
VERSION=vX.Y.Z # Replace with the downloaded artifact's release tag.
curl -fsSLO "https://github.com/NeverBlink-OSS/linkml-scala/releases/download/$VERSION/SHA256SUMS"
sha256sum --ignore-missing -c SHA256SUMS
```

`--ignore-missing` lets you verify the files you downloaded without fetching the other assets. On macOS, use `shasum -a 256 --ignore-missing -c SHA256SUMS` instead.

Expect one `OK` line per file you actually have:

```
ohi-linux-x86_64.gz: OK
```

### Build provenance

Use the [GitHub CLI](https://cli.github.com/) to verify which repository commit and release
workflow produced the download:

```shell
gh attestation verify ohi-linux-x86_64 --repo NeverBlink-OSS/linkml-scala
```

`SHA256SUMS` is attested too:

```shell
gh attestation verify SHA256SUMS --repo NeverBlink-OSS/linkml-scala
```

Use the downloaded filename in the same commands to verify any other asset. On Windows, compare `(Get-FileHash .\ohi-windows-x86_64.exe -Algorithm SHA256).Hash` with its entry in `SHA256SUMS`, then run `gh attestation verify .\ohi-windows-x86_64.exe --repo NeverBlink-OSS/linkml-scala`.

`install.sh` checks the checksum when a manifest exists and verifies provenance when `gh` is installed. Set `LINKML_SCALA_REQUIRE_CHECKSUM=1` and `LINKML_SCALA_REQUIRE_ATTESTATION=1` to require both checks and reject an installation when either cannot be completed.

## npm

The release workflow uses [npm trusted publishing](https://docs.npmjs.com/trusted-publishers) for `@neverblink/linkml` and `@neverblink/ohi`, tying their provenance to the public source and workflow. Once a package is in your dependency tree, check the tree:

```shell
npm audit signatures
```

You can also see the provenance, including the source commit and the workflow that published it, on
the [LinkML package page](https://www.npmjs.com/package/@neverblink/linkml) or [ohi package page](https://www.npmjs.com/package/@neverblink/ohi).

## Maven Central

Every artifact under `eu.neverblink.linkml` is PGP-signed. Signatures sit next to the artifact with an `.asc` suffix.

```shell
# Fetch the signing key (replace the version and artifact as needed)
gpg --keyserver keyserver.ubuntu.com --recv-keys 0DB83C00CF3505E9D904FB58A6D1F7F53F035C53

BASE=https://repo1.maven.org/maven2/eu/neverblink/linkml/generator_3/0.12.0
curl -fsSLO "$BASE/generator_3-0.12.0.jar"
curl -fsSLO "$BASE/generator_3-0.12.0.jar.asc"
gpg --verify generator_3-0.12.0.jar.asc generator_3-0.12.0.jar
```

The signing key fingerprint is:

```
0DB8 3C00 CF35 05E9 D904  FB58 A6D1 F7F5 3F03 5C53
```

## Reporting a problem

If a checksum or signature ever fails to verify, please **don't** run the artifact. Open a [security advisory](https://github.com/NeverBlink-OSS/linkml-scala/security/advisories/new) or reach out at [contact@neverblink.eu](mailto:contact@neverblink.eu).
