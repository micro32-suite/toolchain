// ============================================================
// Bp.java — V9 H1.2 (#853): la orden `bp`
// ============================================================
package basicplus.bp;

import basicplus.frontend.BpBuild;
import basicplus.frontend.Main;
import edu.bpgenvm.config.VmConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * La orden {@code bp}: compilar, ejecutar y lo demás de la línea de órdenes, con un solo nombre (Eduardo, 23-nov:
 * «Uno: `bp`, con órdenes»). Va dentro del jar del IDE, que lleva el compilador y la VM Java, y el ZIP la trae con sus
 * lanzadores ({@code bp.bat}, y {@code bp} para bash). Antes, la documentación mandaba teclear
 * {@code java -jar basicplus-frontend.jar …} y {@code java -jar bpgenvm.jar …}, y esos jars no van en el ZIP (#853).
 *
 * <p>La stdlib, la de siempre: el BpVM.cfg de la carpeta de trabajo o, subiendo, el del fuente; y si no hay ninguno, el
 * de la instalación (el respaldo del IDE, #867). Lo que traiga el resto del H1 (la API de un módulo, los diagnósticos
 * para una máquina, el cable) entra aquí como órdenes nuevas.
 */
public final class Bp {

    private Bp() { }

    public static void main(String[] args) {
        System.exit(ejecuta(args));
    }

    /** El código de salida: 0 bien; 1 mal llamada; 2 no compila; con {@code run}, el del programa. */
    static int ejecuta(String[] args) {
        if (args.length == 0) {
            ayuda(System.err);
            return 1;
        }
        String orden = args[0];
        List<String> resto = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        switch (orden) {
            case "help": case "-h": case "--help":
                ayuda(System.out);
                return 0;
            case "build": return build(resto);
            case "run":   return run(resto);
            case "png":   return png(resto);
            case "frontend": return frontend(resto);
            case "vm":       return vm(resto);
            default:
                System.err.println("bp: orden desconocida: " + orden);
                ayuda(System.err);
                return 1;
        }
    }

    static void ayuda(PrintStream out) {
        out.println("bp: las herramientas de BasicPlus, por línea de órdenes");
        out.println();
        out.println("  bp build <fuente.bp | fuente.jc | proyecto.bpbuild> [--out <carpeta>]");
        out.println("        compila: el .mod, en <la carpeta del fuente>/out (o en el outDir del proyecto)");
        out.println("  bp run [--c] [opciones de la VM] <fuente | proyecto.bpbuild | programa.mod> [argumento]");
        out.println("        compila si hace falta, y ejecuta con la VM Java; con --c, con la VM-C");
        out.println("        (sale con el código del programa; si no compila, con 2 y los errores)");
        out.println("        lo que va antes del fichero es para la VM: --no-screen, --screen=240x240…");
        out.println("  bp png <captura.shot> [salida.png]");
        out.println("        una captura de Gui.shot, a PNG");
        out.println("  bp frontend <lo del compilador>");
        out.println("        el compilador, con todas sus opciones (--project, --interface, --dao, --escribir-jc…)");
        out.println("  bp vm <lo de la VM Java>");
        out.println("        la VM Java, con todas sus opciones (-d desensambla, --trace, --listen…)");
        out.println("  bp help");
        out.println("        esta ayuda");
        out.println();
        out.println("La stdlib: la del BpVM.cfg de la carpeta de trabajo o, subiendo, la del fuente; sin");
        out.println("ninguno, la de la instalación (la carpeta de este jar).");
    }

    // ---- la instalación ----

    /** La carpeta de la instalación: la de este jar. */
    static Path instalacion() {
        return Instalacion.dirDe(Bp.class);
    }

    /** El BpVM.cfg de la instalación (null si no hay), y el compilador avisado de él: sólo vale si no aparece otro. */
    static VmConfig cfgDeLaInstalacion() {
        VmConfig cfg = Instalacion.cfg(instalacion());
        Main.setCfgDelHospedador(cfg);
        return cfg;
    }

    // ---- build ----

    static int build(List<String> args) {
        String out = null;
        List<String> sueltos = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("--out".equals(a)) {
                if (i + 1 >= args.size()) { System.err.println("bp build: --out pide una carpeta"); return 1; }
                out = args.get(++i);
            } else if (a.startsWith("--out=")) {
                out = a.substring("--out=".length());
            } else if (a.startsWith("-")) {
                System.err.println("bp build: opción desconocida: " + a);
                return 1;
            } else {
                sueltos.add(a);
            }
        }
        if (sueltos.size() != 1) {
            System.err.println("bp build: dime un fuente (.bp o .jc) o un proyecto (.bpbuild)");
            return 1;
        }
        cfgDeLaInstalacion();
        Compilado c = compila(Paths.get(sueltos.get(0)), out);
        return c.codigo;
    }

    /** Lo que deja una compilación: su código (0, 1 o 2) y el .mod que hay que ejecutar. */
    static final class Compilado {
        final int codigo;
        final Path mod;
        Compilado(int codigo, Path mod) { this.codigo = codigo; this.mod = mod; }
    }

    /** Compila un fuente o un proyecto, con la salida del compilador tal cual. */
    static Compilado compila(Path fichero, String out) {
        String nombre = fichero.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(fichero)) {
            System.err.println("bp: no existe " + fichero);
            return new Compilado(1, null);
        }
        Path abs = fichero.toAbsolutePath().normalize();
        try {
            if (nombre.endsWith(".bpbuild")) {
                if (out != null) { System.err.println("bp: un proyecto deja lo suyo en su outDir; --out no vale"); }
                BpBuild proj = BpBuild.load(abs);
                boolean ok = Main.buildProject(proj, "mivm", /*pruneBpi*/ true);
                return new Compilado(ok ? 0 : 2, Paths.get(proj.outDir).resolve(proj.main + ".mod"));
            }
            if (nombre.endsWith(".bp") || nombre.endsWith(".jc")) {
                Path dir = (out != null) ? Paths.get(out).toAbsolutePath().normalize() : abs.getParent().resolve("out");
                Files.createDirectories(dir);
                boolean ok = Main.compileFile(abs, dir, "mivm", /*pruneBpi*/ true);
                return new Compilado(ok ? 0 : 2, dir.resolve(Main.nombreDelModulo(abs) + ".mod"));
            }
        } catch (IOException e) {
            System.err.println("bp: " + e.getMessage());
            return new Compilado(2, null);
        }
        System.err.println("bp: no sé compilar " + fichero + " (un .bp, un .jc o un .bpbuild)");
        return new Compilado(1, null);
    }

    // ---- run ----

    static int run(List<String> args) {
        boolean vmC = false;
        List<String> sueltos = new ArrayList<>();
        List<String> opcionesDeLaVm = new ArrayList<>();   // lo que va entre `bp run` y el fichero: para la VM
        for (String a : args) {
            if ("--c".equals(a) && sueltos.isEmpty()) vmC = true;
            else if (a.startsWith("-") && sueltos.isEmpty()) opcionesDeLaVm.add(a);
            else sueltos.add(a);
        }
        if (sueltos.isEmpty() || sueltos.size() > 2) {
            System.err.println("bp run: dime qué ejecutar (un fuente, un proyecto o un .mod) y, si acaso, su argumento");
            return 1;
        }
        VmConfig instalacion = cfgDeLaInstalacion();
        Path fichero = Paths.get(sueltos.get(0));
        String argumento = sueltos.size() > 1 ? sueltos.get(1) : null;
        String nombre = fichero.getFileName().toString().toLowerCase(Locale.ROOT);
        Path mod;
        if (nombre.endsWith(".mod") || nombre.endsWith(".pack")) {
            if (!Files.isRegularFile(fichero)) { System.err.println("bp: no existe " + fichero); return 1; }
            mod = fichero.toAbsolutePath().normalize();
        } else {
            // Compilar, sin la salida del compilador si va bien: lo que se ve es lo que imprime el programa.
            ByteArrayOutputStream lo = new ByteArrayOutputStream();
            PrintStream out = System.out, err = System.err;
            Compilado c;
            try (PrintStream aqui = new PrintStream(lo, true, "UTF-8")) {
                System.setOut(aqui);
                System.setErr(aqui);
                c = compila(fichero, null);
            } catch (IOException e) {
                c = new Compilado(2, null);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            if (c.codigo != 0) {
                System.err.print(new String(lo.toByteArray(), StandardCharsets.UTF_8));
                if (c.codigo == 2) System.err.println("bp run: no compila; no se ejecuta");
                return c.codigo;
            }
            mod = c.mod;
            if (mod == null || !Files.isRegularFile(mod)) {
                System.err.print(new String(lo.toByteArray(), StandardCharsets.UTF_8));
                System.err.println("bp run: compiló, pero no encuentro " + mod);
                return 2;
            }
        }
        /* La stdlib: la del BpVM.cfg que la VM encontrará sola (el del cwd, o subiendo desde el .mod), y si no hay
         * ninguno, la de la instalación. A la VM Java sólo hay que decírsela en el segundo caso (el cfg de la
         * instalación también es un fichero, pero ella no lo ve); a la VM-C, siempre (no lee BpVM.cfg). */
        VmConfig suyo = VmConfig.loadDefaultFor(mod);
        boolean laEncuentraSola = suyo.sourcePath != null;
        VmConfig cfg = laEncuentraSola ? suyo : (instalacion != null ? instalacion : suyo);
        String stdlib = (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) ? cfg.stdlibDir : null;
        return vmC ? conLaVmC(mod, argumento, stdlib, opcionesDeLaVm)
                   : conLaVmJava(mod, argumento, laEncuentraSola ? null : stdlib, opcionesDeLaVm);
    }

    /** Con la VM Java, aquí mismo. {@code stdlib}, sólo si la VM no la va a encontrar sola (sin BpVM.cfg a la vista). */
    static int conLaVmJava(Path mod, String argumento, String stdlib, List<String> opciones) {
        List<String> a = new ArrayList<>();
        if (stdlib != null) a.add("--stdlibDir=" + stdlib);
        a.addAll(opciones);
        a.add(mod.toString());
        if (argumento != null) a.add(argumento);
        try {
            edu.bpgenvm.Main.main(a.toArray(new String[0]));   // con un código distinto de 0, sale ella (System.exit)
        } catch (IOException e) {
            System.err.println("bp run: " + e.getMessage());
            return 2;
        }
        return 0;
    }

    /** Con la VM-C: {@code bin/bpgenvm-c} de la instalación (en el repositorio, {@code bpgenvm-c/build}). */
    static int conLaVmC(Path mod, String argumento, String stdlib, List<String> opciones) {
        Path vm = laVmC(instalacion());
        if (vm == null) {
            System.err.println("bp run --c: no encuentro la VM-C (bin/" + nombreDeLaVmC() + " junto a la instalación)");
            return 1;
        }
        List<String> a = new ArrayList<>();
        a.add(vm.toString());
        if (stdlib != null) a.add("--libdir=" + stdlib);
        a.addAll(opciones);
        a.add(mod.toString());
        if (argumento != null) a.add(argumento);
        try {
            Process p = new ProcessBuilder(a).inheritIO().start();
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("bp run --c: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    static String nombreDeLaVmC() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "bpgenvm-c.exe" : "bpgenvm-c";
    }

    /** La VM-C: en la instalación, {@code bin/}; en el repositorio, {@code bpgenvm-c/build/}, subiendo. Null si no. */
    static Path laVmC(Path dir) {
        if (dir == null) return null;
        String exe = nombreDeLaVmC();
        Path enBin = dir.resolve("bin").resolve(exe);
        if (Files.isRegularFile(enBin)) return enBin;
        for (Path d = dir; d != null; d = d.getParent()) {
            Path b = d.resolve("bpgenvm-c").resolve("build").resolve(exe);
            if (Files.isRegularFile(b)) return b;
        }
        return null;
    }

    // ---- frontend y vm: el compilador y la VM Java tal cual (Eduardo, 23-nov: «`bp frontend …` y `bp vm …`») ----

    /** {@code bp frontend …}: el compilador con todas sus opciones (la referencia, §16.1). Sale él, con su código. Sin
     *  BpVM.cfg a la vista, con la stdlib de la instalación (el respaldo del hospedador, #867). */
    static int frontend(List<String> args) {
        cfgDeLaInstalacion();
        Main.main(args.toArray(new String[0]));
        return 0;
    }

    /** {@code bp vm …}: la VM Java con todas sus opciones (§16.2). Si no se le dice la configuración y el programa no
     *  tiene BpVM.cfg a la vista, con la stdlib de la instalación. Con un código distinto de 0, sale ella. */
    static int vm(List<String> args) {
        List<String> a = new ArrayList<>(args);
        boolean dicha = false;
        String programa = null;
        for (String s : args) {
            if (s.startsWith("--stdlibDir") || s.startsWith("--config") || "--no-config".equals(s)) dicha = true;
            String n = s.toLowerCase(Locale.ROOT);
            if (programa == null && (n.endsWith(".mod") || n.endsWith(".pack") || n.endsWith(".bpproject"))) programa = s;
        }
        VmConfig instalacion = Instalacion.cfg(instalacion());
        if (!dicha && programa != null && instalacion != null && instalacion.stdlibDir != null
                && VmConfig.loadDefaultFor(Paths.get(programa).toAbsolutePath()).sourcePath == null) {
            a.add(0, "--stdlibDir=" + instalacion.stdlibDir);
        }
        try {
            edu.bpgenvm.Main.main(a.toArray(new String[0]));
        } catch (IOException e) {
            System.err.println("bp vm: " + e.getMessage());
            return 2;
        }
        return 0;
    }

    // ---- png ----

    static int png(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            System.err.println("bp png: dime la captura (.shot) y, si acaso, el PNG de salida");
            return 1;
        }
        return CapturaPng.convierte(args.get(0), args.size() > 1 ? args.get(1) : null, System.out, System.err);
    }
}
