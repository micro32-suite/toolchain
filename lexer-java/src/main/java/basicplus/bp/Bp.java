// ============================================================
// Bp.java — V9 H1.2 (#853): la orden `bp`
// ============================================================
package basicplus.bp;

import basicplus.frontend.BpBuild;
import basicplus.frontend.DependenciasDelMod;
import basicplus.frontend.EscritorApi;
import basicplus.frontend.Main;
import basicplus.frontend.ModuleInterface;
import edu.bpgenvm.config.VmConfig;
import edu.bpgenvm.vm.debug.Traza;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        if (("build".equals(orden) || "run".equals(orden) || "test".equals(orden)) && !maxErrores(resto)) return 1;
        switch (orden) {
            case "help": case "-h": case "--help":
                ayuda(System.out);
                return 0;
            case "build": return build(resto);
            case "run":   return run(resto);
            case "png":   return png(resto);
            case "frontend": return frontend(resto);
            case "vm":       return vm(resto);
            case "placa":    return placa(resto);   // V9 #857
            case "api":      return api(resto);     // V9 #858
            case "test":     return Pruebas.ejecuta(resto);   // V9 #859
            default:
                System.err.println("bp: orden desconocida: " + orden);
                ayuda(System.err);
                return 1;
        }
    }

    /** V9 #860 (c) — {@code --max-errores=N}: los errores que se escriben (10; 0, todos). false si está mal escrito. */
    private static boolean maxErrores(List<String> args) {
        for (java.util.Iterator<String> it = args.iterator(); it.hasNext(); ) {
            String a = it.next();
            if (!a.startsWith("--max-errores=")) continue;
            try {
                Main.MAX_ERRORES = Integer.parseInt(a.substring("--max-errores=".length()));
            } catch (NumberFormatException ex) {
                System.err.println("bp: --max-errores necesita un entero (0, todos): " + a);
                return false;
            }
            it.remove();
        }
        return true;
    }

    static void ayuda(PrintStream out) {
        out.println("bp: las herramientas de BasicPlus, por línea de órdenes");
        out.println();
        out.println("  bp build <fuente.bp | fuente.jc | proyecto.bpbuild> [--out <carpeta>]");
        out.println("        compila: el .mod, en <la carpeta del fuente>/out (o en el outDir del proyecto). De los");
        out.println("        errores, los 10 primeros; con --max-errores=0 (aquí, en run y en test), todos");
        out.println("  bp run [--c] [opciones de la VM] <fuente | proyecto.bpbuild | programa.mod> [argumento]");
        out.println("        compila si hace falta, y ejecuta con la VM Java; con --c, con la VM-C (un proyecto que");
        out.println("        sale en pack, en su pack: en el PC, sus resources/ sólo están ahí)");
        out.println("        (sale con el código del programa; si no compila, con 2 y los errores)");
        out.println("        lo que va antes del fichero es para la VM: --no-screen, --screen=240x240…");
        out.println("  bp run --placa=<COM22 | IP de una placa en la red> <fuente | proyecto | programa.mod> [argumento]");
        out.println("  bp run --sim <…>");
        out.println("        en una placa, o en el micro simulado del IDE (lo arranca si hace falta): sube lo que");
        out.println("        haga falta, como el IDE, y lo ejecuta; su salida y su código, como con las VMs del PC");
        out.println("  bp placa <COM22 | IP | sim> ls [ruta] | get <ruta> [fichero] | stop | info");
        out.println("        lista sus ficheros, baja uno (una captura: get /X.shot), para lo que corre, su INFO");
        out.println("  bp placa <COM22 | IP | sim> packs | pack <fichero.pack | nombre>");
        out.println("        los packs que tiene grabados, y grabar uno: un .pack, o uno de la instalación por su");
        out.println("        nombre (Stdlib, Gui…); como el IDE, lo prepara para esa placa");
        out.println("  bp test [--guarda] [--tiempo=N] [<proyecto.bpbuild>] [<carpeta | prueba.bp …>]");
        out.println("        las pruebas: los programas de tests/ (o los que se le den), en las dos VMs del PC, sin");
        out.println("        pantalla; cada una está bien si acaba con 0, imprime lo mismo en las dos y, si tiene su");
        out.println("        X.out al lado, imprime eso (--guarda lo escribe). En un proyecto, lo compila antes.");
        out.println("        Sale con 0 si todas están bien. Las comprobaciones, con el módulo Assert");
        out.println("  bp api [<Módulo | programa.mod>] [--todo]");
        out.println("        la API de un módulo, en BP: sus declaraciones públicas, sin los cuerpos (lo encuentra como");
        out.println("        `import`: la stdlib, las librerías del BpVM.cfg, los packs); con --todo, también lo que ve");
        out.println("        quien hereda y lo del sistema (__nombre). Sin módulo, los que se pueden importar");
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
        /* V9 #858 — y su biblioteca de packs, como la del IDE: el compilador sólo los buscaba en el BpVM.cfg de la
         * carpeta de trabajo, y desde otra, un `import SQLite` se quedaba sin su interfaz. */
        Path packs = Instalacion.packsDir(instalacion());
        Main.setPacksDir(packs == null ? null : packs.toString());
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
        /* V9 #856 (b) — como --quiet (Eduardo: «Sólo los errores, y bp lo usa»): los errores y avisos, cada uno con su
         * fichero, y si compila, sólo dónde quedó el .mod. */
        Runnable vuelve = Main.callaLoDemas();
        Compilado c;
        try {
            c = compila(Paths.get(sueltos.get(0)), out);
        } finally {
            vuelve.run();
        }
        if (c.codigo == 0 && c.queda != null) System.out.println("compilado: " + rutaParaMostrar(c.queda));
        return c.codigo;
    }

    /** Una ruta, desde donde se trabaja si está dentro; si no, entera (la de los diagnósticos del compilador). */
    static String rutaParaMostrar(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path aqui = Paths.get("").toAbsolutePath().normalize();
        return abs.startsWith(aqui) && !abs.equals(aqui) ? aqui.relativize(abs).toString() : abs.toString();
    }

    /** Lo que deja una compilación: su código (0, 1 o 2), el .mod que hay que ejecutar, y lo que se dice que queda (de un
     *  proyecto que sale en pack, su .pack; de uno sin main, una librería, la carpeta). */
    static final class Compilado {
        final int codigo;
        final Path mod, queda;
        Compilado(int codigo, Path mod) { this(codigo, mod, mod); }
        Compilado(int codigo, Path mod, Path queda) { this.codigo = codigo; this.mod = mod; this.queda = queda; }
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
                for (String w : proj.warnings) Main.diag().println("aviso proyecto: " + w);   // V9 #897
                boolean ok = Main.buildProject(proj, "mivm", /*pruneBpi*/ true);
                Path dir = Paths.get(proj.outDir), mod = proj.modDelMain();
                return new Compilado(ok ? 0 : 2, mod, "pack".equals(proj.out) ? dir.resolve(proj.packName + ".pack")
                                                      : Files.isRegularFile(mod) ? mod : dir);
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

    // ---- api (V9 H1.7, #858) ----

    /** {@code bp api [Módulo | X.mod] [--todo]}: la API de un módulo escrita en BP, el módulo sin los cuerpos
     *  ({@link EscritorApi}), encontrado como lo encontraría un {@code import} en un fuente de la carpeta de trabajo; a
     *  secas, los módulos que se pueden importar desde ella. Eduardo, 23-nov: «Si». */
    static int api(List<String> args) {
        boolean todo = false;
        List<String> sueltos = new ArrayList<>();
        for (String a : args) {
            if ("--todo".equals(a)) todo = true;
            else if (a.startsWith("-")) { System.err.println("bp api: opción desconocida: " + a); return 1; }
            else sueltos.add(a);
        }
        if (sueltos.size() > 1) {
            System.err.println("bp api: un módulo cada vez (o ninguno: los que se pueden importar)");
            return 1;
        }
        cfgDeLaInstalacion();
        Path aqui = Paths.get("").toAbsolutePath().normalize();
        if (sueltos.isEmpty()) {
            Map<String, List<String>> porSitio = Main.modulosQueSePuedenImportar(aqui);
            if (porSitio.isEmpty()) {
                System.err.println("bp api: no encuentro ninguna librería: ni la stdlib (mira el BpVM.cfg)");
                return 1;
            }
            for (Map.Entry<String, List<String>> e : porSitio.entrySet()) {
                System.out.println(e.getKey() + ":");
                StringBuilder linea = new StringBuilder(" ");
                for (String n : e.getValue()) {
                    if (linea.length() + 1 + n.length() > 100) { System.out.println(linea); linea = new StringBuilder(" "); }
                    linea.append(' ').append(n);
                }
                System.out.println(linea);
            }
            System.out.println();
            System.out.println("La API de uno: bp api <Módulo>");
            return 0;
        }
        String q = sueltos.get(0);
        try {
            if (q.toLowerCase(Locale.ROOT).endsWith(".mod")) {
                Path mod = Paths.get(q);
                if (!Files.isRegularFile(mod)) { System.err.println("bp api: no existe " + q); return 1; }
                ModuleInterface mi = Main.interfazDeUnMod(mod);
                if (mi == null) { System.err.println("bp api: " + q + " no es un .mod que lleve su interfaz"); return 1; }
                System.out.print(EscritorApi.escribe(mi, rutaParaMostrar(mod), todo));
                return 0;
            }
            Main.InterfazHallada h = Main.interfazDeUnImport(q, aqui);
            if (h == null) {
                System.err.println("bp api: no encuentro el módulo '" + q + "' (ni en la stdlib, ni en las librerías"
                        + " del BpVM.cfg, ni en un pack). Los que hay: bp api. Uno tuyo, compílalo antes (bp build):"
                        + " se busca en out/, como al importarlo");
                return 1;
            }
            System.out.print(EscritorApi.escribe(h.iface, h.origen, todo));
            return 0;
        } catch (IOException e) {
            System.err.println("bp api: " + e.getMessage());
            return 1;
        }
    }

    // ---- run ----

    static int run(List<String> args) {
        boolean vmC = false;
        String placa = null;   // V9 #857: --placa=<COMx|IP> o --sim (el micro simulado)
        List<String> sueltos = new ArrayList<>();
        List<String> opcionesDeLaVm = new ArrayList<>();   // lo que va entre `bp run` y el fichero: para la VM
        for (String a : args) {
            if ("--c".equals(a) && sueltos.isEmpty()) vmC = true;
            else if ("--sim".equals(a) && sueltos.isEmpty()) placa = "sim";
            else if (a.startsWith("--placa=") && sueltos.isEmpty()) placa = a.substring("--placa=".length());
            else if (a.startsWith("-") && sueltos.isEmpty()) opcionesDeLaVm.add(a);
            else sueltos.add(a);
        }
        if (placa != null && (vmC || !opcionesDeLaVm.isEmpty() || placa.isEmpty())) {
            System.err.println("bp run: con --placa o --sim, ni --c ni opciones de la VM (son de las VMs del PC);"
                    + " y --placa=, con su puerto o su IP");
            return 1;
        }
        if (sueltos.isEmpty() || sueltos.size() > 2) {
            System.err.println("bp run: dime qué ejecutar (un fuente, un proyecto o un .mod) y, si acaso, su argumento");
            return 1;
        }
        VmConfig instalacion = cfgDeLaInstalacion();
        Path fichero = Paths.get(sueltos.get(0));
        String argumento = sueltos.size() > 1 ? sueltos.get(1) : null;
        /* El bash de Git cambia un argumento que empieza por «/» por una ruta de Windows: «/data/x.db» llega como
         * «C:/Program Files/Git/data/x.db», y el programa no encuentra nada (visto el 25-nov). Se dice. */
        if (argumento != null && argumento.matches("(?i)[A-Z]:/.*/Git/.*"))
            System.err.println("bp run: el argumento ha llegado como «" + argumento + "»: lo ha cambiado el bash de Git;"
                    + " si querías una ruta que empieza por «/», escríbela con dos (//data/x.db), o con"
                    + " MSYS_NO_PATHCONV=1 delante de bp");
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
                // V9 #856 (b) — y como --quiet: si no compila, se ven sólo los errores, cada uno con su fichero
                Runnable vuelve = Main.callaLoDemas();
                try {
                    c = compila(fichero, null);
                } finally {
                    vuelve.run();
                }
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
            /* V9 #900 — un proyecto que sale en pack se ejecuta en su pack, como en el IDE (#310): va cerrado, y en el PC
             * es donde están sus recursos (Eduardo, 13-nov: «Si hay que subir resources la única manera es hacer un pack
             * de aplicación que los incluya»). */
            mod = c.queda != null && c.queda.toString().endsWith(".pack") ? c.queda : c.mod;
            if (mod == null || !Files.isRegularFile(mod)) {
                System.err.print(new String(lo.toByteArray(), StandardCharsets.UTF_8));
                System.err.println("bp run: compiló, pero no encuentro " + mod);
                return 2;
            }
        }
        // V9 #857 — en una placa o en el micro simulado: lo hace el IDE (PlacaCli), como su Run on Device
        if (placa != null) {
            Path fuente = fichero.toAbsolutePath().normalize();
            return conLaPlaca(placa, fuente, mod, argumento, nombre.endsWith(".bpbuild") ? fuente : null);
        }
        /* La stdlib: la del BpVM.cfg que la VM encontrará sola (el del cwd, o subiendo desde el .mod), y si no hay
         * ninguno, la de la instalación. A la VM Java sólo hay que decírsela en el segundo caso (el cfg de la
         * instalación también es un fichero, pero ella no lo ve); a la VM-C, siempre (no lee BpVM.cfg). */
        VmConfig suyo = VmConfig.loadDefaultFor(mod);
        boolean laEncuentraSola = suyo.sourcePath != null;
        VmConfig cfg = laEncuentraSola ? suyo : (instalacion != null ? instalacion : suyo);
        String stdlib = (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) ? cfg.stdlibDir : null;
        boolean conPacks = false;
        for (String o : opcionesDeLaVm) conPacks |= o.startsWith("--pack");
        if (!conPacks && mod.toString().toLowerCase(Locale.ROOT).endsWith(".mod")) dilosDeLosPacks(fichero, mod, stdlib);
        return vmC ? conLaVmC(mod, argumento, stdlib, opcionesDeLaVm)
                   : conLaVmJava(mod, argumento, laEncuentraSola ? null : stdlib, opcionesDeLaVm);
    }

    /** V9 #904 — un módulo que sólo está en un pack (SQLite) compila, y las VMs del PC no lo cargan: lo que dirán es
     *  «falta el modulo 'SQLite'». Se dice antes qué pasa y dónde va (por la salida de errores). */
    private static void dilosDeLosPacks(Path fichero, Path mod, String stdlib) {
        try {
            List<Path> donde = new ArrayList<>();
            if (mod.getParent() != null) donde.add(mod.getParent());
            for (String falta : DependenciasDelMod.faltan(mod, donde, stdlib == null ? null : Paths.get(stdlib))) {
                Path carpeta = fichero.toAbsolutePath().normalize().getParent();
                Main.InterfazHallada h = Main.interfazDeUnImport(falta, carpeta);
                if (h == null || !h.origen.startsWith("el pack ")) continue;
                System.err.println("bp run: '" + falta + "' viene en " + h.origen.replaceFirst(" \\(.*", "") + ", que las VMs del"
                        + " PC no cargan; en el micro simulado sí: bp run --sim " + fichero);
                return;   // con uno basta: los demás serán de lo mismo
            }
        } catch (IOException e) { /* sólo era una ayuda */ }
    }

    // ---- la placa y el micro simulado (V9 #857: «Java, en bp, sobre lo del IDE») ----

    /** El cable lo lleva el IDE: com.mycompany.bpide.PlacaCli, en su jar (en la instalación, el de bp; en el
     *  repositorio, junto al del compilador en el classpath de bp). Por reflexión: el jar del compilador no lo ve. */
    private static final String SIN_EL_IDE = "hace falta el jar del IDE, de V9 en adelante, y en el classpath no está"
            + " (en el repositorio: mvn -f toolchain/BpIde/pom.xml package)";

    private static Object delIde(String metodo, Class<?>[] tipos, Object... args) throws ReflectiveOperationException {
        Class<?> k = Class.forName("com.mycompany.bpide.PlacaCli");
        return k.getMethod(metodo, tipos).invoke(null, args);
    }

    static int conLaPlaca(String destino, Path fuente, Path mod, String argumento, Path proyecto) {
        try {
            return (Integer) delIde("ejecuta",
                    new Class<?>[] { String.class, String.class, String.class, String.class, String.class },
                    destino, fuente.toString(), mod.toString(), argumento, proyecto == null ? null : proyecto.toString());
        } catch (ClassNotFoundException e) {
            System.err.println("bp: para una placa o el micro simulado " + SIN_EL_IDE);
            return 1;
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.err.println("bp: " + destino + ": " + e.getCause());
            return 1;
        } catch (ReflectiveOperationException e) {
            System.err.println("bp: " + e);
            return 1;
        }
    }

    // ---- placa ----

    static int placa(List<String> args) {
        try {
            return (Integer) delIde("placa", new Class<?>[] { String[].class }, (Object) args.toArray(new String[0]));
        } catch (ClassNotFoundException e) {
            System.err.println("bp placa: " + SIN_EL_IDE);
            return 1;
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.err.println("bp placa: " + e.getCause());
            return 1;
        } catch (ReflectiveOperationException e) {
            System.err.println("bp placa: " + e);
            return 1;
        }
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
            /* V9 #855 (c) — la salida estándar, tal cual (la del programa); la de errores pasa por aquí: debajo de su
             * traza («=== traza: Rompe+122 … ===»), la misma con sus nombres. La VM-C no tiene el .dbg (como en la
             * placa: la traducción es del lado del host); la VM Java la traduce ella. */
            ProcessBuilder pb = new ProcessBuilder(a);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            List<Path> donde = new ArrayList<>();
            Path padre = mod.toAbsolutePath().getParent();
            if (padre != null) donde.add(padre);
            if (stdlib != null) donde.add(Paths.get(stdlib));
            copiaLosErrores(p.getErrorStream(), donde);   // hasta que la VM cierre su salida de errores
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("bp run --c: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** V9 #855 (c) — la salida de errores de la VM-C, línea a línea y byte a byte (sin tocar su codificación); tras una
     *  traza, sus líneas traducidas con los .dbg de {@code donde}. */
    static void copiaLosErrores(InputStream in, List<Path> donde) throws IOException {
        OutputStream err = new FileOutputStream(FileDescriptor.err);
        InputStream bin = new BufferedInputStream(in);
        ByteArrayOutputStream linea = new ByteArrayOutputStream();
        int b;
        while ((b = bin.read()) != -1) {
            linea.write(b);
            if (b == '\n') sueltaLinea(linea, err, donde);
        }
        if (linea.size() > 0) sueltaLinea(linea, err, donde);
        err.flush();
    }

    private static void sueltaLinea(ByteArrayOutputStream linea, OutputStream err, List<Path> donde) throws IOException {
        byte[] bytes = linea.toByteArray();
        linea.reset();
        err.write(bytes);
        String traza = Traza.deLaLinea(new String(bytes, StandardCharsets.UTF_8));
        if (traza != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : Traza.lineas(traza, donde)) sb.append(l).append(System.lineSeparator());
            err.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        err.flush();
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
