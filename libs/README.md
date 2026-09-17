# libs/

`xposed-api-82.jar` holds the compile-time stubs for the classic Xposed API
(`de.robv.android.xposed.*`), which this module is written against. The classes
are provided at runtime by LSPosed; the jar is only on javac's classpath.

It is the API 82 jar that legacy Xposed/LSPosed modules are built with. To build
against a different copy, point `XPOSED_JAR` at it:

```bash
XPOSED_JAR=/path/to/api.jar ./build.sh
```
