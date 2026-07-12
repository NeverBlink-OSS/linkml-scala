# Contributing to LinkML-Scala

Thanks for your interest in LinkML-Scala! We're happy to see the project used and  improved by others.

A note on expectations: we don't yet have a Contributor License Agreement (CLA) process in place, so we can't merge substantial external contributions just yet. You are very welcome to open issues and pull requests – bug reports and feature requests are always appreciated and help us a lot. If you're serious about contributing something larger, please [reach out to us](mailto:contact@neverblink.eu) first so we can sort out the details together.

## Building the project

LinkML-Scala uses [mill](https://mill-build.org/) as the build tool. A bootstrap script is included with this repo (`./mill`), so no additional setup is required for CLI usage. In IntelliJ, [you have to import the project as BSP](https://mill-build.org/mill/cli/installation-ide.html#_intellij) and you are good to go.

Common tasks with mill:

- Run CLI directly: `./mill cli.jvm.run --help`
- Start the browser UI: `./mill ui`
- Scan the mill project structure: `./mill resolve _`
- Compile all modules: `./mill __.compile`
- Run all tests: `./mill __.test` (prefer specific test running for faster feedback, like `./mill generator.jvm.test`)
- Lint the project `./mill lint` (scalafix + scalafmt)
- Re-generate the metamodel classes `./mill metamodel.regenerate`
- Fetch the metamodel definitions from [linkml/linkml-model](https://github.com/linkml/linkml-model) `./mill metamodel.definitions`
- Publish artifacts locally: `./mill __.publishLocal`
- Assembly runnable .jar: `./mill cli.jvm.assembly`
- Build native binary: `./mill cli.jvm.nativeImage` (requires Coursier (cs) to be installed)
- Assemble the npm package: `./mill generator.js.npmPackage` (TS declarations are generated from the Scala facade)
- Verify the npm package (README examples run + types compile): `./mill generator.js.verifyPackage` (requires Node.js and npm)

## Releasing with GitHub UI

1. Go to Releases -> Draft a new release
2. Press "Select tag" list
3. Enter the tag name in form `v0.2.3`
4. Press "Generate Release Notes" button
5. Select "Pre-release" or "Release" list item
6. Press "Publish Release" button
