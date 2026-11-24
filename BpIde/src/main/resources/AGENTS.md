# BasicPlus — a guide for AI assistants

BasicPlus is a statically typed, object-oriented language **of its own**, for 32-bit microcontrollers. It is **not**
DEC's BASIC-PLUS, Visual Basic or classic BASIC: do not assume their syntax. Nor does it have Java's library. One
bytecode (`.mod`) runs the same on the PC and on the boards. Read this page before writing code.

The compiler's and the VMs' messages are in Spanish.

## Where to look, in this order

1. The cheatsheet, the language on one page: `@INSTALACION@docs/cheatsheet.html.md`. Read it all first.
2. `bp api <Module>`: the API of a module, its public declarations written in BasicPlus without the bodies
   (`bp api Collections`); `bp api` alone lists the modules you can import. It finds the module as `import` would,
   packs included (`SQLite` and `Orm` come without source). Look there before inventing a method.
3. `@INSTALACION@bpstdlib/*.bp`: the sources of the standard library, with their comments (in Spanish): what a
   function does, and how.
4. `@INSTALACION@samples/*.bp`: about 380 programs that compile. Look for a real use before guessing.
5. The manual, `@INSTALACION@docs/en/manual.html.md`; the reference, `docs/en/referencia.html.md`; the GUI,
   `docs/en/gui.html.md`; JC (below), `docs/en/jc.html.md`. In Markdown, made from each `.html` beside it: there the
   code is plain text, and `grep` finds it. Spanish versions, HTML only, in `docs/`.
6. `@INSTALACION@docs/PENDIENTES.md`: what the language does not do yet (in Spanish).

## Compile and run on the PC (no board, no IDE)

`bp` is at the root of the installation: `@INSTALACION@bp.bat` on Windows, `@INSTALACION@bp` for bash.

```
bp build X.bp        # compiles: out/X.mod next to the source. A project: bp build App.bpbuild
bp run X.bp          # compiles if needed and runs it on the Java VM
bp run --c X.bp      # the same on the C VM (the one in the boards)
bp api Collections   # the API of a module (bp api alone: the modules you can import)
bp test              # the tests in tests/, on both VMs (see Tests, below)
bp help              # the rest: bp png (a screen capture to PNG), bp frontend and bp vm (all the options)
```

- `bp run` exits with the program's code: 0 if it ends well, 1 with an uncaught exception, 2 if it does not compile
  (then the errors go to stderr and nothing runs).
- Errors come with their file, line and column: `App.bp:7:15: error semántico: …` (also those of an imported module,
  with its own file). Read the whole message: it usually says how to fix it. `bp build` says only that, or
  `compilado: out/X.mod`.
- On stdout, only what the program prints, the same in both VMs: `bp run X.bp > out.txt` keeps just that. The VMs'
  own lines (`[miVM] …`, `[bpvm] …`) go to stderr.
- An uncaught exception says where, on stderr: `=== traza: X+122 X+39 ===`, and under it the file, the line and the
  function (`  en X.bp:8 (Main)`, `  llamado desde …`).
- What the program prints on the PC is what it will print on a board: make it print what it does, and check that.
- Options for the VM go before the program: `bp run --no-screen X.bp` (a GUI without a window; also with `--c`).

## Tests

`bp test` runs the programs in `tests/` (or the ones you name) on both PC VMs, with no screen and a time limit. A test
passes if it compiles, ends with 0, prints the same on both and, if it has its `X.out` beside it, prints exactly that
(`bp test --guarda` writes them from what they print now: read them before keeping them). In a project folder (its
`.bpbuild`), it compiles the project first, and the tests import its modules. One line per test, a failing one with
its reason; it exits with 0 if all pass. The checks, with the `Assert` module (`bp api Assert`):

```
import Assert
Assert.equals(4, suma(2, 2))              // integer, long, double, string or boolean: expected, actual
Assert.equals("abc", s, "the name")       // with what is checked, in front of the failure
Assert.isTrue(l.length() > 0, "not empty")
```

A failing check throws `Assert.Failure`: the test ends with 1, and its trace says the line. A project with two tests,
one of them a GUI: `samples/pruebas`.

## On a board or the simulated micro, without the IDE

The same `bp`, with the IDE's own upload: only what the board does not already have, as the IDE's Run on Device.

```
bp run --sim X.bp              # on the simulated micro (it starts it if it is not running, and stops it at the end)
bp run --placa=COM22 X.bp      # on a board, by its serial port (ask the user which); one on the network: --placa=<its IP>
bp placa COM22 ls              # its files, with their size (also: bp placa sim ls)
bp placa COM22 get X.shot      # download one, with its path as ls prints it
bp placa COM22 info            # which board it is: name, architecture, memory, flash, pins…
bp placa COM22 stop            # stop the program running on it
```

- Output and exit code as on the PC: on stdout only what the program prints; on stderr what was uploaded, and the
  trace of an uncaught exception with its lines. The argument, after the program, as on the PC.
- One client at a time: if the IDE is connected to that board or to the simulated micro, `bp` cannot use it, and says
  so. Then use the IDE's tools (below), or ask the user to disconnect it.
- The board's paths start with `/` (`/app/X.mod`). From Git Bash write them with two (`//app/X.mod`): bash turns
  `/app` into a Windows path before `bp` sees it (if that happens, `bp` says so).

## With the IDE

The IDE has a server for AI assistants (MCP, at `http://127.0.0.1:7340/mcp`; Claude Code connects to it, once, with
`claude mcp add --transport http basicplus http://127.0.0.1:7340/mcp`). Its tools do the same as the IDE's menus: open
a source, compile, run on the PC, on the simulated micro or on a board (connect first), with the output and the exit
code. Files on the PC: only inside the folder the user allowed.

- **The server is off until the user allows it.** If it does not answer: with the IDE open, the user turns it on (menu
  IA); with the IDE closed, open it yourself, in the background (it is a window):
  `@INSTALACION@bpide.bat --ia=<the absolute path of your working folder>` (from bash, if both paths have spaces, cmd
  cannot start it: use PowerShell). The IDE first asks the user, and writes how it went in one line of its output:
  `[ia] servidor para la IA en …` if allowed, another `[ia] …` line if not. Then connect (or reconnect).
- **A board keeps its standard library** (its `Stdlib.pack`), and the IDE does not upload a library the board already
  has in the same version. If a run on a board ends with `lib 'X' presente pero no exporta …`, the board's library is
  older than this installation's: tell the user to burn this installation's packs on the board (IDE, Packs panel;
  `@INSTALACION@docs/INSTALAR_FIRMWARE.md`).
- **The simulated micro keeps its state** between runs, like a board (its file system and its packs). If it carries
  things from before (old files in `/app`, a library that does not export what the program uses),
  `micro_simulado` with `de_cero: true` empties it (all its files and its packs zone; its configuration, the
  partitions, stays) and starts it with this installation's library.

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
`@INSTALACION@docs/en/jc.html.md` §3 (one program in BP and in JC) and §17 (what JC lacks and what changes). The library is
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
- The screen size depends on where it runs: 480x320 on the PC (unless `--screen=WxH`), the one in its settings on the
  simulated micro, the panel's on a board. Lay out with `align` relative to the screen, not with fixed coordinates.
- A button whose size you set by hand does not center its text: `btn.childAt(0).align(Gui.Align.CENTER, 0, 0)`.
- To test it with nobody in front of it (a test in `tests/`, run by `bp test`: see Tests, above):
  - `print Gui.dumpTree()` dumps the widget tree as text: compare that (its `.out`), and check with `Assert`.
  - `Gui.click(widget)` simulates a click; `Gui.change(widget)`, a change of value (its `onChange`; the value, the one
    you set). Without a screen, the GUI loop ends as soon as it has nothing to do: do it before `Gui.run()`, which
    handles it and returns; after that, it is an error that says so.
  - Make the program end by itself: `Gui.start()` … `Gui.stop()` and `Gui.join()`. With a window, `Gui.run()` only
    returns when someone calls `Gui.stop()` or closes it.
  - `Gui.shot("X.shot")` writes a capture (call `sleep(300)` before, so the screen gets painted), and
    `bp png X.shot` turns it into `X.png`. The capture that counts is LVGL's: `bp run --c --no-screen X.bp`. The
    Java VM paints with Swing, an approximation. On a board, the capture stays in its file system (a relative path,
    at its root): download it (`bp placa COM22 get X.shot`; with the IDE, `placa_bajar`) and turn it into a PNG the
    same way.
- Models to copy: `samples/GuiShot.bp`, `samples/GuiClickDemo.bp`, `samples/ChartDemo.bp`, and a GUI that tests
  itself, `samples/pruebas/tests/PruebaGui.bp`.

## Hardware

Each peripheral is a class (`Gpio.Pin`, `I2c.HardI2C`, `Spi.Bus`, `Uart.Port`…). An I2C bus is an `I2c.I2C` (what a
device library asks for): the micro's (`I2c.HardI2C`) or, when there are none left or its pins do not match, a software
one on any two pins (`SoftI2c.SoftI2C(sda, scl)`); only the constructor changes. On the PC, the hardware calls are written
to the console. **Do not invent pin numbers**: ask which board it is, or look at
`@INSTALACION@docs/INSTALAR_FIRMWARE.md` and the reference §14 (device libraries).

## How to work

1. Write a little, compile, fix. The compiler is fast and its messages help.
2. Make the program print what it does: its output on the PC is the same it will have on the board, and it is what
   can be checked.
3. Take nothing for granted without having run it.
