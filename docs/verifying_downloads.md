# Verifying downloads

Every LinkML-Scala artifact is published with a cryptographic signature, so you can be sure you downloaded a genuine build:

| Release channel                                                                                                  | Signature type                                                | How to check it           |
|------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|---------------------------|
| [GitHub Releases](https://github.com/NeverBlink-OSS/linkml-scala/releases) (native binaries, `linkml-scala.jar`) | `SHA256SUMS` manifest + Sigstore build provenance attestation | [below](#github-releases) |
| [npm](https://www.npmjs.com/package/@neverblink/linkml) (`@neverblink/linkml`)                                   | Registry signature + SLSA provenance (npm trusted publishing) | [below](#npm)             |
| [Maven Central](https://central.sonatype.com/namespace/eu.neverblink.linkml)                                     | PGP signature on every artifact                               | [below](#maven-central)   |

## GitHub Releases

### Checksums

Each release publishes a `SHA256SUMS` file covering every asset – the five native binaries, their `.gz` counterparts, and `linkml-scala.jar`. Download it next to whatever you grabbed and compare:

```shell
curl -fsSLO https://github.com/NeverBlink-OSS/linkml-scala/releases/latest/download/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS
```

`--ignore-missing` is what lets you verify a single asset without downloading all eleven. On macOS, use `shasum -a 256 --ignore-missing -c SHA256SUMS` instead.

Expect one `OK` line per file you actually have:

```
linkml-scala-linux-x86_64.gz: OK
```

### Build provenance

Checksums only prove that your copy matches the manifest. The provenance attestation also proves that these exact bytes were produced by our release workflow, from a specific commit in this repository. Nobody can forge one without control of the repository's Actions runs.

Verify it with the [GitHub CLI](https://cli.github.com/):

```shell
gh attestation verify linkml-scala-linux-x86_64 --repo NeverBlink-OSS/linkml-scala
```

`SHA256SUMS` is attested too:

```shell
gh attestation verify SHA256SUMS --repo NeverBlink-OSS/linkml-scala
```

Note that `install.sh` performs both of these checks for you – the checksum always, and the attestation when `gh` happens to be installed.

## npm

`@neverblink/linkml` is published with [npm trusted publishing](https://docs.npmjs.com/trusted-publishers), which means the registry holds both a signature and a SLSA provenance attestation tying the tarball to the release workflow. Once it is in your dependency tree, check the whole tree at once:

```shell
npm audit signatures
```

You can also see the provenance, including the source commit and the workflow that published it, on
the [package page](https://www.npmjs.com/package/@neverblink/linkml).

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
