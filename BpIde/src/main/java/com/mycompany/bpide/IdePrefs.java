// ============================================================
// IdePrefs.java
// Preferencias persistentes del IDE. Hoy contiene endpoint de la
// VM (host:port) y última carpeta abierta; está pensado para
// crecer: layout de paneles, último proyecto, etc.
//
// El fichero se guarda en el HOME del usuario (System property
// user.home) como `.bpide-prefs`. En Windows típicamente
// C:\Users\<nombre>\.bpide-prefs; en Linux/Mac ~/.bpide-prefs.
// Antes vivía en el CWD del proceso, lo cual fallaba si se
// lanzaba el jar desde un directorio sin permisos de escritura
// (Program Files, etc.). Si existe el legacy en CWD, lo migra
// transparente al HOME y elimina el viejo.
//
// Errores de lectura/escritura se loggean a stderr con la ruta
// absoluta para diagnóstico, y nunca rompen el flujo del IDE.
// ============================================================
package com.mycompany.bpide;

import edu.bpgenvm.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preferencias persistentes del IDE. */
public final class IdePrefs {

    /** Endpoint de la VM remota. Null/empty = spawn local (default histórico).
     *  Si está fijado, doRun/doDebug se conectan directamente a host:port en
     *  vez de lanzar un subproceso local. */
    public String vmHost;
    public int    vmPort;

    /** V8/H3 — la última dirección de la placa por la red: la que se escribió, o la que dijo el INFO por el cable. */
    public String placaRed;

    /** V9 H2.3 — el servidor para la IA (MCP): su puerto, en 127.0.0.1. */
    public int    iaPuerto = 7340;
    /** V9 H2.3 — la carpeta que puede tocar la IA (la que se eligió al encenderlo la última vez; Eduardo: «La que elijo
     *  al encenderlo»). Null/empty = sin elegir aún. */
    public String iaCarpeta;

    /** Última carpeta donde se abrió o guardó un fichero .bp.
     *  El JFileChooser arranca aquí en vez de en Documents para que el
     *  usuario no tenga que navegar de nuevo cada vez. */
    public String lastDir;

    /** IDE-4 — Última carpeta usada en el "Upload…" del PicoExplorer. Separada
     *  de lastDir (Load/Save de .bp) porque suelen ser carpetas distintas: los
     *  .mod compilados vs los .bp fuente. */
    public String lastUploadDir;

    /** AOT (H12) — ruta del compilador ARM (arm-none-eabi-gcc) en ESTA máquina.
     *  Null/empty = autodetect (PATH + ubicación estándar del instalador Arm).
     *  Per-máquina: el toolchain se instala en sitios distintos en cada PC, por
     *  eso va en prefs y no en el .bpbuild del proyecto (que es portable). */
    public String aotGccPath;
    /** AOT (H12) — raíz de bpgenvm-c en ESTA máquina, para los includes que gcc
     *  necesita al compilar aot_&lt;Mod&gt;.c (-I &lt;dir&gt;/include -I &lt;dir&gt;/src).
     *  Null/empty = autodetect. */
    public String aotBpgenvmDir;

    /** H10.4 — Carpeta de PACKS que acompaña al IDE (la stdlib empaquetada, el pack
     *  de Gui, y en el futuro SQLite...). El compilador la mira al resolver
     *  `import X from pack Y`, y de aquí salen los packs que se graban en la placa.
     *  Null/empty = autodetect (junto al jar / al repo). */
    public String packsDir;

    /** ── H10: el MICRO SIMULADO (bpvm-sim) ─────────────────────────────────
     *  Es una placa de mentira que corre en el PC: el IDE se conecta a ella por
     *  TCP igual que a una de verdad. Estos son los mimbres del "silicio" que se
     *  configuran desde la ventana del engranaje; el ejecutable se localiza bajo
     *  {@link #aotBpgenvmDir} (build/bpvm-sim.exe) porque es la MISMA raíz de
     *  bpgenvm-c que ya se configura para el AOT — una ruta, no dos. */
    public int     simPort     = 5099;
    public int     simRamKb    = 512;
    public int     simPsramKb  = 0;
    public int     simFlashKb  = 4096;
    public int     simScreenW  = 480;
    public int     simScreenH  = 320;
    public boolean simNoScreen = false;
    /** Dónde viven la "flash" y la imagen del FS del micro simulado (persisten
     *  entre arranques, como en una placa). Null/empty = $HOME/.bpide-sim. */
    public String  simDataDir;

    /** Directorio de la INSTALACIÓN: el que contiene el jar en ejecución. En la
     *  distribución (el ZIP) ahí cuelgan `packs/`, `bin/`, `bpgenvm-c/`… y por eso
     *  todo lo que "viaja con el IDE" se busca relativo a este punto y no a rutas
     *  absolutas de ninguna máquina.
     *
     *  <p>null si no se puede averiguar (jar servido desde una URL rara, sin
     *  permisos, o clases sueltas en un IDE): quien llame debe tener otro
     *  candidato detrás. */
    public static Path installDir() {
        try {
            java.net.URI uri = IdePrefs.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path here = Paths.get(uri);
            return Files.isDirectory(here) ? here : here.getParent();
        } catch (Exception ignored) {
            return null;   // el llamante tiene su propio fallback
        }
    }

    /** Igual que {@link #installDir()} pero devolviendo la subcarpeta `sub` sólo
     *  si existe. Azúcar para los tres sitios que buscan cosas de la instalación. */
    static Path installSubdir(String sub) {
        Path root = installDir();
        if (root == null) return null;
        Path cand = root.resolve(sub);
        return Files.isDirectory(cand) ? cand : null;
    }

    /** V9 #867 — el BpVM.cfg de la INSTALACIÓN del IDE: junto al jar (el ZIP lo trae en su raíz) o subiendo desde él
     *  (en el repo, el jar vive en toolchain/BpIde/target y el cfg en la raíz). Si no aparece ninguno, uno con las
     *  carpetas que viajan junto al jar (bpstdlib/, bpdevices/); null si tampoco están. Es el respaldo de cuando un
     *  fuente no tiene BpVM.cfg, ni subiendo desde él, y el IDE se lanzó desde una carpeta sin él: un programa de la
     *  carpeta de la IA, por ejemplo, no encontraba ni {@code Core}. Se busca con sus propios pasos, no con
     *  {@code loadDefaultFor}, que mira antes el cwd. */
    static edu.bpgenvm.config.VmConfig cfgDeLaInstalacion() {
        for (Path d = installDir(); d != null; d = d.getParent()) {
            Path f = d.resolve(edu.bpgenvm.config.VmConfig.DEFAULT_FILENAME);
            if (Files.isRegularFile(f)) {
                try { return edu.bpgenvm.config.VmConfig.load(f); }
                catch (IOException ex) {
                    System.err.println("Advertencia: no se pudo leer " + f + ": " + ex.getMessage());
                    break;   // existe pero ilegible: como loadDefaultFor, no se sigue subiendo
                }
            }
        }
        Path lib = installSubdir("bpstdlib");
        if (lib == null) return null;
        edu.bpgenvm.config.VmConfig c = edu.bpgenvm.config.VmConfig.defaults();
        c.stdlibDir = lib.toString();
        Path dev = installSubdir("bpdevices");
        if (dev != null) c.devicesDir = dev.toString();
        return c;
    }

    /** V9 #867 — el BpVM.cfg que manda para {@code ancla} (un fuente, una carpeta de salida; null = sólo el cwd): el de
     *  siempre ({@code loadDefaultFor}: el cwd, o subiendo desde el ancla), y si no aparece ninguno, el de la
     *  instalación del IDE. Lo mismo que hace el compilador (Main.setCfgDelHospedador): la stdlib con la que se compila
     *  y la que se le da a la VM, o la que se sube a la placa, son la misma. Nunca null. */
    static edu.bpgenvm.config.VmConfig cfgPara(Path ancla) {
        edu.bpgenvm.config.VmConfig cfg = edu.bpgenvm.config.VmConfig.loadDefaultFor(ancla);
        if (cfg.sourcePath != null) return cfg;
        edu.bpgenvm.config.VmConfig suyo = cfgDeLaInstalacion();
        return suyo != null ? suyo : cfg;
    }

    /** #363 — la carpeta de documentación: los CINCO volúmenes + sus imágenes.
     *
     *  <p>Viven en la instalación (`docs/` junto al jar), no dentro del jar. Antes
     *  se empotraban dos de los cinco y se extraían a un temporal: eso dejaba diez
     *  enlaces rotos —el lector pinchaba "Gráficos" o "Guía del IDE" y no había
     *  nada— y además obligaba a copiar a mano los HTML a `src/main/resources` en
     *  cada cambio, con lo fácil que es olvidarse y publicar documentación rancia.
     *  Empotrar los cinco CON sus imágenes serían 900 KB sobre un jar de 4,2 (+21%)
     *  para tener una segunda copia de algo que el ZIP ya trae.
     *
     *  <p>Dos candidatos, en este orden y sin adivinar nada más:
     *  <ol><li>`docs/` junto al jar — la INSTALACIÓN, que es el caso del usuario;
     *      <li>`../../../docs` — el repo, cuando se ejecuta desde `toolchain/BpIde/target/`
     *          durante el desarrollo.</ol>
     *  null si no está en ninguno: quien llame manda al usuario a la web. */
    static Path docsDir() {
        Path junto = installSubdir("docs");
        if (junto != null) return junto;
        Path root = installDir();
        if (root == null) return null;
        Path repo = root.resolve("..").resolve("..").resolve("..").resolve("docs").normalize();   // V8.1: toolchain/BpIde/target
        return Files.isDirectory(repo) ? repo : null;
    }

    /** Biblioteca de packs efectiva: la configurada; si no, la que VIAJA CON EL IDE
     *  (una carpeta `packs/` junto al jar) y por último `packs/` en el directorio
     *  de trabajo (útil en el repo). null si no hay ninguna — el compilador dirá
     *  entonces dónde configurarla. */
    public String packsDirEffective() {
        if (packsDir != null && !packsDir.isEmpty()) return packsDir;
        Path junto = installSubdir("packs");
        if (junto != null) return junto.toString();
        Path cwd = Paths.get(System.getProperty("user.dir", "."), "packs");
        return Files.isDirectory(cwd) ? cwd.toString() : null;
    }

    /** Carpeta de datos efectiva del simulador (nunca null). */
    public String simDataDirEffective() {
        if (simDataDir != null && !simDataDir.isEmpty()) return simDataDir;
        String home = System.getProperty("user.home");
        return Paths.get(home != null ? home : ".", ".bpide-sim").toString();
    }

    /** IDE-3 — Recientes: ficheros .bp abiertos y proyectos .bpbuild abiertos.
     *  Más-reciente-primero, sin duplicados, cap MAX_RECENT. Se persisten como
     *  un string con entradas separadas por '\n' (los paths no llevan newline). */
    public java.util.List<String> recentFiles    = new java.util.ArrayList<>();
    public java.util.List<String> recentProjects = new java.util.ArrayList<>();
    public static final int MAX_RECENT = 8;

    /** Inserta `path` al frente de `list` (dedup + cap a `max`). */
    public static void pushRecent(java.util.List<String> list, String path, int max) {
        if (path == null || path.isEmpty()) return;
        list.remove(path);
        list.add(0, path);
        while (list.size() > max) list.remove(list.size() - 1);
    }

    /** Parte un string "a\nb\nc" en lista (ignora vacíos). Inverso de String.join. */
    private static java.util.List<String> splitRecent(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static final String FILENAME = ".bpide-prefs";

    /** Ruta canónica del fichero de prefs: $HOME/.bpide-prefs.
     *  Si user.home no está disponible (improbable en JVM normal),
     *  fallback al cwd para no romper en entornos exóticos. */
    private static Path prefsPath() {
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            return Paths.get(home, FILENAME);
        }
        return Paths.get(FILENAME);   /* fallback legacy */
    }

    /** Si existe un .bpide-prefs en CWD (legacy de versiones
     *  anteriores), lo migra al HOME y borra el original. Llamado
     *  desde load() para que la primera vez tras el upgrade el
     *  usuario no pierda sus prefs. */
    private static void migrateLegacyIfAny() {
        Path legacy = Paths.get(FILENAME);
        Path canonical = prefsPath();
        if (legacy.equals(canonical)) return;     /* mismo sitio, nada que hacer */
        if (!Files.isRegularFile(legacy)) return;
        if (Files.isRegularFile(canonical)) {
            /* Ambos existen: el canónico gana, borramos el legacy. */
            try { Files.delete(legacy); } catch (IOException ignored) {}
            return;
        }
        try {
            Files.copy(legacy, canonical);
            Files.delete(legacy);
        } catch (IOException ignored) {
            /* Si no podemos mover, dejamos las prefs viejas donde estaban
             * — el load del cwd las recuperará como fallback. */
        }
    }

    /** Lee las prefs. Devuelve una instancia vacía si no existe el
     *  fichero o si hay error de lectura — nunca lanza. */
    public static IdePrefs load() {
        migrateLegacyIfAny();
        IdePrefs p = new IdePrefs();
        Path f = prefsPath();
        /* Fallback: si no hay en HOME pero sí en CWD (migración
         * fallida o entorno raro), leer del CWD. */
        if (!Files.isRegularFile(f)) f = Paths.get(FILENAME);
        if (!Files.isRegularFile(f)) return p;
        try {
            String raw = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Map<String, Object> m = Json.parseFlatObject(raw);
            p.vmHost  = Json.getString(m, "vmHost", null);
            p.vmPort  = (int) Json.getLong(m, "vmPort", 0);
            p.placaRed = Json.getString(m, "placaRed", null);
            p.iaPuerto  = (int) Json.getLong(m, "iaPuerto", p.iaPuerto);   // V9 H2.3
            p.iaCarpeta = Json.getString(m, "iaCarpeta", null);
            if (p.iaCarpeta != null && p.iaCarpeta.isEmpty()) p.iaCarpeta = null;
            p.lastDir = Json.getString(m, "lastDir", null);
            p.lastUploadDir = Json.getString(m, "lastUploadDir", null);
            p.aotGccPath = Json.getString(m, "aotGccPath", null);
            p.aotBpgenvmDir = Json.getString(m, "aotBpgenvmDir", null);
            p.packsDir   = Json.getString(m, "packsDir", null);
            p.simDataDir = Json.getString(m, "simDataDir", null);
            /* Los tamaños del micro simulado: si la clave falta (prefs de una
             * versión anterior) se queda el default del campo, no un 0. */
            p.simPort     = (int) Json.getLong(m, "simPort",     p.simPort);
            p.simRamKb    = (int) Json.getLong(m, "simRamKb",    p.simRamKb);
            p.simPsramKb  = (int) Json.getLong(m, "simPsramKb",  p.simPsramKb);
            p.simFlashKb  = (int) Json.getLong(m, "simFlashKb",  p.simFlashKb);
            p.simScreenW  = (int) Json.getLong(m, "simScreenW",  p.simScreenW);
            p.simScreenH  = (int) Json.getLong(m, "simScreenH",  p.simScreenH);
            p.simNoScreen = Json.getLong(m, "simNoScreen", 0) != 0;
            if (p.vmHost  != null && p.vmHost.isEmpty())  p.vmHost  = null;
            if (p.lastDir != null && p.lastDir.isEmpty()) p.lastDir = null;
            if (p.lastUploadDir != null && p.lastUploadDir.isEmpty()) p.lastUploadDir = null;
            if (p.aotGccPath != null && p.aotGccPath.isEmpty()) p.aotGccPath = null;
            if (p.aotBpgenvmDir != null && p.aotBpgenvmDir.isEmpty()) p.aotBpgenvmDir = null;
            if (p.packsDir   != null && p.packsDir.isEmpty())   p.packsDir   = null;
            if (p.simDataDir != null && p.simDataDir.isEmpty()) p.simDataDir = null;
            p.recentFiles    = splitRecent(Json.getString(m, "recentFiles", null));
            p.recentProjects = splitRecent(Json.getString(m, "recentProjects", null));
        } catch (Throwable t) {
            System.err.println("[IdePrefs] no se pudo leer " + f.toAbsolutePath()
                    + ": " + t.getMessage());
        }
        return p;
    }

    /** Persiste al HOME del usuario. Errores se loggean con la ruta
     *  absoluta para diagnóstico — nunca lanzan. */
    public void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vmHost",  vmHost  == null ? "" : vmHost);
        m.put("vmPort",  (long) vmPort);
        m.put("placaRed", placaRed == null ? "" : placaRed);
        m.put("iaPuerto", (long) iaPuerto);                       // V9 H2.3
        m.put("iaCarpeta", iaCarpeta == null ? "" : iaCarpeta);
        m.put("lastDir", lastDir == null ? "" : lastDir);
        m.put("lastUploadDir", lastUploadDir == null ? "" : lastUploadDir);
        m.put("aotGccPath", aotGccPath == null ? "" : aotGccPath);
        m.put("aotBpgenvmDir", aotBpgenvmDir == null ? "" : aotBpgenvmDir);
        m.put("packsDir",   packsDir   == null ? "" : packsDir);
        m.put("simDataDir", simDataDir == null ? "" : simDataDir);
        m.put("simPort",     (long) simPort);
        m.put("simRamKb",    (long) simRamKb);
        m.put("simPsramKb",  (long) simPsramKb);
        m.put("simFlashKb",  (long) simFlashKb);
        m.put("simScreenW",  (long) simScreenW);
        m.put("simScreenH",  (long) simScreenH);
        m.put("simNoScreen", simNoScreen ? 1L : 0L);
        m.put("recentFiles",    String.join("\n", recentFiles));
        m.put("recentProjects", String.join("\n", recentProjects));
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(Json.escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof String) sb.append('"').append(Json.escape((String) v)).append('"');
            else                     sb.append(v.toString());
        }
        sb.append('}');
        Path f = prefsPath();
        try {
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioe) {
            System.err.println("[IdePrefs] no se pudo escribir " + f.toAbsolutePath()
                    + ": " + ioe.getMessage());
        }
    }
}
