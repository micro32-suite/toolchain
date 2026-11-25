package basicplus.frontend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 #903 — un módulo ya compilado que vive fuera del proyecto (un dispositivo de bpdevices, una carpeta de
 * "dependencies"): el compilador deja su copia junto al programa, que es donde lo busca la VM del PC. Lo encontró la
 * prueba final de la IA: compilaba y «falta el modulo 'Mcp9804'» al ejecutarlo.
 */
class LosDeFueraTest {

    private static void escribe(Path f, String s) throws IOException {
        Files.createDirectories(f.getParent());
        Files.write(f, s.getBytes(StandardCharsets.UTF_8));
    }

    /** Compila el proyecto de {@code dir} (su fuente, {@code src/<main>.bp}) con esas dependencias; lo que dijo. */
    private static String construye(Path dir, String main, boolean bien, Path... deps) throws IOException {
        BpBuild b = new BpBuild();
        b.projectDir = dir.toString();
        b.sourceDir = dir.resolve("src").toString();
        b.outDir = dir.resolve("out").toString();
        b.main = main;
        File std = new File("../../bpstdlib");
        Assumptions.assumeTrue(new File(std, "Core.mod").isFile(), "hace falta ../../bpstdlib/Core.mod");
        b.dependencies.add(std.getAbsolutePath());
        for (Path d : deps) b.dependencies.add(d.toString());
        PrintStream out0 = System.out, err0 = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, "UTF-8");
        try {
            System.setOut(ps);
            System.setErr(ps);
            boolean ok = Main.buildProject(b, "mivm", false);
            String dicho = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(bien, ok, dicho);
            return dicho;
        } finally {
            System.setOut(out0);
            System.setErr(err0);
        }
    }

    /** Una librería, compilada en su carpeta: {@code lib/out/Sensor.mod}, que usa {@code lib/out/Bits.mod}. */
    private static Path libreria(Path tmp, String extra) throws IOException {
        Path lib = tmp.resolve("lib");
        escribe(lib.resolve("src/Bits.bp"), "module Bits\n  public function baja(x: integer): integer\n    return x & 255\n"
                + "  end baja\nend Bits\n");
        escribe(lib.resolve("src/Sensor.bp"), "module Sensor\n  import Bits\n  public function lee(): integer\n"
                + "    return Bits.baja(513)\n  end lee\n" + extra + "end Sensor\n");
        construye(lib, "Sensor", true);
        for (String f : new String[] { "Bits.bp", "Sensor.bp" }) Files.delete(lib.resolve("src").resolve(f));
        return lib.resolve("out");
    }

    private static final String PROGRAMA = "module P\n  import Sensor\n  function Main()\n    print Sensor.lee()\n  end Main\nend P\n";

    @Test @DisplayName("lo que importa de fuera, y lo que eso importa, junto al programa")
    void juntoAlPrograma(@TempDir Path tmp) throws IOException {
        Path lib = libreria(tmp, "");
        Path p = tmp.resolve("p");
        escribe(p.resolve("src/P.bp"), PROGRAMA);
        String m = construye(p, "P", true, lib);
        assertArrayEquals(Files.readAllBytes(lib.resolve("Sensor.mod")), Files.readAllBytes(p.resolve("out/Sensor.mod")), m);
        assertArrayEquals(Files.readAllBytes(lib.resolve("Bits.mod")), Files.readAllBytes(p.resolve("out/Bits.mod")), m);
        assertTrue(m.contains("de fuera : Sensor.mod"), m);
        assertFalse(Files.exists(p.resolve("out/Core.mod")), "la stdlib no se copia");
        assertFalse(construye(p, "P", true, lib).contains("de fuera"), "la segunda vez no hay nada que copiar");
    }

    @Test @DisplayName("si el original cambia, manda él, y la copia se rehace")
    void cambiaElOriginal(@TempDir Path tmp) throws IOException {
        Path lib = libreria(tmp, "");
        Path p = tmp.resolve("p");
        escribe(p.resolve("src/P.bp"), PROGRAMA);
        construye(p, "P", true, lib);
        libreria(tmp, "  public function otra(): integer\n    return 7\n  end otra\n");
        escribe(p.resolve("src/P.bp"), PROGRAMA.replace("Sensor.lee()", "Sensor.otra()"));
        String m = construye(p, "P", true, lib);
        assertArrayEquals(Files.readAllBytes(lib.resolve("Sensor.mod")), Files.readAllBytes(p.resolve("out/Sensor.mod")), m);
    }

    @Test @DisplayName("un módulo del proyecto con el mismo nombre es suyo: no se toca")
    void elDelProyecto(@TempDir Path tmp) throws IOException {
        Path lib = libreria(tmp, "");
        Path p = tmp.resolve("p");
        escribe(p.resolve("src/P.bp"), PROGRAMA);
        escribe(p.resolve("src/Sensor.bp"), "module Sensor\n  public function lee(): integer\n    return 1\n  end lee\nend Sensor\n");
        construye(p, "P", true, lib);
        construye(p, "P", true, lib);
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(lib.resolve("Sensor.mod")),
                Files.readAllBytes(p.resolve("out/Sensor.mod"))));
        assertFalse(Files.exists(p.resolve("out/Bits.mod")));
    }
}
