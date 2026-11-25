// ============================================================
// PlacaCli.java
// V9 #857 — EL CABLE, POR LÍNEA DE ÓRDENES: lo que hace el IDE con una placa o
// con el micro simulado, para `bp` (Eduardo, 23-nov: «Java, en bp, sobre lo del
// IDE»). La subida, la del IDE (Despliegue); el cable, el suyo (los Backend sobre
// BpvmClient: serie, red o TCP); y el micro simulado, el del IDE (SimRunner, con
// su configuración y su carpeta; si ya está en marcha, ése: «El mismo del IDE»).
//
//   bp run --placa=COM22 X.bp        (o =192.168.1.53: una placa en la red)
//   bp run --sim X.bp
//   bp placa <COMx|ip|sim> ls [ruta] | get <ruta> [fichero] | stop | info
//   bp placa <COMx|ip|sim> packs | pack <fichero.pack|nombre>          (V9 #875)
//
// La salida del programa, a la estándar; lo demás (la subida, el simulado, la
// traza), a la de errores. `bp run` sale con el código del programa; `bp placa`,
// con 0, o con 1 si algo falla. `bp` la llama por reflexión: su jar (el del
// compilador) no ve las clases del IDE.
// ============================================================
package com.mycompany.bpide;

import edu.bpgenvm.vm.debug.ExitedEvent;
import edu.bpgenvm.vm.debug.Traza;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlacaCli {

    private PlacaCli() {}

    /** Lo de la placa que no es del programa: a la salida de errores. */
    private static void err(String s) {
        if (s == null) return;
        String t = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        System.err.println(t);
    }

    /** Un destino abierto: el backend conectado y, si lo arrancó bp, el micro simulado (que se para al cerrar). */
    private static final class Conexion implements AutoCloseable {
        final Backend b;
        final SimRunner sim;
        final String endpoint;

        Conexion(Backend b, SimRunner sim, String endpoint) {
            this.b = b;
            this.sim = sim;
            this.endpoint = endpoint;
        }

        BpvmClient cliente() {
            return (b instanceof AbstractBpvmBackend) ? ((AbstractBpvmBackend) b).debugClient() : null;
        }

        @Override public void close() {
            try { b.close(); } catch (Exception ignored) { }
            if (sim != null) sim.stop();
        }
    }

    /** ¿Escucha alguien en 127.0.0.1:{@code puerto}? (El micro simulado del IDE, si lo tiene en marcha.) */
    private static boolean escucha(int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", puerto), 300);
            return true;
        } catch (IOException no) {
            return false;
        }
    }

    /** El destino: «sim» (el micro simulado del IDE), un puerto serie («COM22», «/dev/ttyACM0») o una placa en la red
     *  («192.168.1.53», o con su puerto, «…:3333»), como en el panel de la placa del IDE. */
    private static Conexion abre(String destino) throws IOException {
        Conexion c = conecta(destino);
        String otra = (c.b instanceof AbstractBpvmBackend) ? ((AbstractBpvmBackend) c.b).avisoVersion() : null;
        if (otra != null) err("bp: ⚠ " + otra);   // V9 #907
        return c;
    }

    private static Conexion conecta(String destino) throws IOException {
        if ("sim".equalsIgnoreCase(destino)) {
            IdePrefs prefs = IdePrefs.load();
            String ep = "127.0.0.1:" + prefs.simPort;
            SimRunner sim = null;
            if (!escucha(prefs.simPort)) {
                sim = new SimRunner(PlacaCli::err);
                sim.start(prefs);
            } else {
                err("[sim] el micro simulado ya está en marcha en " + ep + ": se usa ése");
            }
            Backend b = new BpvmBackend("Micro simulado");
            try {
                b.connect(ep);
            } catch (IOException e) {
                if (sim != null) sim.stop();
                /* Ya en marcha y sin contestar: atiende a un cliente a la vez (medido: con otro bp ejecutando en él, el
                 * segundo no conecta). El mensaje del cliente («¿está en marcha? «Arrancar»…») aquí despista. */
                throw new IOException(sim == null
                        ? "el micro simulado (" + ep + ") está en marcha, pero no atiende: atiende a uno a la vez"
                          + " (¿lo tiene otro? ¿el IDE, conectado a él, u otro bp?)"
                        : "no se pudo conectar al micro simulado (" + ep + ") recién arrancado: " + e.getMessage(), e);
            }
            return new Conexion(b, sim, ep);
        }
        boolean serie = destino.matches("(?i)COM\\d+") || destino.startsWith("/dev/");
        Backend b = serie ? new SerialBackend() : new RedBackend();
        b.connect(destino);
        return new Conexion(b, null, destino);
    }

    /**
     * `bp run --placa=<destino> X` y `bp run --sim X`: con lo compilado ({@code mod}, al lado de su .dbg), sube a la placa
     * lo que hace falta (Despliegue: lo mismo que el Run on Device del IDE), lo ejecuta, escribe su salida y devuelve su
     * código de salida. {@code fuente}: el .bp/.jc (o el .mod, si se pidió uno ya compilado); {@code bpbuild}: el
     * proyecto, o null (su carpeta en la placa, /app/&lt;proyecto&gt;, y sus recursos).
     */
    public static int ejecuta(String destino, String fuente, String mod, String argumento, String bpbuild) {
        Path modPath = Paths.get(mod).toAbsolutePath().normalize();
        Path src = Paths.get(fuente).toAbsolutePath().normalize();
        Path proyecto = (bpbuild != null) ? Paths.get(bpbuild).toAbsolutePath().normalize() : null;
        Path modDir = modPath.getParent();
        TrazaError.conoce(modDir);   // los .dbg del programa, para la línea del fin («exit 1 (…, Rompe.bp:8 (Main))»)
        boolean packRun = modPath.getFileName().toString().toLowerCase().endsWith(".pack");

        edu.bpgenvm.config.VmConfig cfg = null;
        try { cfg = IdePrefs.cfgPara(src); } catch (Throwable ignored) { }
        String stdlibDir = (cfg != null && cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) ? cfg.stdlibDir : null;
        // #310 — un .pack viaja cerrado: sus módulos y recursos van dentro (como en el IDE)
        List<File> deps = packRun ? Collections.<File>emptyList()
                                  : Despliegue.dependencias(src, modDir, modPath, cfg, PlacaCli::err);
        Set<String> libs = Despliegue.deLaStdlib(deps, stdlibDir);
        Map<String, File> recursos = packRun ? Collections.<String, File>emptyMap()
                                             : Despliegue.recursos(proyecto, PlacaCli::err);
        String prefijo = Despliegue.prefijoEnLaPlaca(proyecto);

        try (Conexion c = abre(destino)) {
            BpvmClient dc = c.cliente();
            Despliegue.Perfil perfil = Despliegue.Perfil.NINGUNO;
            if (dc != null) {
                try { perfil = Despliegue.Perfil.delInfo(dc.getInfo(4000)); } catch (IOException sinInfo) { /* sin él */ }
            }
            Despliegue despliegue = new Despliegue();
            String remoto = despliegue.sube(c.b, dc, perfil, modPath.toFile(), deps, libs, recursos, prefijo, PlacaCli::err);
            if (c.b instanceof AbstractBpvmBackend) ((AbstractBpvmBackend) c.b).sinTrazaEnLaSalida();
            String fin = c.b.run(remoto, argumento, System.out::println);   // la salida del programa, tal cual
            System.out.flush();
            ExitedEvent e = (c.b instanceof AbstractBpvmBackend) ? ((AbstractBpvmBackend) c.b).ultimoExited() : null;
            if (e == null) {
                err("bp: la placa no dijo cómo acabó el programa (" + fin + ")");
                return 1;
            }
            if (e.stackTrace != null && !e.stackTrace.trim().isEmpty()) {
                // la traza, con sus nombres: los .dbg, junto al programa y en la stdlib (#855: la del IDE, Traza)
                List<Path> donde = new ArrayList<>();
                donde.add(modDir);
                if (stdlibDir != null) donde.add(Paths.get(stdlibDir));
                err(Traza.PREFIJO + e.stackTrace.trim() + " ===");
                for (String l : Traza.lineas(e.stackTrace, donde)) err(l);
            }
            if (e.exitCode != 0) err("bp: " + fin);
            String pista = despliegue.pista(fin);   // V9 #907
            if (!pista.isEmpty()) err("bp: " + pista);
            return e.exitCode;
        } catch (IOException ex) {
            err("bp: " + destino + ": " + ex.getMessage());
            return 1;
        }
    }

    /** Una ruta de la placa, como la escribió quien llama. El bash de Git (MSYS) cambia un argumento que empieza por «/»
     *  por una ruta de Windows: «/app» llega como «C:/Program Files/Git/app», y un `ls` no encontraba nada, sin decir
     *  por qué (medido). Las de la placa no llevan letra de unidad: una que la lleva se dice, con el remedio. Y «//app»
     *  (lo que el bash deja pasar como «/app») vale también desde cmd y PowerShell. */
    static String rutaDeLaPlaca(String ruta) throws IOException {
        if (ruta.matches("[A-Za-z]:[\\\\/].*")) {
            throw new IOException("«" + ruta + "» no es una ruta de la placa: el bash de Git cambia las que empiezan"
                    + " por «/»; escríbela con dos (//app/X.mod), o con MSYS_NO_PATHCONV=1 delante de bp");
        }
        return ruta.startsWith("//") ? ruta.substring(1) : ruta;
    }

    /** `bp placa <destino> <orden> …`: lo de la placa que no es ejecutar. 0 si va bien; 1 si no. */
    public static int placa(String[] args) {
        if (args.length < 2) {
            err("bp placa: dime la placa (COM22, una IP o sim) y qué hacer: ls [ruta], get <ruta> [fichero], stop, info,"
                    + " packs o pack <fichero.pack|nombre>");
            return 1;
        }
        String destino = args[0];
        String orden = args[1];
        try (Conexion c = abre(destino)) {
            switch (orden) {
                case "ls": {
                    String bajo = args.length > 2 ? rutaDeLaPlaca(args[2]) : null;
                    for (Backend.Entry e : c.b.list()) {
                        if (bajo != null && !e.name.startsWith(bajo)) continue;
                        System.out.println(e.isDir ? String.format("%9s  %s", "carpeta", e.name)
                                                   : String.format("%9d  %s", e.size, e.name));
                    }
                    return 0;
                }
                case "get": {
                    if (args.length < 3) { err("bp placa get: ¿qué fichero? (su ruta en la placa: /app/X.shot)"); return 1; }
                    String ruta = rutaDeLaPlaca(args[2]);
                    byte[] datos;
                    try {
                        datos = c.b.get(ruta);
                    } catch (IOException e) {   // el NOT_FOUND de la placa no dice de qué fichero
                        throw new IOException(ruta + ": " + e.getMessage(), e);
                    }
                    Path a = Paths.get(args.length > 3 ? args[3] : ruta.substring(ruta.lastIndexOf('/') + 1));
                    Files.write(a, datos);
                    System.out.println("bajado: " + a + " (" + datos.length + " bytes)");
                    return 0;
                }
                case "stop": {
                    c.b.kill();
                    System.out.println("parado");
                    return 0;
                }
                case "info": {
                    BpvmClient dc = c.cliente();
                    if (dc == null) { err("bp placa info: este destino no da su INFO"); return 1; }
                    String v = VersionPlaca.enElInfo(dc.serverName(), dc.serverVersion(), VersionPlaca.mia());   // V9 #907
                    if (!v.isEmpty()) System.out.println("version: " + v);
                    for (Map.Entry<String, Object> e : dc.getInfo(4000).entrySet()) {
                        if (e.getKey().equals("type") || e.getKey().equals("id")) continue;   // del cable, no de la placa
                        System.out.println(e.getKey() + ": " + e.getValue());
                    }
                    return 0;
                }
                case "packs": {   // V9 #875: los que tiene grabados, y el resumen de la zona
                    BpvmClient dc = c.cliente();
                    if (dc == null) { err("bp placa packs: este destino no tiene zona de packs"); return 1; }
                    BpvmClient.PackTable t = dc.packList(8000);
                    for (BpvmClient.PackInfo pi : t.packs) {
                        System.out.println(String.format("%9d  %-8s %s  %s%s%s", pi.size, pi.active ? "activo" : "retirado",
                                PacksPlaca.fecha(pi.date), pi.name, pi.version.isEmpty() ? "" : " " + pi.version,
                                pi.crcOk ? "" : "  CRC MAL"));
                    }
                    System.out.println(PacksPlaca.resumen(t));
                    return 0;
                }
                case "pack": {   // V9 #875 — grabar uno: un fichero, o uno de la instalación por su nombre
                    BpvmClient dc = c.cliente();
                    if (dc == null) { err("bp placa pack: este destino no tiene zona de packs"); return 1; }
                    if (args.length < 3) {
                        err("bp placa pack: ¿cuál? (un .pack, o uno de la instalación: "
                                + String.join(", ", PacksPlaca.losDeLaInstalacion()) + ")");
                        return 1;
                    }
                    File f = new File(args[2]);
                    if (!f.isFile()) f = PacksPlaca.deLaInstalacion(args[2]);
                    if (f == null) {
                        err("bp placa pack: no existe el fichero «" + args[2] + "» ni hay un pack con ese nombre en la"
                                + " instalación (" + String.join(", ", PacksPlaca.losDeLaInstalacion()) + ")");
                        return 1;
                    }
                    final int[] dicho = { -10 };
                    PacksPlaca.Grabado g = PacksPlaca.graba(dc, f, Despliegue.Perfil.NINGUNO, PlacaCli::err, (hechos, total) -> {
                        int pct = total > 0 ? (int) ((hechos * 100L) / total) : 100;
                        if (pct < dicho[0] + 10) return;
                        dicho[0] = pct;
                        err("[Packs] grabando " + args[2] + "… " + pct + " %");
                    });
                    System.out.println("grabado: " + g.nombre + " (" + g.bytes + " bytes"
                            + (g.delFichero != g.bytes ? ", podado de " + g.delFichero + " para esta placa" : "") + ")");
                    for (BpvmClient.PackInfo pi : g.habia) {
                        err("[Packs] ya había otro '" + pi.name + "' activo (del " + PacksPlaca.fecha(pi.date) + "): manda el"
                                + " que se acaba de grabar; el viejo se retira desde el IDE");
                    }
                    if (dc.reiniciaTrasPack()) {
                        err("[Packs] la placa tenía motores cargados y se reinicia para cargar los de la zona nueva");
                    }
                    return 0;
                }
                default:
                    err("bp placa: no conozco «" + orden + "» (ls, get, stop, info, packs o pack)");
                    return 1;
            }
        } catch (IOException ex) {
            err("bp placa " + destino + ": " + ex.getMessage());
            return 1;
        }
    }
}
