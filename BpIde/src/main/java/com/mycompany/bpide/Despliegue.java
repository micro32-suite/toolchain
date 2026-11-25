// ============================================================
// Despliegue.java
// V9 #857 — SUBIR A LA PLACA LO QUE HACE FALTA PARA EJECUTAR UN .mod, sin ventana.
//
// Es lo que hacía PicoExplorer.uploadAndRun antes de ejecutar (sus pasos 0 a
// 2b-bis: el LIST, las dependencias con su STAT y la regla de #466/#624, sus
// .mdn, los recursos, el módulo principal y su .mdn, y los huérfanos de un
// proyecto), sacado TAL CUAL a una clase que usan el IDE y `bp` (Eduardo,
// 23-nov: «Sacarla a una clase común»: un sitio, la lección de #463, cuando el
// IDE llevaba un gemelo del orden de búsqueda y se desincronizó). Y lo que el
// IDE preparaba antes de llamarla (FrmMain): las dependencias que carga la VM,
// cuáles son de la stdlib, la carpeta del proyecto en la placa y sus recursos.
//
// Lo de la ventana se queda en PicoExplorer: el árbol que se refresca tras
// subir, la barra de estado, el depurador y la ejecución. Las líneas de la
// consola («[Explorer] …», sin el salto) salen por `consola`: el IDE las pinta
// en la suya (en el hilo de la ventana), `bp` en la salida de errores.
// ============================================================
package com.mycompany.bpide;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class Despliegue {

    /** El perfil de la placa, del INFO: para saber si un .mdn le sirve (su arquitectura, la receta de compilación y la
     *  ABI de helpers). 0 / "" si el firmware no lo dice: entonces no se compara (un firmware viejo). */
    public static final class Perfil {
        public final int arch;
        public final long flagsCrc;
        public final String receta;
        public final int mdnAbi;

        public Perfil(int arch, long flagsCrc, String receta, int mdnAbi) {
            this.arch = arch;
            this.flagsCrc = flagsCrc;
            this.receta = receta == null ? "" : receta;
            this.mdnAbi = mdnAbi;
        }

        public static final Perfil NINGUNO = new Perfil(0, 0, "", 0);

        /** Del INFO de la placa: los campos arch, aotFlagsCrc, aotReceta y mdnAbi (los que guardaba PicoExplorer). */
        public static Perfil delInfo(Map<String, Object> m) {
            if (m == null) return NINGUNO;
            return new Perfil((int) num(m, "arch"), num(m, "aotFlagsCrc") & 0xFFFFFFFFL, texto(m, "aotReceta"),
                    (int) num(m, "mdnAbi"));
        }

        private static long num(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return (v instanceof Number) ? ((Number) v).longValue() : 0L;
        }

        private static String texto(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return v == null ? "" : v.toString();
        }
    }

    /** N-skip-put-by-hash: CRC32 de los bytes que subimos a cada ruta remota
     *  en ESTA conexión. El skip-if-same-size (#110) servía .mod rancios
     *  cuando una edición no cambiaba el tamaño; ahora el skip se confirma
     *  por contenido. Se vacía al (re)conectar ({@link #olvida()}). */
    private final Map<String, Long> sentCrc = new ConcurrentHashMap<>();

    /** paso 4 cierre — CRC32 REAL de cada fichero del device, leído del LS al
     *  empezar cada subida (los firmwares nuevos lo reportan; los viejos no →
     *  el mapa no tiene la entrada y putIfChanged cae al fallback tamaño+sentCrc).
     *  Es la verdad-de-terreno: salta el PUT sólo si el contenido del device
     *  coincide con el local, sin depender de la memoria de sesión. */
    private volatile Map<String, Long> deviceCrcByPath = Collections.emptyMap();

    /** Una conexión nueva: lo que se subió en la anterior ya no cuenta. */
    public void olvida() {
        sentCrc.clear();
    }

    /**
     * Sube a la placa lo que hace falta para ejecutar {@code modFile}: sus dependencias (por cada una SE PREGUNTA A LA
     * PLACA si ya la tiene, #466; una librería, con la regla de #624), los .mdn al lado, los recursos, el principal y
     * su .mdn, y en modo proyecto ({@code prefix} distinto de "/app") borra los huérfanos. Lo que hace, en
     * {@code consola} («[Explorer] …»). Devuelve la ruta del principal en la placa.
     *
     * @param dc el cliente del cable de la conexión (el STAT por nombre y el CRC de un fichero); null: no se pregunta
     */
    public String sube(Backend b, BpvmClient dc, Perfil perfil, File modFile, List<File> depMods, Set<String> libDepNames,
                       Map<String, File> resourceFiles, String appPrefix, Consumer<String> consola) throws IOException {
        final Perfil p = (perfil != null) ? perfil : Perfil.NINGUNO;
        final String prefix = (appPrefix == null || appPrefix.isEmpty()) ? "/app" : appPrefix;
        final boolean projectMode = !"/app".equals(prefix);   // F2: huérfanos sólo con proyecto
        final String remoteName = appPath(prefix, modFile.getName());
        final List<File> deps = (depMods != null) ? depMods : Collections.emptyList();
        final Set<String> libNames = (libDepNames != null) ? libDepNames : Collections.emptySet();
        final Map<String, File> resources = (resourceFiles != null) ? resourceFiles : Collections.emptyMap();

        // 0) Sondea LS para saber qué hay ya en el FS remoto (tamaño + CRC real).
        Map<String, Long> remote = new HashMap<>();
        Map<String, Long> remoteCrc = new HashMap<>();
        try {
            for (Backend.Entry rf : b.list()) {
                remote.put(rf.name, rf.size);
                if (rf.crc >= 0) remoteCrc.put(rf.name, rf.crc);   // paso 4: -1 = firmware sin crc
            }
        } catch (IOException lsErr) {
            consola.accept("[Explorer] LIST falló, subiré todo: " + lsErr.getMessage());
        }
        this.deviceCrcByPath = remoteCrc;   // putIfChanged lo consulta por ruta

        // F2 (H19) — claves bajo el prefijo del proyecto que ESTE run deja
        // presentes (subidas O saltadas por CRC). Al final borramos del
        // device las claves bajo prefix/ que NO estén aquí (huérfanos).
        final Set<String> deployed = new HashSet<>();
        deployed.add(remoteName);

        // 1) Subir deps primero (drivers).
        //    skip-if-IDÉNTICO (tamaño + CRC32, vía putIfChanged) +
        //    DEL-before-overwrite por economía (evita reescritura de
        //    flash innecesaria en Pico).
        for (File dep : deps) {
            // #466 — la regla de Eduardo: por cada dependencia SE PREGUNTA A LA
            // PLACA si ya la tiene, donde sea (STAT por nombre: lo resuelve el
            // device con el orden del RUN). Se sube sólo si no la tiene, si la
            // suya es de versión (MAGIC) anterior, o si es la misma versión con
            // CRC distinto; si la placa la tiene MÁS NUEVA, no se sube.
            // La versión de la stdlib la resuelve el SO —la imagen repone su
            // /lib al arrancar—, no esta comunicación: por eso ya no hay aquí
            // una lista de «módulos embebidos» ni se «corrige» /lib por CRC.
            // (Lo que motivó aquel CRC el 13-jun —las vtables de un blob viejo—
            // lo cubre la regla: misma versión y CRC distinto → se sube.)
            final boolean isLib = libNames.contains(dep.getName());   // viene de la stdlib
            final BpvmClient.ModStat st = (dc != null) ? dc.statModule(dep.getName(), prefix, 8000) : null;
            final byte[] depBytes = Files.readAllBytes(dep.toPath());
            final int vLocal = BpvmClient.ModStat.versionOf(magicOf(depBytes));
            // V6/E1 — DE DONDE SALIO EL MODULO. La ruta la resuelve el DEVICE con su
            // propio resolvedor (STAT por nombre); el IDE no lleva un gemelo del orden de
            // busqueda — asi se desincronizo #463. Aqui solo comparamos lo que el device
            // contesta con el sitio donde el IDE lo habria puesto: si no coinciden, la
            // placa esta cargando OTRA copia, y eso hay que decirlo en voz alta.
            final String donde_le_tocaria = isLib ? ("/lib/" + dep.getName()) : appPath(prefix, dep.getName());
            final String depRemote; final String motivo; final boolean subir;
            if (st == null) {
                // No lo tiene (o firmware sin STAT por nombre): a donde le toca.
                depRemote = isLib ? ("/lib/" + dep.getName()) : appPath(prefix, dep.getName());
                /* V7 #623 (8-oct): un pack librería ya no lleva la stdlib; lo que la placa no tiene se sube a /lib, que
                 * se carga en la RAM (en la C6 no cabe la GUI). Con el Stdlib.pack grabado no haría falta: XIP. */
                motivo = isLib ? "la placa no lo tiene (a /lib, que se carga en la RAM; con el Stdlib.pack grabado"
                                 + " correria XIP desde el)" : "la placa no lo tiene";
                subir = true;
            } else if (isLib) {
                /* V7/H9 #624 — una LIBRERÍA: la regla de Eduardo (2-oct, la de Java), en ReglaDeSubida: el formato
                 * (MAGIC) compatible y la versión de la placa igual o superior a la mínima —la del .mod contra el
                 * que se compiló el programa—. El CRC sólo dice «idéntico»: otra compilación de la misma versión
                 * VALE y no se sube (antes sí, y a /lib delante del pack, que va a RAM: la C6 sin memoria, #623).
                 * Si hay que subir: donde está, o delante del pack (/lib), que va antes en la resolución. */
                final basicplus.frontend.ReglaDeSubida.Decision dd = basicplus.frontend.ReglaDeSubida.decidir(
                        st.magic, st.gen, st.crc,
                        basicplus.frontend.ReglaDeSubida.formatoDe(depBytes),
                        basicplus.frontend.ReglaDeSubida.versionDe(depBytes), crc32(depBytes));
                subir = dd.subir;
                final boolean enPack = st.path.startsWith("pack:");
                depRemote = (subir && enPack) ? donde_le_tocaria : st.path;
                motivo = (subir && enPack) ? dd.motivo + ": se sube delante del pack" : dd.motivo;
            } else if (st.path.startsWith("pack:")) {
                /* [V7/H2, #493] Lo tiene UN PACK GRABADO (la placa lo ejecuta XIP
                 * desde ahí). Idéntico → no se sube nada. Distinto → se sube a
                 * donde le toca (/lib o la app), que va DELANTE del pack en la
                 * resolución; el pack no se toca (no hay DEL de un pack). */
                if (st.crc >= 0 && st.crc == crc32(depBytes)) {
                    depRemote = st.path; motivo = "ya en la placa, en un pack (XIP), idéntico"; subir = false;
                } else {
                    depRemote = donde_le_tocaria;
                    motivo = "un pack lo tiene distinto: se sube delante del pack"; subir = true;
                }
            } else {
                depRemote = st.path;                         // se reemplaza DONDE está
                final int vDev = st.version();
                if (vDev > vLocal) {
                    motivo = "la placa lo tiene MÁS NUEVO (MOD" + vDev + " > MOD" + vLocal + ")";
                    subir = false;
                } else if (vDev < vLocal) {
                    motivo = "la placa lo tiene de versión anterior (MOD" + vDev + " < MOD" + vLocal + ")";
                    subir = true;
                } else if (st.crc >= 0 && st.crc == crc32(depBytes)) {
                    motivo = "ya en la placa, idéntico"; subir = false;
                } else {
                    motivo = "misma versión, CRC distinto"; subir = true;
                }
            }
            if (depRemote.startsWith(prefix + "/")) deployed.add(depRemote);   // F2: bajo el prefijo
            if (subir) {
                if (st != null && !st.path.startsWith("pack:")) {   // DEL-before-PUT: economía de flash (#111)
                    try { b.del(depRemote); } catch (IOException delErr) { /* tolerable */ }
                }
                b.put(depRemote, depBytes);
                sentCrc.put(depRemote, crc32(depBytes));
            }
            // V6/E1 — el CRC y la RUTA en el mensaje: cuando algo falla por una
            // dependencia, esta linea es la que dice de donde salio el modulo.
            final String donde = (st == null) ? "" : "  [la placa lo tiene en " + st.path
                    + (st.crc >= 0 ? String.format(", crc %08X", st.crc) : "") + "]";
            final String ojo = (st != null && !st.path.equals(donde_le_tocaria) && !st.path.startsWith("pack:"))
                    ? "  ⚠ OJO: no es donde este proyecto lo pondria (" + donde_le_tocaria
                      + ") — la placa carga esa OTRA copia"
                    : "";
            consola.accept(subir
                    ? "[Explorer] " + dep.getName() + " → " + depRemote + " ("
                      + depBytes.length + " bytes): " + motivo + donde + ojo
                    : "[Explorer] " + dep.getName() + ": " + motivo + donde + ojo
                      + " — no se sube");
            // Si el dep tiene .mdn alongside, subirlo también.
            final File depMdn = mdnSiblingOf(dep);
            boolean depMdnStale = depMdn != null && depMdn.isFile() && mdnIsStale(depMdn, dep, p);
            if (depMdnStale) {
                consola.accept("[Explorer] AOT " + depMdn.getName() + " " + motivoMdnRancio(depMdn, dep, p)
                        + " — NO se sube (regenéralo activando AOT en el proyecto)");
            }
            if (depMdn != null && depMdn.isFile() && !depMdnStale) {
                String depMdnRemote = isLib ? toAppPath(depMdn.getName()) : appPath(prefix, depMdn.getName());
                if (!isLib) deployed.add(depMdnRemote);   // F2: junto a su .mod
                boolean upMdn = putIfChanged(b, dc, depMdn, depMdnRemote, remote.get(depMdnRemote), consola);
                if (upMdn) {
                    consola.accept("[Explorer] subido AOT " + depMdnRemote + " (" + depMdn.length() + " bytes)");
                }
            }
        }
        // 1c) H12 (#260) — resources/ del proyecto: ficheros arbitrarios a
        //     su ruta remota (/app/<rel>), con skip-if-idéntico.
        for (Map.Entry<String, File> res : resources.entrySet()) {
            final String rRemote = res.getKey();
            deployed.add(rRemote);   // F2: ya viene con el prefijo del proyecto
            final File rFile = res.getValue();
            boolean up = putIfChanged(b, dc, rFile, rRemote, remote.get(rRemote), consola);
            if (up) {
                consola.accept("[Explorer] subido resource " + rRemote + " (" + rFile.length() + " bytes)");
            } else {
                consola.accept("[Explorer] " + rRemote + " ya en FS (" + rFile.length()
                        + " bytes, contenido idéntico), salto PUT");
            }
        }
        // 2) Subir el módulo principal solo si su CONTENIDO difiere.
        boolean upMain = putIfChanged(b, dc, modFile, remoteName, remote.get(remoteName), consola);
        if (!upMain) {
            consola.accept("[Explorer] " + remoteName + " ya en FS (" + modFile.length()
                    + " bytes, contenido idéntico), salto PUT");
        }
        // 2b) Si hay .mdn alongside del .mod, subirlo también (H3 #158
        //     fase D). El firmware al hacer RUN escanea el FS por
        //     <mod>.mdn y registra los thunks AOT zero-copy. Si el
        //     .mdn no existe localmente, sin problema — BP corre
        //     interpretado normal.
        final File mdnFile = mdnSiblingOf(modFile);
        boolean mdnStale = mdnFile != null && mdnFile.isFile() && mdnIsStale(mdnFile, modFile, p);
        if (mdnStale) {
            consola.accept("[Explorer] AOT " + mdnFile.getName() + " " + motivoMdnRancio(mdnFile, modFile, p)
                    + " — NO se sube (regenéralo activando AOT en el proyecto)");
        }
        if (mdnFile != null && mdnFile.isFile() && !mdnStale) {
            String mdnRemote = appPath(prefix, mdnFile.getName());
            deployed.add(mdnRemote);   // F2
            boolean up = putIfChanged(b, dc, mdnFile, mdnRemote, remote.get(mdnRemote), consola);
            if (up) {
                consola.accept("[Explorer] subido AOT " + mdnRemote + " (" + mdnFile.length() + " bytes)");
            }
        } else if (modFile.getName().toLowerCase().endsWith(".mod")) {   // V9 #899: de un .pack, el nombre era el suyo, y se borraba
            /* [25-ago-2026] La app ya NO trae `.mdn` suelto (el nativo va
             * DENTRO del `.mod` desde N1.4, y AotBuild dejó de escribirlo).
             * Pero el de la TANDA ANTERIOR sigue en el device, y no es
             * inofensivo: el barrido del RUN lo registra DESPUÉS del bloque
             * embebido, así que un `.mdn` rancio PISARÍA a los thunks
             * frescos — la clase de fallo que la fusión vino a matar.
             * Se retira aquí, best-effort, como los huérfanos de proyecto. */
            String mdnRemote = appPath(prefix, modFile.getName().replaceFirst("[.]mod$", ".mdn"));
            if (remote.containsKey(mdnRemote)) {
                try {
                    b.del(mdnRemote);
                    consola.accept("[Explorer] AOT retirado del device: " + mdnRemote
                            + " (el nativo va dentro del .mod)");
                } catch (IOException delErr) {
                    /* best-effort: si falla el DEL, seguimos */
                }
            }
        }
        // 2b-bis) F2 (H19) — huérfanos: en modo proyecto, borra de prefix/
        //   las claves que el device tiene pero este run ya no despliega
        //   (módulos/resources quitados del proyecto). El prefijo ES el
        //   manifest (FS plano enumerado por LS); nunca toca /lib ni otros
        //   /app/<otro>/.
        if (projectMode) {
            final String pfx = prefix + "/";
            for (String key : new ArrayList<>(remote.keySet())) {
                if (key.startsWith(pfx) && !deployed.contains(key)) {
                    try {
                        b.del(key);
                        consola.accept("[Explorer] huérfano borrado: " + key);
                    } catch (IOException delErr) {
                        /* best-effort: si falla el DEL, seguimos */
                    }
                }
            }
        }
        return remoteName;
    }

    /** Sube `file` a `remote` salvo que YA esté allí con el MISMO contenido.
     *  paso 4 cierre — verdad-de-terreno: si el firmware reportó el CRC del
     *  fichero en el LS (deviceCrcByPath), saltamos el PUT sólo si coincide con
     *  el CRC local → detecta rancios de CUALQUIER fuente, no solo de lo que
     *  subió esta sesión. Sin crc del device (firmware viejo) cae al heurístico
     *  anterior (#110/#111: tamaño + sentCrc). DEL-before-PUT se conserva (#111).
     *  @return true si (re)subió; false si lo saltó. */
    private boolean putIfChanged(Backend b, BpvmClient dc, File file, String remote, Long deviceSize,
                                 Consumer<String> consola) throws IOException {
        if (remote.length() > 39) {   // F2: FS_NAME_LEN=40 (39 chars + NUL)
            consola.accept("[Explorer] AVISO: ruta " + remote.length() + " chars > 39 (FS_NAME_LEN) — "
                    + "el device puede truncarla/rechazarla: " + remote);
        }
        byte[] data = Files.readAllBytes(file.toPath());
        long crc = crc32(data);
        Long devCrc = deviceCrcByPath.get(remote);
        /* #398 — si el listado no trajo CRC (que ya es lo normal: calcularlo
         * para todo el FS en cada refresco costaba el 99 % del tiempo del
         * árbol), se pide AQUÍ el de ESTE fichero. Es una pregunta por fichero
         * a subir en vez de una lectura del FS entero por refresco, y conserva
         * la propiedad que hacía valioso el CRC: detecta un rancio venga de
         * donde venga, no sólo de lo que subió esta sesión. Sólo si el device
         * dice que el fichero ya está (deviceSize != null): preguntar por uno
         * que no existe es un viaje para que nos digan NOT_FOUND. */
        if (devCrc == null && deviceSize != null) {
            if (dc != null) {
                long c = dc.fileCrc(remote, 8000);
                if (c >= 0) devCrc = Long.valueOf(c);
            }
        }
        if (devCrc != null) {
            if (devCrc.longValue() == crc) return false;   // contenido REAL del device == local
        } else {
            // Fallback (firmware sin crc en el LS): tamaño del device + CRC de
            // lo último que mandó ESTA sesión.
            Long sent = sentCrc.get(remote);
            if (deviceSize != null && deviceSize.longValue() == data.length
                    && sent != null && sent.longValue() == crc) {
                return false;
            }
        }
        if (deviceSize != null) {
            try { b.del(remote); }
            catch (IOException delErr) { /* tolerable */ }
        }
        b.put(remote, data);
        sentCrc.put(remote, crc);
        return true;
    }

    // ---- lo que el IDE preparaba antes de subir (FrmMain), para el IDE y para bp ----

    /**
     * Los {@code .mod} de los que depende el programa, para subirlos a la placa: los que CARGA la VM, leídos del
     * {@code .mod} compilado ({@code mainMod}) y de los suyos, transitivos
     * ({@link basicplus.frontend.DependenciasDelMod}). Se buscan en:
     *   1) outDir (si el frontend lo emitió como dep)
     *   2) {bpdevices/} via devicesDir del BpVM.cfg
     *   3) {bpstdlib/} via stdlibDir (fallback)
     *   4) directorio del bpFile
     * y uno de la stdlib, en stdlibDir PRIMERO. Devuelve los que existen, en orden de descubrimiento.
     *
     * V7/H8 #538 (28-sep): antes salían de una regex sobre las líneas `import X` de los {@code .bp}, y se le
     * escapaban el {@code import Core} implícito (lo pone el parser), un módulo de biblioteca
     * ({@code import com.acme.lib.X} cogía {@code com}) y la dependencia de la que sólo había {@code .mod}. Con el
     * {@code Stdlib.pack} cargado {@code /lib} está vacío, y en el micro simulado un pack rancio mataba el programa
     * al enlazar. Qué se sube de verdad lo sigue decidiendo la placa ({@link #sube} → {@code statModule}, #466).
     *
     * @param cfg el BpVM.cfg del fuente (o el de la instalación, sin él); null: sin devicesDir ni stdlibDir
     */
    public static List<File> dependencias(Path bpFile, Path outDir, Path mainMod, edu.bpgenvm.config.VmConfig cfg,
                                          Consumer<String> consola) {
        List<File> result = new ArrayList<>();

        List<Path> searchDirs = new ArrayList<>();
        if (outDir != null) searchDirs.add(outDir);
        Path stdlibPath = null;
        if (cfg != null) {
            if (cfg.devicesDir != null && !cfg.devicesDir.isEmpty())
                searchDirs.add(java.nio.file.Paths.get(cfg.devicesDir));
            if (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) {
                stdlibPath = java.nio.file.Paths.get(cfg.stdlibDir);
                searchDirs.add(stdlibPath);
            }
        }
        if (bpFile != null && bpFile.getParent() != null) searchDirs.add(bpFile.getParent());

        // Resolución TRANSITIVA, leída de los .mod (V7/H8 #538): Gui importa Json para el loader de Forms (H13.1),
        // y hay que subir TODAS, no sólo las directas de la app. La stdlib, desde stdlibDir PRIMERO: un X.mod rancio
        // en outDir (de un compile viejo) tiene otro layout de vtable y el INVOKE_VIRTUAL cross-module no resuelve
        // en la placa (junio: I2c.Bus.read; y Json.JsonValue#asObject#15 en el P4, «exit 10 mudo»). Esa regla vive
        // ahora en DependenciasDelMod, con su prueba (DependenciasDelModTest). La decisión final de subir o no la
        // toma sube() según lo que YA exista en la placa.
        try {
            for (Path p : basicplus.frontend.DependenciasDelMod.paraSubir(mainMod, searchDirs, stdlibPath)) {
                result.add(p.toFile());
            }
        } catch (IOException e) {
            consola.accept("[deps] no se pudieron leer las dependencias de " + mainMod.getFileName()
                    + ": " + e.getMessage());
        }
        return result;
    }

    /** #466 — qué dependencias son de la STDLIB: las que viven en {@code stdlibDir}. Sólo decide a dónde iría una que
     *  la placa NO tenga (/lib); si la tiene, manda la respuesta de la placa. */
    public static Set<String> deLaStdlib(List<File> deps, String stdlibDir) {
        Set<String> r = new HashSet<>();
        if (stdlibDir == null || deps == null) return r;
        Path sp = java.nio.file.Paths.get(stdlibDir);
        for (File d : deps) {
            String n = d.getName();
            String base = n.endsWith(".mod") ? n.substring(0, n.length() - 4) : n;
            if (Files.isRegularFile(sp.resolve(base + ".mod")) || Files.isRegularFile(sp.resolve(base + ".bp")))
                r.add(n);
        }
        return r;
    }

    /**
     * F2 (H19) — prefijo de despliegue en el device para un proyecto: "/app/&lt;proj&gt;" (proj = carpeta del
     * .bpbuild, saneada a [A-Za-z0-9_-], cap 20); "/app" sin proyecto (fichero suelto, el comportamiento histórico).
     * Al ejecutar /app/&lt;proj&gt;/&lt;entry&gt;.mod el firmware deriva basedir=/app/&lt;proj&gt; → activa F1
     * (recursos + módulos base-dir-first).
     */
    public static String prefijoEnLaPlaca(Path bpbuild) {
        if (bpbuild == null) return "/app";
        Path dir = bpbuild.getParent();
        if (dir == null || dir.getFileName() == null) return "/app";
        String raw = dir.getFileName().toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 20; i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "/app" : "/app/" + sb;
    }

    /** H12 (#260) — los recursos de un proyecto (su carpeta resources/), con su ruta en la placa. Vacío sin proyecto. */
    public static Map<String, File> recursos(Path bpbuild, Consumer<String> consola) {
        Map<String, File> out = new LinkedHashMap<>();
        if (bpbuild == null) return out;
        Path rdir = bpbuild.getParent().resolve("resources");
        if (!Files.isDirectory(rdir)) return out;
        final String prefix = prefijoEnLaPlaca(bpbuild);   // F2: /app/<proj> o /app
        try (java.util.stream.Stream<Path> st = Files.walk(rdir)) {
            st.filter(Files::isRegularFile).forEach(p -> {
                String rel = rdir.relativize(p).toString().replace('\\', '/');
                out.put(prefix + "/" + rel, p.toFile());
            });
        } catch (IOException ex) {
            consola.accept("[resources] error leyendo " + rdir + ": " + ex.getMessage());
        }
        return out;
    }

    // ---- los ayudantes (de PicoExplorer) ----

    static String magicOf(byte[] d) {
        return (d != null && d.length >= 4)
                ? new String(d, 0, 4, java.nio.charset.StandardCharsets.US_ASCII) : null;
    }

    /** CRC32 de un buffer, como long (para confirmar contenido idéntico
     *  antes de saltarse un PUT). */
    static long crc32(byte[] data) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(data);
        return c.getValue();
    }

    /** Convierte un nombre local (e.g. "Hello.mod") en un path remoto.
     *  Si ya viene con `/`, se respeta. Si no, se prefija /app/. */
    static String toAppPath(String localName) {
        if (localName.indexOf('/') >= 0) return localName;
        return "/app/" + localName;
    }

    /** F2 (H19) — como toAppPath pero con prefijo de proyecto explícito
     *  ("/app/&lt;proj&gt;" o "/app"). Si el nombre ya trae '/', se respeta. */
    static String appPath(String prefix, String localName) {
        if (localName.indexOf('/') >= 0) return localName;
        return prefix + "/" + localName;
    }

    /** Para "Foo.mod" devuelve "Foo.mdn" en el mismo directorio (o null
     *  si el nombre no acaba en .mod). El fichero puede no existir —
     *  el caller chequea con .isFile(). H3 #158 fase D. */
    static File mdnSiblingOf(File modFile) {
        if (modFile == null) return null;
        String name = modFile.getName();
        if (!name.toLowerCase().endsWith(".mod")) return null;
        String base = name.substring(0, name.length() - 4);
        return new File(modFile.getParentFile(), base + ".mdn");
    }

    /**
     * ¿Es este `.mdn` MÁS VIEJO que su `.mod`? Entonces es de otra compilación y
     * NO se sube.
     *
     * Por qué existe esta comprobación: el `.mdn` sólo se regenera si el
     * proyecto tiene el AOT activado, pero el subidor cogía cualquiera que
     * hubiera al lado del `.mod`. Un `.mdn` de hace dos meses viajaba a la placa
     * como si fuera de este build — y como lleva código NATIVO compilado contra
     * una versión concreta de los helpers AOT, ejecutarlo tras cambiar esos
     * helpers es corrupción de memoria y reset (pasó: .mdn de mayo, helpers
     * v1→v2 en julio).
     *
     * Es la cuarta vez que un artefacto rancio muerde en este proyecto (el
     * fat-jar del IDE, los .mod versionados de la stdlib, los .o sin memoria de
     * sus flags). La regla barata que los caza a todos: si el fuente se
     * recompiló y el derivado no, el derivado NO vale.
     *
     * La VM tiene además su propio gate de ABI, que rechaza el `.mdn`
     * incompatible aunque llegue. Dos redes independientes, a propósito.
     */
    static boolean mdnIsStale(File mdnFile, File modFile, Perfil p) {
        if (mdnFile.lastModified() < modFile.lastModified()) return true;
        /* #441 — y la ARQUITECTURA, no sólo la fecha. Un `.mdn` de otra ISA es
         * más viejo en el sentido que importa aunque su fecha sea nueva: la
         * cabecera ya la lleva (`arch` = e_machine del ELF) y la placa dice la
         * suya en el INFO, pero NADIE las comparaba — así que el IDE lo subía
         * tan contento y el gate del loader lo rechazaba ya en la placa.
         * Comparar aquí convierte un fallo remoto en un "regenéralo" local. */
        int suyo = mdnArchOf(mdnFile);
        if (suyo != 0 && p.arch != 0 && suyo != p.arch) return true;
        /* #441 (V7/H2) — y la RECETA, la segunda mitad de la ficha: misma
         * arquitectura y otros flags (`-mcmodel=medany`, 17-ago) es código
         * inservible que ni la arch ni las fechas ven. La cabecera v2 lleva el
         * CRC del literal de bpvm_aot_recetas.h y la placa publica el suyo. Un
         * `.mdn` v1 (sin huella) no se compara: la placa lo carga y avisa. */
        long crc = mdnFlagsCrcOf(mdnFile);
        if (crc != 0 && p.flagsCrc != 0 && crc != p.flagsCrc) return true;
        /* V7/H5 (27-sep) — y la ABI de HELPERS: con la misma receta, un `.mdn` generado por un compilador de otra ABI
         * lo rechaza el cargador (en su log, sin que el programa se entere: #504). La placa la publica (`mdnAbi`). */
        int abi = mdnAbiOf(mdnFile);
        return abi != 0 && p.mdnAbi != 0 && abi != p.mdnAbi;
    }

    /** #441 — POR QUÉ está rancio, que no es lo mismo dicho de dos formas: si la
     *  causa es la arquitectura, decir «es más viejo que su .mod» es MENTIRA y
     *  manda a mirar fechas que están bien. */
    static String motivoMdnRancio(File mdnFile, File modFile, Perfil p) {
        if (mdnFile.lastModified() < modFile.lastModified()) {
            return "es MÁS VIEJO que su .mod";
        }
        int suyo = mdnArchOf(mdnFile);
        if (suyo != 0 && p.arch != 0 && suyo != p.arch) {
            return "es de otra ARQUITECTURA (" + archName(suyo) + ", y la placa es "
                 + archName(p.arch) + ")";
        }
        /* V7/H5 (27-sep) — la ABI de HELPERS: misma receta, otra tabla de helpers. */
        int abi = mdnAbiOf(mdnFile);
        if (abi != 0 && p.mdnAbi != 0 && abi != p.mdnAbi) {
            return "es de otra ABI de helpers (" + abi + ", y la placa habla la " + p.mdnAbi + ")";
        }
        /* #441 (V7/H2) — la RECETA: misma arquitectura, otros flags. */
        return String.format("es de otra RECETA de compilación (huella %08x, y la placa exige %s %08x): regenéralo",
                             mdnFlagsCrcOf(mdnFile), p.receta, p.flagsCrc);
    }

    /** [V7/H5, 27-sep] La ABI de helpers de un `.mdn` (cabecera: magic, versión u16, ABI u16, little-endian), o 0 si
     *  no se puede leer. */
    public static int mdnAbiOf(File f) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            byte[] hdr = new byte[8];
            in.readFully(hdr);
            if (hdr[0] != 'M' || hdr[1] != 'D' || hdr[2] != 'N') return 0;
            return java.nio.ByteBuffer.wrap(hdr).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(6) & 0xFFFF;
        } catch (Exception e) {
            return 0;                  /* ilegible → que decidan la fecha y lo demás, como antes */
        }
    }

    /** #441 — la huella de la receta de un `.mdn` v2 (`flags_crc`, offset 20,
     *  little-endian); 0 si es v1, ilegible o no es un .mdn. */
    public static long mdnFlagsCrcOf(File f) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            byte[] hdr = new byte[24];
            in.readFully(hdr, 0, 20);
            if (hdr[0] != 'M' || hdr[1] != 'D' || hdr[2] != 'N') return 0;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(hdr).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int version = bb.getShort(4) & 0xFFFF;
            if (version < 2) return 0;
            in.readFully(hdr, 20, 4);
            return bb.getInt(20) & 0xFFFFFFFFL;
        } catch (Exception e) {
            return 0;
        }
    }

    /** #441 — la `arch` del header de un `.mdn` (0 = no se pudo leer o legacy).
     *  Layout (ver `bpgenvm-c/src/mdn_format.h`): magic[4] · version u16 ·
     *  abi_version u16 · code_size u32 · sym_count u32 · **arch u32** → offset 16.
     *
     *  LITTLE-endian, y esto no es un detalle: el `.mod` va en big-endian y dar
     *  por hecho que el `.mdn` también lo hace devuelve 0x28000000 en vez de 40.
     *  Se vio en el volcado de un `.mdn` real (`xxd`), no leyendo el struct. */
    public static int mdnArchOf(File f) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'M' || magic[1] != 'D' || magic[2] != 'N') return 0;
            in.skipBytes(12);          /* version+abi (4) + code_size (4) + sym_count (4) */
            byte[] le = new byte[4];
            in.readFully(le);
            return java.nio.ByteBuffer.wrap(le)
                     .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        } catch (Exception e) {
            return 0;                  /* ilegible → que decida la fecha, como antes */
        }
    }

    /** Nombre legible de la arquitectura del nativo (e_machine del ELF). */
    public static String archName(int arch) {
        switch (arch) {
            case 40:  return "arm";      /* EM_ARM   — Cortex-M Thumb-2 */
            case 243: return "riscv";    /* EM_RISCV — RV32, ESP32-P4   */
            /* EM_XTENSA (ESP32-S3). Sin toolchain AOT todavía: se devuelve igual
             * para que AotBuild lo rechace con un aviso claro ("target no
             * soportado") en vez de caer al ajuste del proyecto y compilar ARM
             * para una placa Xtensa. Decir la verdad y fallar limpio. */
            case 94:  return "xtensa";   /* EM_XTENSA — ESP32-S3        */
            default:  return "";
        }
    }
}
