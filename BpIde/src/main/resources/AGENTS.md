# BasicPlus — a guide for AI assistants

BasicPlus is a statically typed, object-oriented language **of its own**, for 32-bit microcontrollers. It is **not**
DEC's BASIC-PLUS, Visual Basic or classic BASIC: do not assume their syntax. Nor does it have Java's library. One
bytecode (`.mod`) runs the same on the PC and on the boards. Read this page before writing code.

The compiler's and the VMs' messages are in Spanish.

## Where to look, in this order

1. The cheatsheet, the language on one page: https://micro32-suite.github.io/BasicPlus/cheatsheet.html (it is on the
   web only). Read it all first. Without the web, the manual below.
2. `@INSTALACION@bpstdlib/*.bp`: the sources of the standard library, the real API. Search there before inventing a
   method (`grep "public function" @INSTALACION@bpstdlib/Collections.bp`).
3. `@INSTALACION@samples/*.bp`: about 380 programs that compile. Look for a real use before guessing.
4. `@INSTALACION@docs/en/manual.html`, `reference`: `docs/en/referencia.html`, the GUI: `docs/en/gui.html`. JC (below):
   `docs/en/jc.html`. Spanish versions in `docs/`.
5. `@INSTALACION@docs/PENDIENTES.md`: what the language does not do yet (in Spanish).

## Compile and run on the PC (no board, no IDE)

`bp` is at the root of the installation: `@INSTALACION@bp.bat` on Windows, `@INSTALACION@bp` for bash.

```
bp build X.bp        # compiles: out/X.mod next to the source. A project: bp build App.bpbuild
bp run X.bp          # compiles if needed and runs it on the Java VM
bp run --c X.bp      # the same on the C VM (the one in the boards)
bp help              # the rest: bp png (a screen capture to PNG), bp frontend and bp vm (all the options)
```

- `bp run` exits with the program's code: 0 if it ends well, 1 with an uncaught exception, 2 if it does not compile
  (then the errors go to stderr and nothing runs).
- Errors come as `[line:column] error …`. Read the whole message: it usually says how to fix it.
- Each VM adds lines of its own to the output (`config: …`, `[miVM] …`, `[bpvm …]`, `=== …`): they are not the
  program's.
- What the program prints on the PC is what it will print on a board: make it print what it does, and check that.
- Options for the VM go before the program: `bp run --c --no-screen X.bp` (a GUI without a window).

## With the IDE

If the IDE has its AI server on (menu IA), its tools do the same from the IDE: open a source, compile, run on the PC,
on the simulated micro or on a board (connect first), with the output and the exit code.

## Traps for those coming from Java or C#

| You would write | In BasicPlus |
|---|---|
| `list.size()` | `list.length()` |
| `Map<String, Integer>` | No generic map: `import Collections`, then `var m: Collections.Map := Collections.stringMap()` |
| `m.put("a", 5)` | A number in an `Object` goes in its box: `m.put("a", Integer(5))`; read it with `m.getInteger("a")`. (`List.add(5)` boxes it for you.) |
| `s.toUpperCase()`, `s.length()` | Strings have no methods: `upper(s)`, `strlen(s)`, `substring(s, from, to)` (`to` not included)… In `Core`; more in `Str`. |
| `static` | Qualify with the class: `public property Counter.total: integer`, `public function Counter.reset()` … `end Counter.reset`, called as `Counter.reset()` |
| public fields | `public property x: integer` |
| `{ }` and `;` | Every block closes with its name or keyword: `end main`, `end Counter`, `endif`, `endwh`, `next i`, `endtry`. No semicolons. |
| `=` to assign | `:=` assigns; `==` compares |

What does work as you would expect: `class B extends A`, `super(x)` in the constructor and `super.method()`;
`for i := 0 to n do … next i` (`n` included); `for x in list do … next x`; `"text " + number`; `+=`;
`try … catch e: RuntimeError … endtry` (the message, `e.msg`).

## JC — the same language with braces (`.jc` files)

JC **looks** like Java or C#, but it is BasicPlus with another syntax: do not write Java from memory. Read
`@INSTALACION@docs/en/jc.html` §3 (one program in BP and in JC) and §17 (what JC lacks and what changes). The library is
the same, with the same names and the same traps as above.

| You would write in Java | In JC |
|---|---|
| `public Item(…) { }`, `public int f() { }` | Every method and constructor takes `function`: `public function Item(…)`, `public function int f()` |
| `class B extends A`, `implements I` | `class B : A, I` |
| `super(x);` in the body, `super.f()` | `public function B(…) : base(x) { }`, `base.f()` |
| `@Override` | Remove it: it is not needed (every method is virtual), and as is it does not compile |
| `for (int i = 0; i < n; i++)` | `for` is a counter with the limit **included**: `for (int i = 0; i <= n - 1; i++)` |
| `for (Item x : list)` | `foreach (var x in list)` |
| `System.out.println(x)` | `println(x)`; with several pieces, `println("a ", n)`; `print` does not end the line |

- The JC compiler stops at the first syntax error: fix them one at a time.
- The compiler translates between the two languages, and both compile to the same `.mod`:
  ```
  bp frontend X.bp --escribir-jc > X.jc     # BP -> JC
  bp frontend X.jc --escribir-bp > X.bp     # JC -> BP
  bp frontend X.bp --comprobar-jc           # the round trip
  ```
  Comments do not travel. Almost all the samples are in BP: if you are asked for JC and are unsure, find the sample
  in BP and translate it.

## GUI (module `Gui`)

- Events are subscribed with `::`, and the handler is declared with `function event`:
  ```
  btn.onClick := this::btn_onClick
  function event btn_onClick(who: Gui.Component) … end btn_onClick
  ```
- The default font has no accented letters or `ñ` (they show as a box); `°` works.
- A button whose size you set by hand does not center its text: `btn.childAt(0).align(Gui.Align.CENTER, 0, 0)`.
- To test it with nobody in front of it:
  - `print Gui.__guiDumpTree()` dumps the widget tree as text: compare that.
  - `Gui.__guiClick(widget)` simulates a click. Without a screen, the GUI loop ends as soon as it has nothing to do:
    do it before `Gui.run()`, which handles it and returns; after that, it is an error that says so.
  - Make the program end by itself: `Gui.start()` … `Gui.stop()` and `Gui.join()`. With a window, `Gui.run()` only
    returns when someone calls `Gui.stop()` or closes it.
  - `Gui.shot("X.shot")` writes a capture (call `sleep(300)` before, so the screen gets painted), and
    `bp png X.shot` turns it into `X.png`. The capture that counts is LVGL's: `bp run --c --no-screen X.bp`. The
    Java VM paints with Swing, an approximation.
- Models to copy: `samples/GuiShot.bp`, `samples/GuiClickDemo.bp`, `samples/ChartDemo.bp`.

## Hardware

Each peripheral is a class (`Gpio.Pin`, `I2c.Bus`, `Spi.Bus`, `Uart.Port`…). On the PC, the hardware calls are written
to the console. **Do not invent pin numbers**: ask which board it is, or look at
`@INSTALACION@docs/INSTALAR_FIRMWARE.md` and the reference §14 (device libraries).

## How to work

1. Write a little, compile, fix. The compiler is fast and its messages help.
2. Make the program print what it does: its output on the PC is the same it will have on the board, and it is what
   can be checked.
3. Take nothing for granted without having run it.
