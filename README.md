# OBP Commons

Common utilities and models for Open Bank Project.

## Installation

### Maven

Add JitPack repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Add the dependency:

```xml
<dependency>
    <groupId>com.github.YourGitHubUsername</groupId>
    <artifactId>obp-commons</artifactId>
    <version>v1.0.0</version>
</dependency>
```

### Gradle

Add JitPack repository to your `build.gradle`:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.YourGitHubUsername:obp-commons:v1.0.0'
}
```

## Usage

### Import the models

```scala
import com.openbankproject.commons.model._
import com.openbankproject.commons.util._
```

### Example

```scala
import com.openbankproject.commons.model.{BankId, AccountId, User}
import com.openbankproject.commons.util.ApiVersion

// Use the models
val bankId = BankId("bank123")
val accountId = AccountId("account456")
```

## Version History

- `v1.0.0` - Initial release with common models and utilities

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the AGPL v3 License - see the [LICENSE](LICENSE) file for details.

## JitPack.io

This project is published via [JitPack.io](https://jitpack.io). 

To use a specific version:
- Use the git tag: `v1.0.0`
- Use the commit hash: `a1b2c3d`
- Use the branch name: `master`

Visit [https://jitpack.io/#YourGitHubUsername/obp-commons](https://jitpack.io/#YourGitHubUsername/obp-commons) for more information.
