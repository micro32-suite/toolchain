package basicplus.frontend;

import edu.bpgenvm.vm.ModuleManager;
import edu.bpgenvm.vm.OutputSink;
import edu.bpgenvm.vm.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 #886 (25-nov) — un módulo que se llama {@code Main} (o {@code main}), con su {@code function Main}, corre UNA vez. La
 * función con el nombre del módulo es su inicializador (corre al cargarlo), y {@code Main} es el programa: con los dos a la
 * vez, las dos VMs la corrían dos veces, sin un aviso, y es un nombre natural para el primer programa de alguien.
 */
class ModuloMainTest {

    /** Compila los `fuentes` (nombre, texto) y corre el módulo `main` en la miVM; devuelve lo que imprime. */
    private static String compilaYCorre(Path tmp, String main, String... fuentes) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        for (int i = 0; i < fuentes.length; i += 2) {
            Files.write(src.resolve(fuentes[i] + ".bp"), fuentes[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        BpBuild b = new BpBuild();
        b.projectDir = tmp.toString();
        b.sourceDir  = src.toString();
        b.outDir     = out.toString();
        b.main       = main;
        String antes = Main.STDLIB_DIR;
        try {
            Main.STDLIB_DIR = StdlibDeTest.paraFormato(tmp).getAbsolutePath();
            assertTrue(Main.buildProject(b, "mivm", false), "debe compilar sin errores");
        } finally {
            Main.STDLIB_DIR = antes;
        }
        VirtualMachine vm = new VirtualMachine();
        ModuleManager mm = new ModuleManager(vm);
        mm.setStdlibDir("../../bpstdlib");
        vm.setModuleManager(mm);
        StringBuilder salida = new StringBuilder();
        vm.setProgramOut(new OutputSink() {
            @Override public void writeText(String s) { salida.append(s); }
            @Override public void writeChar(char c) { salida.append(c); }
            @Override public void newline() { salida.append('\n'); }
            @Override public void writeLine(String s) { salida.append(s).append('\n'); }
            @Override public void flush() { }
        });
        mm.executeRootModule(out.resolve(main + ".mod").toString(), main);
        vm.run();
        return salida.toString();
    }

    @Test
    @Timeout(60)
    @DisplayName("module Main con function Main(): una vez")
    void mainUnaVez(@TempDir Path tmp) throws Exception {
        assertEquals("hola\n", compilaYCorre(tmp, "Main", "Main",
                "module Main\n  function Main()\n    print \"hola\"\n  end Main\nend Main\n"));
    }

    @Test
    @Timeout(60)
    @DisplayName("module main con function main(arg), que lleva el valor por defecto: una vez")
    void mainConArgumento(@TempDir Path tmp) throws Exception {
        assertEquals("con x\n", compilaYCorre(tmp, "main", "main",
                "module main\n  function main(arg: string := \"x\")\n    print \"con \" + arg\n  end main\nend main\n"));
    }

    @Test
    @Timeout(60)
    @DisplayName("el inicializador de los demás, como siempre: al cargarse el módulo, antes del programa")
    void elInicializadorSigue(@TempDir Path tmp) throws Exception {
        assertEquals("Lib se inicia\nApp se inicia\nel programa: 7\n", compilaYCorre(tmp, "App",
                "Lib", "module Lib\n  var n: integer := 0\n  function Lib()\n    print \"Lib se inicia\"\n    n := 7\n  end Lib\n"
                        + "  public function valor(): integer\n    return n\n  end valor\nend Lib\n",
                "App", "module App\n  import Lib\n  function App()\n    print \"App se inicia\"\n  end App\n"
                        + "  function Main()\n    print \"el programa: \" + Lib.valor()\n  end Main\nend App\n"));
    }

    @Test
    @Timeout(60)
    @DisplayName("un módulo Main importado: su Main es una función normal, y no corre al cargarse")
    void importado(@TempDir Path tmp) throws Exception {
        assertEquals("el programa\n", compilaYCorre(tmp, "Usa",
                "Main", "module Main\n  public function doble(n: integer): integer\n    return n * 2\n  end doble\n"
                        + "  function Main()\n    print \"esto no\"\n  end Main\nend Main\n",
                "Usa", "module Usa\n  import Main\n  function main()\n    print \"el programa\"\n  end main\nend Usa\n"));
    }
}
