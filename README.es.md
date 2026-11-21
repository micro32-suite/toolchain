# Micro32 Suite · toolchain

Lo de Java de [BasicPlus](https://github.com/micro32-suite/BasicPlus): el
compilador de BasicPlus y JC, el IDE, la VM Java de referencia y el formato de
los packs.

> 🇬🇧 [English version](README.md)

Este repositorio es parte de **[Micro32 Suite](https://github.com/micro32-suite)**,
junto a [BasicPlus](https://github.com/micro32-suite/BasicPlus) —la biblioteca
estándar, los ejemplos, la documentación y las pruebas— y
[runtime](https://github.com/micro32-suite/runtime) —la VM C y el firmware de las
placas—. BasicPlus lo trae como submódulo, en `toolchain/`, y es allí donde se
compila: sus pruebas usan la biblioteca estándar, los ejemplos y la VM C de
BasicPlus.

**Para usar BasicPlus no hace falta compilar nada:** la descarga para Windows,
con el IDE, el compilador y el firmware, está en la [última
versión](https://github.com/micro32-suite/BasicPlus/releases/latest), y la
documentación, en la [web](https://micro32-suite.github.io/BasicPlus/).

## Qué hay dentro

| Carpeta | Qué es |
|---|---|
| `pack/` | El formato de los packs: la biblioteca que comparten la VM Java y el compilador |
| `miVM/` | La VM Java —la implementación de referencia—, su depurador y su daemon TCP |
| `lexer-java/` | El compilador: `.bp` y `.jc` → `.mod` (el bytecode, la interfaz del módulo y, dentro, el código nativo de sus funciones `native`) + `.dbg` |
| `BpIde/` | El IDE (Swing): un único jar, que lleva dentro el compilador y la VM Java |
| `tools/packtool/` | Poda y sella un pack para una placa |

## Compilar

Requisitos: JDK 8 o posterior y Maven. Desde la raíz de BasicPlus, clonado con
sus submódulos:

```sh
git clone --recursive https://github.com/micro32-suite/BasicPlus.git
cd BasicPlus
mvn -f toolchain/pack/pom.xml install          # el primero: miVM y el compilador dependen de él
mvn -f toolchain/miVM/pom.xml install
mvn -f toolchain/lexer-java/pom.xml install
mvn -f toolchain/BpIde/pom.xml package         # el IDE, en toolchain/BpIde/target/
```

El compilador y la VM Java, desde la línea de comandos:

```sh
java -jar toolchain/lexer-java/target/basicplus-frontend.jar samples/blink.bp \
     --compile samples --backend=mivm
java -jar toolchain/miVM/target/bpgenvm-1.0.jar samples/Blink.mod
```

## La regla con la que vive

El mismo `.mod` corre en esta VM Java y en la VM C de
[runtime](https://github.com/micro32-suite/runtime), y **la salida de un programa
debe ser byte a byte la misma en las dos**. Cada novedad se hace primero aquí,
después en C —con su salida comparada byte a byte con la de Java—, y sólo
entonces pasa a las placas. Más en [BasicPlus desde
dentro](https://micro32-suite.github.io/BasicPlus/bp-desde-dentro.html).

## Licencia

[MIT](LICENSE).
