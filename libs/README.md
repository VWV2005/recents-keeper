# libs/

## xposed-api-82.jar

Compile-time stubs for the classic Xposed API (`de.robv.android.xposed.*`), which
this module is written against. The classes are provided at runtime by LSPosed;
the jar only sits on `javac`'s classpath, and nothing from it is compiled into
the module.

**License: Apache License 2.0** — Copyright 2013 rovo89, Tungstwenty.

The upstream project ships the grant in `NOTICE.txt` rather than a file named
`LICENSE`, which is why GitHub's license detector reports "no license" for it.
The jar is redistributed here **unmodified**, together with:

- `LICENSE-Apache-2.0.txt` — the full license text
- `NOTICE.txt` — the upstream attribution notice

Upstream: <https://github.com/rovo89/XposedBridge>

Apache-2.0 is compatible with this repository's GPL-3.0: Apache-2.0 code may be
included in a GPL-3.0 work, provided the license text and notices above are
kept. (Note the direction: the combined work is GPL-3.0, and you cannot
relicense the Apache-2.0 parts as Apache-2.0 alone.)

To build against a different copy of the API, point `XPOSED_JAR` at it:

```bash
XPOSED_JAR=/path/to/api.jar ./build.sh
```
