# Dependencies and asset separation

The project code is MIT licensed. Runtime dependencies keep their own licenses:

| Dependency | Version | License |
| --- | --- | --- |
| Jackson core/databind / annotations | 2.22.3 / 2.22 | Apache-2.0 |
| Picocli | 4.7.7 | Apache-2.0 |
| ASM | 9.11 | BSD-3-Clause |
| Gson | 2.13.2 | Apache-2.0 |
| JUnit (test only) | 5.13.4 | EPL-2.0 |
| Gradle wrapper | 9.8.1 | Apache-2.0 |

配布するcoreのjavadocにはJDK 21の標準UI画像・スクリプト等が含まれます。javadoc JAR内の`legal/`にJDKのLICENSE・ASSEMBLY_EXCEPTION・追加ライセンス、jQuery/jQuery UIの通知を保持しています。これらの文書用画像はMinecraft資産ではありません。

See upstream notices bundled in the corresponding dependency artifacts and these projects: [Jackson](https://github.com/FasterXML/jackson-databind), [Picocli](https://github.com/remkop/picocli), [ASM](https://gitlab.ow2.org/asm/asm), [Gson](https://github.com/google/gson), [JUnit](https://github.com/junit-team/junit5), [Gradle](https://github.com/gradle/gradle).

Legacy name resolution downloads [Ornithe Feather](https://github.com/OrnitheMC/feather) and [Calamus](https://github.com/OrnitheMC/calamus) at runtime with pinned checksums. Their mappings are not packaged in the CLI or library. The Docker image includes Eclipse Temurin/OpenJDK, Ubuntu and Mesa packages under their respective licenses; the Docker image is built locally and is not currently distributed.

Minecraft client JARs, libraries and assets downloaded from Mojang are separate from this project's code. No Minecraft client, original asset, generated icon set or authentication credential is included in source or distribution archives. This tool is unofficial. Refer to the [Minecraft EULA](https://www.minecraft.net/en-us/eula) for Minecraft's terms. Publishing the project code does not grant rights to redistribute Minecraft assets.
