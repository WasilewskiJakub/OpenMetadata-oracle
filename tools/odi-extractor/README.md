# ODI SDK extractor

This isolated Maven project is the development boundary between the Oracle ODI SDK and the
OpenMetadata ingestion connector. It is intentionally not included in the root Maven reactor.

The Oracle binaries are user-provided, remain outside Git, and are never packaged into the extractor:

```text
~/.local/share/oracle/odi/14.1.2/lib/
├── oracle.odi.common.clientLib.jar
├── oracle.odi.tp.clientLib.jar
├── oracle.odi.sdk.clientLib.jar
└── ojdbc11.jar
```

Build and run the classpath probe from an environment with Java 21 and the private libraries:

```bash
mvn -f tools/odi-extractor/pom.xml clean compile

ODI_LIB="$HOME/.local/share/oracle/odi/14.1.2/lib"
java -cp "tools/odi-extractor/target/classes:$ODI_LIB/*" \
  org.openmetadata.tools.odi.OdiSdkProbe
```

The probe performs no repository connection and reads no secrets. Repository access and NDJSON
extraction will be added only after the SDK classpath and public API surface are verified.
