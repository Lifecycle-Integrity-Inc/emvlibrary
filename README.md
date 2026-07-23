# EMV Library

A standalone Android library for EMV (Europay, MasterCard, Visa) NFC card reading and limited processing - excluding POS transactions. 

## Overview

emvlibrary is an Android library module that provides EMV card functionality. It was extracted from the readNfcCard project to be shared across multiple projects.

## Features

- EMV card reading and processing
- NFC card support
- Kotlin implementation

## Installation

Add to your `build.gradle` dependencies:

```gradle
dependencies {
    implementation 'com.wwopenfare:emvlibrary:1.0.0'
}
```

## Configuration

Add GitHub Packages credentials to your `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_TOKEN
```

The GitHub token must have `read:packages` permission.

## Usage

See the [readNfcCard](https://github.com/Lifecycle-Integrity-Inc/readNfcCard) and [unitiag-validator](https://github.com/Lifecycle-Integrity-Inc/unitiag-validator) projects for usage examples.

## Requirements

- Android SDK 26+
- Kotlin

## License

See LICENSE file for details.

## Contributing

This library is maintained by Lifecycle Integrity Inc.
