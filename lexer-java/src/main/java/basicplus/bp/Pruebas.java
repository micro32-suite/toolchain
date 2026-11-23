// ============================================================
// Pruebas.java — V9 H1.8 (#859 a): bp test
// ============================================================
package basicplus.bp;

import basicplus.frontend.BpBuild;
import basicplus.frontend.Main;
import edu.bpgenvm.config.VmConfig;
import edu.bpgenvm.vm.debug.Traza;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@code bp test}: las pruebas de un programa o de un proyecto, sin nadie delante (V9 H1.8, {@code #859}; Eduardo,
 * 23-nov: «si»). Una prueba es un programa ({@code .bp} o {@code .jc}) de la carpeta {@code tests/}, y está bien si, en
 * las dos VMs del PC, compila, acaba con 0, imprime lo mismo en las dos (la regla de oro) y, si tiene al lado su
 * {@code X.out}, imprime eso. Son las reglas de la red del proyecto ({@code compat.sh}), para quien programa; las
 * comprobaciones, con el módulo {@code Assert} (un fallo, una excepción: el programa acaba con 1 y su traza dice la
 * línea).
 *
 * <p>Cada VM, en su proceso, sin pantalla ({@code --no-screen}: una GUI sin ventana) y con un tiempo máximo: una prueba
 * que no acaba no cuelga las demás. En un proyecto (su {@code .bpbuild}), primero se compila el proyecto, y cada prueba,
 * en su carpeta de salida: así encuentra sus módulos, al compilar y al ejecutar, como el programa. {@code --guarda}
 * escribe el {@code X.out} de cada una con lo que imprime ahora (lo que se mira antes de darlo por bueno). Sale con 0 si
 * todas están bien; con 1 si alguna no, o si no hay ninguna.
 */
final class Pruebas {

    private Pruebas() { }

    /** Segundos de cada VM con una prueba, si no se dice otra cosa ({@code --tiempo=N}). */
    static final int TIEMPO = 60;

    static int ejecuta(List<String> args) {
        boolean guarda = false;
        int tiempo = TIEMPO;
        List<String> sueltos = new ArrayList<>();
        for (String a : args) {
            if ("--guarda".equals(a)) guarda = true;
            else if (a.startsWith("--tiempo=")) {
                try { tiempo = Integer.parseInt(a.substring("--tiempo=".length())); }
                catch (NumberFormatException e) { tiempo = -1; }
                if (tiempo <= 0) { System.err.println("bp test: --tiempo= pide los segundos de cada VM (60 si no se dice)"); return 1; }
            } else if (a.startsWith("-")) {
                System.err.println("bp test: opción desconocida: " + a);
                return 1;
            } else sueltos.add(a);
        }
        VmConfig instalacion = Bp.cfgDeLaInstalacion();

        // El proyecto: el .bpbuild que se nombre, o el único de la carpeta de trabajo.
        Path aqui = Paths.get("").toAbsolutePath().normalize();
        Path proyecto = null;
        List<Path> pedidos = new ArrayList<>();
        for (String s : sueltos) {
            Path p = Paths.get(s).toAbsolutePath().normalize();
            if (s.toLowerCase(Locale.ROOT).endsWith(".bpbuild")) {
                if (proyecto != null) { System.err.println("bp test: un proyecto cada vez"); return 1; }
                proyecto = p;
            } else pedidos.add(p);
        }
        if (proyecto == null) proyecto = elUnicoProyecto(aqui);
        BpBuild proj = null;
        if (proyecto != null) {
            if (!Files.isRegularFile(proyecto)) { System.err.println("bp test: no existe " + proyecto); return 1; }
            try {
                proj = BpBuild.load(proyecto);
            } catch (IOException e) {
                System.err.println("bp test: " + proyecto.getFileName() + ": " + e.getMessage());
                return 1;
            }
            final BpBuild elProyecto = proj;
            String errores = callado(() -> Main.buildProject(elProyecto, "mivm", true));
            if (errores != null) {
                System.out.println("bp test: el proyecto " + Bp.rutaParaMostrar(proyecto) + " no compila:");
                System.out.print(sangra(errores));
                return 1;
            }
        }

        // Las pruebas: las que se nombren (ficheros o carpetas), o las de tests/ (junto al proyecto, o aquí).
        List<Path> pruebas = new ArrayList<>();
        if (pedidos.isEmpty()) {
            Path base = proyecto != null ? proyecto.getParent() : aqui;
            pedidos.add(base.resolve("tests"));
        }
        for (Path p : pedidos) {
            if (Files.isDirectory(p)) pruebas.addAll(fuentesDe(p));
            else if (Files.isRegularFile(p)) pruebas.add(p);
            else { System.err.println("bp test: no existe " + Bp.rutaParaMostrar(p)); return 1; }
        }
        if (pruebas.isEmpty()) {
            System.err.println("bp test: no hay pruebas: ni " + Bp.rutaParaMostrar(pedidos.get(0))
                    + " con programas (.bp, .jc), ni ficheros nombrados");
            return 1;
        }

        int bien = 0;
        for (Path prueba : pruebas) {
            Path salida = proj != null ? Paths.get(proj.outDir) : prueba.getParent().resolve("out");
            String mal = una(prueba, salida, instalacion, tiempo, guarda);
            if (mal == null) bien++;
        }
        int malas = pruebas.size() - bien;
        System.out.println(pruebas.size() + (pruebas.size() == 1 ? " prueba: " : " pruebas: ") + bien + " bien, "
                + malas + " mal");
        return malas == 0 ? 0 : 1;
    }

    private static Path elUnicoProyecto(Path dir) {
        List<Path> hay = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bpbuild")) {
            for (Path p : ds) hay.add(p);
        } catch (IOException ignored) { }
        return hay.size() == 1 ? hay.get(0) : null;
    }

    /** Los programas de una carpeta (.bp y .jc), por nombre. */
    private static List<Path> fuentesDe(Path dir) {
        List<Path> l = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(p) && (n.endsWith(".bp") || n.endsWith(".jc"))) l.add(p.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) { }
        Collections.sort(l);
        return l;
    }

    // ---- una prueba ----

    /** Una prueba: su línea («bien» o «MAL», y por qué). Devuelve null si está bien, o el porqué. */
    static String una(Path prueba, Path salida, VmConfig instalacion, int tiempo, boolean guarda) {
        String nombre = Bp.rutaParaMostrar(prueba);
        // 1) compilarla, en `salida`, sin la salida del compilador si va bien
        try {
            Files.createDirectories(salida);
        } catch (IOException e) {
            return informa(nombre, "no se puede crear " + salida + ": " + e.getMessage(), null);
        }
        final Path dondeVa = salida;
        String errores = callado(() -> Main.compileFile(prueba, dondeVa, "mivm", true));
        if (errores != null) return informa(nombre, "no compila", errores);
        Path mod = salida.resolve(Main.nombreDelModulo(prueba) + ".mod");
        if (!Files.isRegularFile(mod)) return informa(nombre, "compiló, pero no encuentro " + mod, null);

        // 2) en las dos VMs, cada una en su proceso, sin pantalla (la stdlib, como en bp run)
        VmConfig suyo = VmConfig.loadDefaultFor(mod);
        boolean laEncuentraSola = suyo.sourcePath != null;
        VmConfig cfg = laEncuentraSola ? suyo : (instalacion != null ? instalacion : suyo);
        String stdlib = (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) ? cfg.stdlibDir : null;
        Path vmC = Bp.laVmC(Bp.instalacion());
        if (vmC == null) return informa(nombre, "no encuentro la VM-C (bin/" + Bp.nombreDeLaVmC() + ")", null);

        List<String> java = new ArrayList<>();
        java.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        java.add("-Dfile.encoding=UTF-8");
        java.add("-cp");
        java.add(System.getProperty("java.class.path"));
        java.add("edu.bpgenvm.Main");
        java.add("--no-screen");
        if (!laEncuentraSola && stdlib != null) java.add("--stdlibDir=" + stdlib);
        java.add(mod.toString());
        List<String> c = new ArrayList<>();
        c.add(vmC.toString());
        c.add("--no-screen");
        if (stdlib != null) c.add("--libdir=" + stdlib);
        c.add(mod.toString());

        // las dos a la vez, cada una en su proceso: una prueba que se cuelga cuesta su tiempo una vez, no dos
        Corrida[] enC = new Corrida[1];
        Thread hiloC = new Thread(() -> enC[0] = corre(c, tiempo), "bp-test-vm-c");
        hiloC.start();
        Corrida j = corre(java, tiempo);
        try {
            hiloC.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Corrida k = enC[0] != null ? enC[0] : new Corrida();
        if (enC[0] == null) k.error = "interrumpida";
        List<Path> donde = new ArrayList<>();
        donde.add(salida);
        if (stdlib != null) donde.add(Paths.get(stdlib));

        // 3) lo que dice si está bien
        if (j.error != null) return informa(nombre, "la VM Java no arranca: " + j.error, null);
        if (k.error != null) return informa(nombre, "la VM-C no arranca: " + k.error, null);
        if (j.colgada || k.colgada) {
            String quien = j.colgada && k.colgada ? "en las dos VMs" : j.colgada ? "en la VM Java" : "en la VM-C";
            return informa(nombre, "no acaba en " + tiempo + " s " + quien + " (--tiempo=N para darle más)", null);
        }
        if (j.codigo != 0 || k.codigo != 0) {
            String que = j.codigo == k.codigo ? "acaba con " + j.codigo + " en las dos VMs"
                    : "acaba con " + j.codigo + " en la VM Java y con " + k.codigo + " en la VM-C";
            Corrida laQueFalla = j.codigo != 0 ? j : k;
            return informa(nombre, que, porQue(laQueFalla, donde));
        }
        if (!j.salida.equals(k.salida)) {
            return informa(nombre, "la VM Java y la VM-C no imprimen lo mismo", diferencia(j.salida, k.salida,
                    "VM Java", "VM-C"));
        }
        Path esperada = conExtension(prueba, ".out");
        if (Files.isRegularFile(esperada) && !guarda) {
            String quiere;
            try {
                quiere = new String(Files.readAllBytes(esperada), StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (IOException e) {
                return informa(nombre, "no puedo leer " + esperada.getFileName() + ": " + e.getMessage(), null);
            }
            if (!quiere.equals(j.salida)) {
                return informa(nombre, "no imprime lo de " + esperada.getFileName(), diferencia(quiere, j.salida,
                        esperada.getFileName().toString(), "imprime"));
            }
        }
        if (guarda) {
            try {
                Files.write(esperada, j.salida.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return informa(nombre, "no puedo escribir " + esperada.getFileName() + ": " + e.getMessage(), null);
            }
            System.out.println("bien   " + nombre + "   (guardada su salida: " + esperada.getFileName() + ")");
            return null;
        }
        System.out.println("bien   " + nombre);
        return null;
    }

    private static String informa(String nombre, String que, String detalle) {
        System.out.println("MAL    " + nombre + ": " + que);
        if (detalle != null && !detalle.isEmpty()) System.out.print(sangra(detalle));
        return que;
    }

    /** Lo que dice una VM al acabar mal: el mensaje de la excepción y dónde, con sus nombres; si no, el final de su salida
     *  de errores. */
    private static String porQue(Corrida r, List<Path> donde) {
        StringBuilder sb = new StringBuilder();
        String[] lineas = r.errores.split("\n");
        for (String l : lineas) {
            int i = l.toLowerCase(Locale.ROOT).indexOf("no atrapada: ");
            if (i >= 0 && sb.length() == 0) {
                String m = l.substring(i + "no atrapada: ".length()).trim();
                int v = m.indexOf(" (valor=");
                if (v > 0) m = m.substring(0, v);
                if (m.endsWith("===")) m = m.substring(0, m.length() - 3).trim();
                sb.append(m).append('\n');
            }
        }
        boolean traducida = false;
        for (String l : lineas) {
            if (l.startsWith("  en ") || l.startsWith("  llamado desde ")) { sb.append(l).append('\n'); traducida = true; }
        }
        if (!traducida) {
            for (String l : lineas) {
                String t = Traza.deLaLinea(l);
                if (t != null) {
                    for (String x : Traza.lineas(t, donde)) sb.append(x).append('\n');
                    break;
                }
            }
        }
        if (sb.length() > 0) return sb.toString();
        StringBuilder fin = new StringBuilder();   // sin excepción a la vista: lo último que dijo
        int desde = Math.max(0, lineas.length - 5);
        for (int i = desde; i < lineas.length; i++) if (!lineas[i].isEmpty()) fin.append(lineas[i]).append('\n');
        return fin.toString();
    }

    /** La primera línea en la que no coinciden dos salidas. */
    private static String diferencia(String a, String b, String nombreA, String nombreB) {
        String[] la = a.split("\n", -1), lb = b.split("\n", -1);
        int n = Math.min(la.length, lb.length);
        for (int i = 0; i < n; i++) {
            if (!la[i].equals(lb[i])) {
                return "la línea " + (i + 1) + ":\n  " + nombreA + ": «" + la[i] + "»\n  " + nombreB + ": «" + lb[i] + "»\n";
            }
        }
        return la.length > lb.length
                ? nombreA + " sigue, desde la línea " + (n + 1) + ": «" + la[n] + "»\n"
                : nombreB + " sigue, desde la línea " + (n + 1) + ": «" + lb[n] + "»\n";
    }

    private static Path conExtension(Path fuente, String ext) {
        String n = fuente.getFileName().toString();
        int punto = n.lastIndexOf('.');
        return fuente.resolveSibling((punto > 0 ? n.substring(0, punto) : n) + ext);
    }

    private static String sangra(String texto) {
        StringBuilder sb = new StringBuilder();
        for (String l : texto.replace("\r\n", "\n").split("\n")) if (!l.isEmpty()) sb.append("         ").append(l).append('\n');
        return sb.toString();
    }

    // ---- compilar sin ruido ----

    interface Compilacion { boolean hazla() throws IOException; }

    /** Compila con la salida del compilador recogida (y como --quiet): null si va bien; si no, lo que dijo. */
    static String callado(Compilacion c) {
        ByteArrayOutputStream lo = new ByteArrayOutputStream();
        PrintStream out = System.out, err = System.err;
        boolean ok = false;
        String fallo = null;
        try (PrintStream aqui = new PrintStream(lo, true, "UTF-8")) {
            System.setOut(aqui);
            System.setErr(aqui);
            Runnable vuelve = Main.callaLoDemas();
            try {
                ok = c.hazla();
            } catch (IOException e) {
                fallo = e.getMessage();
            } finally {
                vuelve.run();
            }
        } catch (IOException e) {   // el PrintStream en UTF-8: no pasa
            fallo = e.getMessage();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        if (ok) return null;
        String dicho = new String(lo.toByteArray(), StandardCharsets.UTF_8).trim();
        if (fallo != null) dicho = dicho.isEmpty() ? fallo : dicho + "\n" + fallo;
        return dicho.isEmpty() ? "(el compilador no dijo por qué)" : dicho;
    }

    // ---- una VM, en su proceso ----

    static final class Corrida {
        int codigo = -1;
        boolean colgada;
        String salida = "", errores = "", error;
    }

    /** Ejecuta {@code cmd} con un tiempo máximo: su código, su salida estándar (los finales de línea, \n) y la de errores. */
    static Corrida corre(List<String> cmd, int segundos) {
        Corrida r = new Corrida();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            p = pb.start();
            p.getOutputStream().close();   // sin entrada: una prueba no espera a nadie
        } catch (IOException e) {
            r.error = e.getMessage();
            return r;
        }
        ByteArrayOutputStream so = new ByteArrayOutputStream(), se = new ByteArrayOutputStream();
        Thread a = vacia(p.getInputStream(), so), b = vacia(p.getErrorStream(), se);
        try {
            if (!p.waitFor(segundos, TimeUnit.SECONDS)) {
                r.colgada = true;
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            } else {
                r.codigo = p.exitValue();
            }
            a.join(5000);
            b.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        r.salida = new String(so.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        r.errores = new String(se.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return r;
    }

    private static Thread vacia(InputStream in, ByteArrayOutputStream a) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    synchronized (a) { a.write(buf, 0, n); }
                }
            } catch (IOException ignored) { }
        }, "bp-test-lector");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
