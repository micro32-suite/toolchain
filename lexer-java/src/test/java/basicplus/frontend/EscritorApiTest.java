package basicplus.frontend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V9 H1.7 (#858) — `bp api`: la interfaz de un módulo escrita en BP, el módulo sin los cuerpos ({@link EscritorApi}), y
 * el módulo encontrado como lo encuentra un `import` ({@link Main#interfazDeUnImport}).
 */
class EscritorApiTest {

    private static Path stdlib() {
        File std = new File("../../bpstdlib");
        Assumptions.assumeTrue(new File(std, "Collections.mod").isFile(), "hace falta ../../bpstdlib (compila la stdlib)");
        return std.toPath().toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("Collections: lo público, como se escribe; lo protegido, sólo con --todo")
    void collections() throws Exception {
        Path mod = stdlib().resolve("Collections.mod");
        ModuleInterface mi = Main.interfazDeUnMod(mod);
        assertNotNull(mi);
        String api = EscritorApi.escribe(mi, "Collections.mod", false);
        assertTrue(api.startsWith("// Collections.mod\nmodule Collections\n"), api);
        assertTrue(api.endsWith("end Collections\n"), api);
        for (String linea : new String[]{
                "\n  public function numericMap(): Map\n",
                "\n  public function stringMap(): Map\n",
                "\n  public class Map\n",
                "\n    public function Map()\n",
                "\n    public function put(k: string, v: Object)\n",
                "\n    public function getInteger(k: string): integer\n",
                "\n  end Map\n",
                "\n  public class SyncList extends List\n",
                "\n    public function compare(a: Object, b: Object): integer\n" })
            assertTrue(api.contains(linea), "falta «" + linea.trim() + "» en:\n" + api);
        for (String no : new String[]{ "bsearch", "keyList", "Core.", "intrinsic", "CALL_BUILTIN" })
            assertFalse(api.contains(no), "«" + no + "» no va sin --todo:\n" + api);

        String todo = EscritorApi.escribe(mi, null, true);
        assertTrue(todo.startsWith("module Collections\n"), todo);
        for (String linea : new String[]{ "\n    function bsearch(key: Object): integer\n", "\n    var keyList: List\n" })
            assertTrue(todo.contains(linea), "con --todo, falta «" + linea.trim() + "» en:\n" + todo);
    }

    @Test
    @DisplayName("Core: las funciones sueltas por nombre, sin lo de dentro de una intrínseca; byte y word, no uint8;"
            + " lo del sistema (__nombre), sólo con --todo")
    void core() throws Exception {
        ModuleInterface mi = Main.interfazDeUnMod(stdlib().resolve("Core.mod"));
        String api = EscritorApi.escribe(mi, null, false);
        for (String linea : new String[]{ "\n  public function strlen(s: string): integer\n",
                                          "\n  public function substring(s: string, start: integer, hasta: integer): string\n",
                                          "\n  public function sleep(ms: integer)\n" })
            assertTrue(api.contains(linea), "falta «" + linea.trim() + "» en:\n" + api);
        assertFalse(api.contains("uint8"), api);
        assertFalse(api.contains("inline"), api);
        assertFalse(api.contains(" __"), "lo del sistema, sólo con --todo:\n" + api);
        assertTrue(api.indexOf("public function abs(") < api.indexOf("public function upper("), "por nombre:\n" + api);
        assertTrue(EscritorApi.escribe(mi, null, true).contains("\n  public function __newRefArray(cap: integer): integer[]\n"));
    }

    /** Lo que la stdlib no tiene: una interfaz de clase, una clase `final`, un enum, una constante de texto, una property
     *  de módulo, estáticos, un evento, un parámetro `var`, valores por defecto de cada tipo, una tupla, `@element`, un
     *  campo `owner` y lo privado (que no sale nunca). */
    private static final String JUGUETE =
        "module Juguete\n"
      + "  public const LIMITE: integer := 10\n"
      + "  public const SALUDO: string := \"di \\\"hola\\\"\"\n"
      + "  public property contador: integer := 0\n"
      + "  public enum Color\n"
      + "    ROJO\n"
      + "    VERDE := 5\n"
      + "    AZUL\n"
      + "  end Color\n"
      + "  public interface Forma\n"
      + "    function area(): double\n"
      + "    property nombre: string\n"
      + "  end Forma\n"
      + "  public class Cuadro implements Forma\n"
      + "    var lado: double\n"
      + "    var owner hijo: Cuadro\n"
      + "    private var secreto: integer\n"
      + "    event cambio(x: integer)\n"
      + "    @implement{ Forma }\n"
      + "    public property nombre: string\n"
      + "    public final property fijo: integer\n"
      + "    public const Cuadro.MAX: integer := 3\n"
      + "    public property Cuadro.total: long := 0L\n"
      + "    public function Cuadro()\n"
      + "      this.lado := 1.0d\n"
      + "    end Cuadro\n"
      + "    public function Cuadro(l: double)\n"
      + "      this.lado := l\n"
      + "    end Cuadro\n"
      + "    @implement{ Forma }\n"
      + "    public function area(): double\n"
      + "      return this.lado * this.lado\n"
      + "    end area\n"
      + "    public final function cuenta(var x: integer, paso: long := 2L, f: double := 0.5d, s: string := \"a\"): (boolean, integer)\n"
      + "      x := x + 1\n"
      + "      return (true, x)\n"
      + "    end cuenta\n"
      + "    function protegido(): integer\n"
      + "      return 1\n"
      + "    end protegido\n"
      + "    private function privado(): integer\n"
      + "      return 2\n"
      + "    end privado\n"
      + "    public function Cuadro.crea(): Cuadro\n"
      + "      return Cuadro()\n"
      + "    end Cuadro.crea\n"
      + "  end Cuadro\n"
      + "  public final class Fallo extends Core.Exception\n"
      + "    public function Fallo()\n"
      + "      super(\"f\")\n"
      + "    end Fallo\n"
      + "  end Fallo\n"
      + "  @element{Object}\n"
      + "  public class Bolsa\n"
      + "    public function Bolsa()\n"
      + "    end Bolsa\n"
      + "  end Bolsa\n"
      + "  public function suma(a: integer, b: integer): integer\n"
      + "    return a + b\n"
      + "  end suma\n"
      + "  public function nada()\n"
      + "  end nada\n"
      + "  function interna()\n"
      + "  end interna\n"
      + "  public function main()\n"
      + "    print suma(1, 2)\n"
      + "  end main\n"
      + "end Juguete\n";

    private static ModuleInterface compilaJuguete(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.write(src.resolve("Juguete.bp"), JUGUETE.getBytes(StandardCharsets.UTF_8));
        BpBuild b = new BpBuild();
        b.projectDir = tmp.toString(); b.sourceDir = src.toString(); b.outDir = out.toString(); b.main = "Juguete";
        b.dependencies.add(stdlib().toString());
        assertTrue(Main.buildProject(b, "mivm", false), "el módulo de juguete debe compilar");
        return Main.interfazDeUnMod(out.resolve("Juguete.mod"));
    }

    /** Lo que escribe `bp api` del juguete: lo público. `main` no sale (la entrada no se exporta), ni `interna` (privada);
     *  la interfaz de clase, con sus properties delante (así van en la interfaz). */
    private static final String API_JUGUETE =
        "// Juguete.mod\n"
      + "module Juguete\n"
      + "\n"
      + "  public function nada()\n"
      + "  public function suma(a: integer, b: integer): integer\n"
      + "\n"
      + "  public const LIMITE: integer := 10\n"
      + "  public const SALUDO: string := \"di \\\"hola\\\"\"\n"
      + "  public property contador: integer\n"
      + "\n"
      + "  public enum Color\n"
      + "    ROJO := 0\n"
      + "    VERDE := 5\n"
      + "    AZUL := 6\n"
      + "  end Color\n"
      + "\n"
      + "  @element{Object}\n"
      + "  public class Bolsa\n"
      + "    public function Bolsa()\n"
      + "  end Bolsa\n"
      + "\n"
      + "  public class Cuadro implements Forma\n"
      + "@@CAMPOS@@"
      + "    public function Cuadro()\n"
      + "    public function Cuadro(l: double)\n"
      + "    public property nombre: string\n"
      + "    public final property fijo: integer\n"
      + "    public const Cuadro.MAX: integer := 3\n"
      + "    public property Cuadro.total: long\n"
      + "    event cambio(x: integer)\n"
      + "    public function area(): double\n"
      + "    public final function cuenta(var x: integer, paso: long := 2L, f: double := 0.5d, s: string := \"a\"): (boolean, integer)\n"
      + "@@PROTEGIDO@@"
      + "    public function Cuadro.crea(): Cuadro\n"
      + "  end Cuadro\n"
      + "\n"
      + "  public final class Fallo extends Exception\n"
      + "    public function Fallo()\n"
      + "  end Fallo\n"
      + "\n"
      + "  public interface Forma\n"
      + "    property nombre: string\n"
      + "    function area(): double\n"
      + "  end Forma\n"
      + "end Juguete\n";

    @Test
    @DisplayName("un módulo con todo: cada declaración como se escribe en BP; lo protegido, con --todo; lo privado, nunca")
    void juguete(@TempDir Path tmp) throws Exception {
        ModuleInterface mi = compilaJuguete(tmp);
        assertEquals(API_JUGUETE.replace("@@CAMPOS@@", "").replace("@@PROTEGIDO@@", ""),
                EscritorApi.escribe(mi, "Juguete.mod", false));
        assertEquals(API_JUGUETE.replace("// Juguete.mod\n", "")
                        .replace("@@CAMPOS@@", "    var lado: double\n    var owner hijo: Cuadro\n")
                        .replace("@@PROTEGIDO@@", "    function protegido(): integer\n"),
                EscritorApi.escribe(mi, null, true));
    }

    /** Un BpVM.cfg en {@code dir} con la stdlib del repositorio (lo que tendría un fuente de esa carpeta). */
    private static void cfgCon(Path dir, Path stdlib) throws Exception {
        String s = stdlib.toString().replace('\\', '/');
        Files.write(dir.resolve("BpVM.cfg"), ("{ \"stdlibDir\": \"" + s + "\" }\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("el módulo, donde lo encontraría import: la stdlib; uno que no está, null; la lista, sin Stdlib")
    void comoImport(@TempDir Path tmp) throws Exception {
        Path std = stdlib();
        cfgCon(tmp, std);
        Main.InterfazHallada h = Main.interfazDeUnImport("Collections", tmp);
        assertNotNull(h);
        assertEquals(std.resolve("Collections.mod").toString(), Paths.get(h.origen).toAbsolutePath().normalize().toString());
        assertEquals("Collections", h.iface.moduleName);
        assertEquals(null, Main.interfazDeUnImport("NoExisteNinguno", tmp));
        Map<String, List<String>> lista = Main.modulosQueSePuedenImportar(tmp);
        List<String> deLaStdlib = lista.get(std.toString());
        assertNotNull(deLaStdlib, "la stdlib en la lista: " + lista);
        assertTrue(deLaStdlib.contains("Collections") && deLaStdlib.contains("Core"), lista.toString());
        assertFalse(deLaStdlib.contains("Stdlib"), "Stdlib no publica nada: " + lista);
    }

    @Test
    @DisplayName("un módulo que sólo está en un pack (SQLite): de la biblioteca de packs, como al compilar")
    void deUnPack(@TempDir Path tmp) throws Exception {
        Path packs = Paths.get("../../packs").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(packs.resolve("SQLite.pack")), "hace falta ../../packs/SQLite.pack");
        cfgCon(tmp, stdlib());
        Main.setPacksDir(packs.toString());
        try {
            Main.InterfazHallada h = Main.interfazDeUnImport("SQLite", tmp);
            assertNotNull(h, "SQLite, del pack");
            assertTrue(h.origen.startsWith("el pack SQLite ("), h.origen);
            String api = EscritorApi.escribe(h.iface, h.origen, false);
            assertTrue(api.contains("\n  public class Db\n") && api.contains("\n    public function query(sql: string): Query\n"), api);
            assertTrue(api.contains("\n    public function execLong(sql: string, siFalta: long := 0L): long\n"), api);
            Map<String, List<String>> lista = Main.modulosQueSePuedenImportar(tmp);
            boolean conElPack = false;
            for (Map.Entry<String, List<String>> e : lista.entrySet())
                if (e.getKey().startsWith("el pack SQLite (")) conElPack = e.getValue().contains("Orm") && e.getValue().contains("SQLite");
            assertTrue(conElPack, "Orm y SQLite, del pack, en la lista: " + lista);
        } finally {
            Main.setPacksDir(null);
        }
    }
}
