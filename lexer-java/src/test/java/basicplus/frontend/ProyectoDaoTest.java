package basicplus.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** V9 #897 — un proyecto y sus DAO: el que los nombra antes de generarlos, y los de unas entidades en JC. */
class ProyectoDaoTest {

    private static final String JARDIN_BP = "module Jardin\n  @BD{ tabla = \"plantas\" }\n  class Planta\n"
            + "    @BD{ pk } public property id: long\n    @BD{} public property nombre: string\n  end Planta\nend Jardin\n";

    private static final String JARDIN_JC = "module Jardin\n{\n   @BD{ tabla = \"plantas\" }\n   public class Planta\n   {\n"
            + "      @BD{ pk }\n      public long id { get; set; }\n      @BD{}\n      public string nombre { get; set; }\n   }\n}\n";

    private static Path escribe(Path dir, String nombre, String texto) throws IOException {
        return Files.write(dir.resolve(nombre), texto.getBytes(StandardCharsets.UTF_8));
    }

    private static Path proyecto(Path dir, String... fuentes) throws IOException {
        StringBuilder s = new StringBuilder();
        for (String f : fuentes) s.append(s.length() == 0 ? "" : ", ").append("\"./").append(f).append('"');
        return escribe(dir, "P.bpbuild", "{ \"sourceDir\": \".\", \"sources\": [" + s + "], \"outDir\": \"out\" }\n");
    }

    /** Lo que se escribe (por las dos salidas) mientras se construye. */
    private static String construye(BpBuild proj) throws IOException {
        ByteArrayOutputStream lo = new ByteArrayOutputStream();
        PrintStream out = System.out, err = System.err;
        try (PrintStream aqui = new PrintStream(lo, true, "UTF-8")) {
            System.setOut(aqui);
            System.setErr(aqui);
            Main.buildProject(proj, "mivm", true);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new String(lo.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("El DAO por generar no impide abrir el proyecto; el build dice quién lo genera, y generarlo vale sin recargar")
    void daoPorGenerar(@TempDir Path dir) throws IOException {
        escribe(dir, "Jardin.bp", JARDIN_BP);
        BpBuild p = BpBuild.load(proyecto(dir, "Jardin.bp", "JardinDao.bp"));
        assertEquals(2, p.sources.size());
        assertEquals(2, p.sourcesDeclarados.size(), "lo que se guarda al editar sus propiedades");
        String m = construye(p);
        assertTrue(m.contains("'sources' nombra JardinDao.bp, que no está: lo genera DAO build, de las entidades de Jardin.bp"), m);
        DaoGen.Resultado r = DaoGen.generar(p, true);
        assertTrue(r.ok(), String.valueOf(r.errores));
        assertEquals(1, r.entidades);
        assertTrue(Files.isRegularFile(dir.resolve("JardinDao.bp")));
        assertFalse(construye(p).contains("que no está"));   // (compilarlo entero pide el Orm, del pack de la BD)
    }

    @Test @DisplayName("Un fuente que falta y no es un DAO sigue siendo un error al abrirlo")
    void otroQueFalta(@TempDir Path dir) throws IOException {
        escribe(dir, "Jardin.bp", JARDIN_BP);
        assertThrows(IOException.class, () -> BpBuild.load(proyecto(dir, "Jardin.bp", "Otro.bp")));
        assertThrows(IOException.class, () -> BpBuild.load(proyecto(dir, "Jardin.bp", "OtroDao.bp")));   // sin Otro al lado
        assertThrows(IOException.class, () -> BpBuild.load(proyecto(dir, "Jardin.bp", "Dao.bp")));
    }

    @Test @DisplayName("De un módulo del proyecto que no es su main no se avisa de que no define main; del main, sí")
    void avisoDeMain(@TempDir Path dir) throws IOException {
        escribe(dir, "Lib.bp", "module Lib\n  public function f(): integer\n    return 1\n  end f\nend Lib\n");
        escribe(dir, "App.bp", "module App\n  import Lib\n  function Main()\n    print Lib.f()\n  end Main\nend App\n");
        escribe(dir, "A.bpbuild", "{ \"main\": \"App\", \"sourceDir\": \".\", \"sources\": [\"./Lib.bp\", \"./App.bp\"], \"outDir\": \"out\" }\n");
        escribe(dir, "L.bpbuild", "{ \"main\": \"Lib\", \"sourceDir\": \".\", \"sources\": [\"./Lib.bp\"], \"outDir\": \"out\" }\n");
        assertFalse(construye(BpBuild.load(dir.resolve("A.bpbuild"))).contains("no define 'main'"));
        assertTrue(construye(BpBuild.load(dir.resolve("L.bpbuild"))).contains("el módulo 'Lib' no define 'main'"));
    }

    @Test @DisplayName("El .mod de un programa con library lleva su nombre entero, también el del main de un proyecto")
    void conLibrary(@TempDir Path dir) throws IOException {
        Path bp = escribe(dir, "Prog.bp", "// algo\nlibrary \"com.ejemplo\"\nmodule Prog\n  function Main()\n  end Main\nend Prog\n");
        Path jc = escribe(dir, "Otro.jc", "library \"com.ejemplo\";\nmodule Otro\n{\n   function void Main() { }\n}\n");
        assertEquals("com.ejemplo.Prog", Main.nombreDelModulo(bp));
        assertEquals("com.ejemplo.Otro", Main.nombreDelModulo(jc));
        for (String m : new String[] { "Prog", "Otro" }) {
            escribe(dir, "P.bpbuild", "{ \"main\": \"" + m + "\", \"sourceDir\": \".\", \"outDir\": \"out\" }\n");
            BpBuild p = BpBuild.load(dir.resolve("P.bpbuild"));
            construye(p);
            assertEquals("com.ejemplo." + m + ".mod", p.modDelMain().getFileName().toString());
            assertTrue(Files.isRegularFile(p.modDelMain()), "está: " + p.modDelMain());
        }
    }

    @Test @DisplayName("Los DAO de unas entidades escritas en JC, como los de BP")
    void entidadesEnJc(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("bp"));
        Files.createDirectories(dir.resolve("jc"));
        escribe(dir.resolve("bp"), "Jardin.bp", JARDIN_BP);
        escribe(dir.resolve("jc"), "Jardin.jc", JARDIN_JC);
        BpBuild enBp = BpBuild.load(proyecto(dir.resolve("bp"), "Jardin.bp"));
        BpBuild enJc = BpBuild.load(proyecto(dir.resolve("jc"), "Jardin.jc"));
        assertEquals(1, DaoGen.generar(enBp, true).entidades);
        assertEquals(1, DaoGen.generar(enJc, true).entidades);
        String a = new String(Files.readAllBytes(dir.resolve("bp/JardinDao.bp")), StandardCharsets.UTF_8);
        String b = new String(Files.readAllBytes(dir.resolve("jc/JardinDao.bp")), StandardCharsets.UTF_8);
        assertTrue(b.contains("entidades de `Jardin.jc`"), b);
        assertEquals(a, b.replace("`Jardin.jc`", "`Jardin.bp`"));
        assertEquals(1, DaoGen.generar(enJc, true).sinCambios.size(), "la segunda vez, al día");
    }
}
