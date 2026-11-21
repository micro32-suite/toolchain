# Micro32 Suite · toolchain

The Java side of [BasicPlus](https://github.com/micro32-suite/BasicPlus): the
compiler for BasicPlus and JC, the IDE, the reference Java VM and the pack
format.

> 🇪🇸 [Versión en español](README.es.md)

This repository is part of **[Micro32 Suite](https://github.com/micro32-suite)**,
together with [BasicPlus](https://github.com/micro32-suite/BasicPlus) — the
standard library, the examples, the documentation and the tests — and
[runtime](https://github.com/micro32-suite/runtime) — the C VM and the boards'
firmware. BasicPlus brings it in as a submodule, in `toolchain/`, and that is
where it is built: its tests use BasicPlus's standard library, samples and C VM.

**To use BasicPlus you don't need to build anything:** the Windows download,
with the IDE, the compiler and the firmware, is in the [latest
release](https://github.com/micro32-suite/BasicPlus/releases/latest), and the
documentation, on the [web](https://micro32-suite.github.io/BasicPlus/en/).

## What's inside

| Folder | What it is |
|---|---|
| `pack/` | The pack format: the library the Java VM and the compiler share |
| `miVM/` | The Java VM — the reference implementation —, its debugger and its TCP daemon |
| `lexer-java/` | The compiler: `.bp` and `.jc` → `.mod` (the bytecode, the module's interface and, inside, the native code of its `native` functions) + `.dbg` |
| `BpIde/` | The IDE (Swing): a single jar that carries the compiler and the Java VM |
| `tools/packtool/` | Prunes and seals a pack for a board |

## Build

Requirements: JDK 8 or later and Maven. From the root of BasicPlus, cloned with
its submodules:

```sh
git clone --recursive https://github.com/micro32-suite/BasicPlus.git
cd BasicPlus
mvn -f toolchain/pack/pom.xml install          # first: miVM and the compiler depend on it
mvn -f toolchain/miVM/pom.xml install
mvn -f toolchain/lexer-java/pom.xml install
mvn -f toolchain/BpIde/pom.xml package         # the IDE, in toolchain/BpIde/target/
```

The compiler and the Java VM, from the command line:

```sh
java -jar toolchain/lexer-java/target/basicplus-frontend.jar samples/blink.bp \
     --compile samples --backend=mivm
java -jar toolchain/miVM/target/bpgenvm-1.0.jar samples/Blink.mod
```

## The rule it lives by

The same `.mod` runs on this Java VM and on the C VM of
[runtime](https://github.com/micro32-suite/runtime), and **a program's output
must be byte-identical on both**. Every feature is done here first, then in C —
with its output compared byte for byte against Java's — and only then does it
go to the boards. More in [BasicPlus from the
inside](https://micro32-suite.github.io/BasicPlus/bp-desde-dentro.html)
*(Spanish)*.

## License

[MIT](LICENSE).
