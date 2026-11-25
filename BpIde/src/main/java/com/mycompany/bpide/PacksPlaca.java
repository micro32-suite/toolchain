package com.mycompany.bpide;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * V9 #875 (25-nov) — LOS PACKS DE LA PLACA, como comandos, como el entorno ({@link EntornoPlaca}). Eduardo: <i>«Y cuando
 * terminemos, hacemos lo mismo con los Packs.»</i>
 *
 * <p>La lógica, sin ventana ({@link #graba} y lo suyo): la que vivía en {@link PacksPanel}; la usa también {@code bp}. Y los
 * cinco comandos ({@code packs_listar}, {@code pack_ficheros}, {@code pack_grabar}, {@code pack_borrar} y
 * {@code packs_formatear}), los mismos para los botones del panel y para la IA. Formatear, que borra lo de todos y no se
 * deshace, se lo pregunta antes a la persona que usa el IDE si lo pide la IA (Eduardo: <i>«1) si»</i>).</p>
 *
 * <p>Los que cambian la zona acaban cuando el IDE ha acabado con la placa: si tenía motores cargados se reinicia (#671), y
 * el comando espera a que vuelva y la reconecta; y devuelven la zona como queda.</p>
 */
public final class PacksPlaca {

    private static final long T = 8000;            // timeout wire
    private static final long T_FLASH = 30000;     // borra y programa
    private static final String TITULO = "Packs de la placa";
    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Quien tiene la zona a la vista (el panel): la lista cada vez que se lee, y cómo va lo que tarda. En el EDT. */
    public interface Mirador {
        void zona(BpvmClient.PackTable t);
        void avance(String que);
    }

    /** Volver a conectar cuando la placa se reinicia sola (lo hace el explorador). En el EDT. */
    public interface Reconecta {
        void trasReinicio(Consumer<BpvmClient> alVolver, Consumer<String> alFallar);
    }

    private final Comandos comandos;
    private final Supplier<BpvmClient> cliente;
    private final Supplier<Despliegue.Perfil> perfil;
    private final Supplier<String> quePlaca;
    private final Reconecta reconecta;
    private final Consumer<String> consola;
    private final List<Mirador> mirando = new CopyOnWriteArrayList<>();

    /** `cliente`, el de la conexión de ahora (null sin ella); `perfil`, lo que dijo la placa de su nativo (NINGUNO: se le
     *  pregunta); `quePlaca`, cómo se la llama al preguntar por ella; `consola`, la del IDE, desde cualquier hilo. */
    public PacksPlaca(Comandos comandos, Supplier<BpvmClient> cliente, Supplier<Despliegue.Perfil> perfil,
                      Supplier<String> quePlaca, Reconecta reconecta, Consumer<String> consola) {
        this.comandos = comandos;
        this.cliente = cliente;
        this.perfil = perfil;
        this.quePlaca = quePlaca;
        this.reconecta = reconecta;
        this.consola = consola != null ? consola : s -> { };
        registra();
    }

    /* ===== la lógica, sin ventana ===== */

    /** Lo grabado: el pack, lo que ocupa (podado para esta placa) y lo que medía el fichero, dónde, los avisos, y los que ya
     *  había activos con su nombre (gana el último de la cadena). */
    public static final class Grabado {
        public final String nombre;
        public final int bytes, delFichero;
        public final long offset;
        public final List<String> avisos;
        public final List<BpvmClient.PackInfo> habia;
        Grabado(String nombre, int bytes, int delFichero, long offset, List<String> avisos,
                List<BpvmClient.PackInfo> habia) {
            this.nombre = nombre; this.bytes = bytes; this.delFichero = delFichero; this.offset = offset;
            this.avisos = avisos; this.habia = habia;
        }
    }

    /** Un pack de la instalación por su nombre («Gui»), o null. */
    public static File deLaInstalacion(String nombre) {
        String dir = IdePrefs.load().packsDirEffective();
        File[] hijos = dir == null ? null : new File(dir).listFiles();
        if (hijos == null) return null;
        for (File f : hijos) {
            if (f.isFile() && f.getName().equalsIgnoreCase(nombre.endsWith(".pack") ? nombre : nombre + ".pack")) return f;
        }
        return null;
    }

    /** Los packs de la instalación, por su nombre (para decir los que hay). */
    static List<String> losDeLaInstalacion() {
        List<String> out = new ArrayList<>();
        String dir = IdePrefs.load().packsDirEffective();
        File[] hijos = dir == null ? null : new File(dir).listFiles();
        if (hijos != null) {
            for (File f : hijos) {
                String n = f.getName();
                if (f.isFile() && n.toLowerCase().endsWith(".pack")) out.add(n.substring(0, n.length() - 5));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Graba un pack en la placa: avisa de lo que le faltará, lo prepara para ella (V5/H8: se queda con el motor de su
     * familia y lo sella para la dirección que ella le da, que no se sabe hasta que elige el hueco: entre el BEGIN y los
     * datos) y lo manda por trozos. `conocido`, lo que dijo la placa de su nativo (NINGUNO: se le pregunta).
     */
    public static Grabado graba(BpvmClient c, File f, Despliegue.Perfil conocido, Consumer<String> log,
                                BpvmClient.BurnProgress avance) throws IOException {
        final byte[] img = Files.readAllBytes(f.toPath());
        final BpvmClient.PackTable antes = c.packList(T);
        List<String> avisos = new ArrayList<>();
        String nombre = f.getName();
        try {
            basicplus.pack.PackReader.Pack p = basicplus.pack.PackReader.read(img);
            nombre = p.nombre;
            avisos.addAll(packsQueFaltan(p, antes));
            avisos.addAll(stdlibQueFalta(c, p, f));
        } catch (Exception e) {
            /* Un pack que no se deja leer lo dirá el grabado, no estos avisos. */
        }
        for (String a : avisos) log.accept("[Packs] AVISO: " + a);
        List<BpvmClient.PackInfo> habia = new ArrayList<>();
        for (BpvmClient.PackInfo pi : antes.packs) if (pi.active && pi.name.equals(nombre)) habia.add(pi);

        final basicplus.frontend.PackBurn.Preparado prep = prepara(c, img, conocido, log);
        final byte[] aGrabar = (prep != null) ? prep.bytes : img;
        long off = c.packBurn(aGrabar, T_FLASH, (flashAddr, ramBase, ramSize) -> {
            if (prep == null || !prep.necesitaDireccion) return aGrabar;
            int codigo = basicplus.frontend.PackBurn.baseDelCodigo(prep, (int) flashAddr);
            log.accept(String.format("[Packs] pack en 0x%08X · motor en 0x%08X · RAM 0x%08X (%d KB) — relocalizando %d"
                    + " sitio(s)", flashAddr, codigo, ramBase, ramSize / 1024, prep.relocalizaciones));
            try {
                /* V7/H6.c G1: con `ramSize`, un motor que no cabe se dice AQUÍ, antes de mandar un byte — no al cargarlo. */
                return basicplus.frontend.PackBurn.sellar(prep, codigo, (int) ramBase, ramSize);
            } catch (basicplus.frontend.PackBurn.BurnException be) {
                throw new IOException(be.getMessage(), be);
            }
        }, avance);
        return new Grabado(nombre, aGrabar.length, img.length, off, avisos, habia);
    }

    /** [V7/H2, #493] Los packs que éste declara necesitar (`requires-pack=` del manifest) y la placa no tiene. Se comprueba
     *  aquí, al grabar, con la placa delante: el PC no sabe qué hay grabado. Se avisa y se graba igual. */
    private static List<String> packsQueFaltan(basicplus.pack.PackReader.Pack p, BpvmClient.PackTable t) {
        List<String> out = new ArrayList<>();
        String manifest = null;
        for (basicplus.pack.PackEntry e : p.entries) {
            if (basicplus.pack.PackFormat.TYPE_MANIFEST.equals(e.tipo)
                    && basicplus.pack.PackFormat.MANIFEST_NAME.equals(e.nombre))
                manifest = new String(e.data, StandardCharsets.UTF_8);
        }
        if (manifest == null) return out;
        Set<String> grabados = new HashSet<>();
        for (BpvmClient.PackInfo pi : t.packs) if (pi.active) grabados.add(pi.name);
        for (String linea : manifest.split("\n")) {
            linea = linea.trim();
            if (!linea.startsWith("requires-pack=")) continue;
            for (String r : linea.substring("requires-pack=".length()).split(",")) {
                r = r.trim();
                if (r.isEmpty() || grabados.contains(r)) continue;
                /* #539 — «no está en la lista» sólo es «no está grabado» si la lista trae TODOS los activos. */
                out.add("'" + p.nombre + "' declara requires-pack=" + r + (t.activosCompletos()
                        ? " y la placa no tiene ese pack grabado: grábalo también, o el RUN dirá que falta un módulo"
                        : " y no está entre los activos que lista la placa, pero la lista no los trae todos (#539): puede"
                          + " estar grabado; si el RUN dice que falta un módulo, grábalo"));
            }
        }
        return out;
    }

    /** [V7 #623, 8-oct] Un pack LIBRERÍA no lleva la stdlib: la saca la placa del Stdlib.pack o de /lib. Los módulos de la
     *  stdlib que usa, no lleva y la PLACA NO TIENE (STAT por nombre: ni en el FS ni en un pack). Cuáles son de la stdlib lo
     *  dice la carpeta `stdlibDir` del BpVM.cfg más cercano al pack (sin cfg, todos los que importa y no lleva). */
    private static List<String> stdlibQueFalta(BpvmClient c, basicplus.pack.PackReader.Pack p, File f) throws IOException {
        java.nio.file.Path sdir = null;
        try {
            edu.bpgenvm.config.VmConfig cfg = edu.bpgenvm.config.VmConfig.loadDefaultFor(
                    f.getAbsoluteFile().getParentFile().toPath());
            if (cfg.sourcePath == null || cfg.stdlibDir == null || cfg.stdlibDir.isEmpty())
                cfg = IdePrefs.cfgPara(null);   // V9 #867: el del cwd, y sin él, el de la instalación
            if (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) sdir = java.nio.file.Paths.get(cfg.stdlibDir);
        } catch (Throwable sinCfg) { /* sin cfg: cuenta todo lo que importa y no lleva */ }
        List<String> faltan = new ArrayList<>();
        for (String m : basicplus.frontend.PackStep.stdlibQueImporta(p.entries, sdir)) {
            if (c.statModule(m + ".mod", null, T) == null) faltan.add(m);
        }
        List<String> out = new ArrayList<>();
        if (!faltan.isEmpty()) {
            String lo = faltan.size() == 1 ? "lo" : "los";
            out.add("'" + p.nombre + "' usa " + String.join(", ", faltan) + " de la stdlib, no " + lo + " lleva (un pack"
                    + " librería no lleva la stdlib, #623) y la placa no " + lo + " tiene: graba también el Stdlib.pack, o el"
                    + " RUN " + lo + " subirá a /lib (a la RAM)");
        }
        return out;
    }

    /**
     * V5/H8 — deja el pack con lo de ESTA placa: poda las familias que no son y prepara el motor para sellarlo cuando se
     * sepa la dirección. null si no hay que tocar nada. Un fallo NO se traga: grabar un pack a medias es peor que no
     * grabarlo, y el mensaje de `PackBurn` ya dice qué pasa.
     *
     * <p>La familia la dice la PLACA: el motor equivocado no da error, da un salto a un sitio que no es. Y si no se sabe
     * todavía, se le pregunta aquí (el valor se guarda al conectar con una petición asíncrona, que puede no haber
     * llegado).</p>
     */
    private static basicplus.frontend.PackBurn.Preparado prepara(BpvmClient c, byte[] img, Despliegue.Perfil conocido,
                                                                  Consumer<String> log) throws IOException {
        Despliegue.Perfil p = conocido;
        if (p.arch == 0) {
            try {
                p = Despliegue.Perfil.delInfo(c.getInfo(T));
            } catch (IOException io) {
                throw new IOException("la placa no responde al INFO (" + io.getMessage() + "). Sin eso no se puede saber qué"
                        + " motor grabar. Reinicia la placa y vuelve a conectar; comprueba con el botón INFO antes de"
                        + " grabar.", io);
            }
        }
        String que = PicoExplorer.archName(p.arch) + (p.receta.isEmpty() ? "" : " " + p.receta);
        basicplus.frontend.NpackReloc.Destino d = destino(p);
        /* Arquitectura desconocida o sin motor nativo (el Xtensa del S3): se graba SIN nativo (Eduardo, 22-ago: «lleve nativo
         * o no, el pack se graba; lo unico, si el nativo no existe o no se corresponde con el del micro, se graba SIN
         * nativo»). `podar` quita todo lo nativo y lo dice. */
        if (d == null) {
            log.accept("[Packs] la placa es '" + (que.trim().isEmpty() ? "(no la dice)" : que.trim())
                    + "': no hay motor nativo para ella, se grabará SIN nativo");
        }
        try {
            basicplus.frontend.PackBurn.Preparado prep = basicplus.frontend.PackBurn.podar(img, d);
            for (String s : prep.detalle) log.accept("[Packs] " + s);
            return prep;
        } catch (basicplus.frontend.PackBurn.BurnException be) {
            throw new IOException(be.getMessage(), be);
        }
    }

    /** De qué familia es el motor que se graba en esa placa; null, ninguno. V9 #892 — por la RECETA que publica (desde V7
     *  H2, #441): por la arquitectura no se distinguen la P4 (RISC-V con FPU) y la C3, la C5 y la C6 (sin ella), y hasta V9
     *  en estas tres se grababa el motor de la P4, que la placa rechaza, o ninguno. Sin receta (una imagen de antes de V7),
     *  por la arquitectura, como siempre. */
    static basicplus.frontend.NpackReloc.Destino destino(Despliegue.Perfil p) {
        return !p.receta.isEmpty() ? basicplus.frontend.NpackReloc.porReceta(p.receta)
                : basicplus.frontend.NpackReloc.porTargetAot(PicoExplorer.archName(p.arch));
    }

    /** El resumen de la zona, en una línea (la del panel). */
    static String resumen(BpvmClient.PackTable t) {
        int alive = 0;
        for (BpvmClient.PackInfo p : t.packs) if (p.active) alive++;
        long used = t.regionSize - t.free;   // ocupado = cadena entera (retirados incluidos)
        // #539 — los activos que no caben en la lista también cuentan; y con una imagen anterior que truncó sólo se sabe de
        // los listados, y se dice.
        int activos = alive + Math.max(0, t.activeOmitted);
        boolean sinCuenta = t.omitted < 0 && t.count > t.packs.size();
        StringBuilder s = new StringBuilder();
        s.append(t.count).append(" packs (").append(activos)
         .append(sinCuenta ? " activos listados) · ocupado " : " activos) · ocupado ")
         .append(kb(used)).append(" · libre ").append(kb(t.free)).append(" de ").append(kb(t.regionSize));
        // cadena corrupta con 0 packs = zona SIN ESTRENAR (restos del uso anterior de esa flash) → «Formatear zona…»
        if (!t.chainOk) s.append(t.count == 0
                ? "  ⚠ zona sin formatear (restos previos) → «Formatear zona…»"
                : "  ⚠ cadena corrupta → compactar desde el PC o «Formatear zona…»");
        if (t.count > t.packs.size()) s.append(avisoListado(t));
        return s.toString();
    }

    /** #539 — lo que el PACK_LS no lista, dicho. El device lista como mucho 16: los activos primero y, en el hueco, los
     *  borrados más recientes; el resto lo cuenta. Una imagen anterior no lo cuenta y listaba los 16 PRIMEROS de la
     *  cadena, que en una placa con historia son borrados. */
    private static String avisoListado(BpvmClient.PackTable t) {
        int n = t.packs.size();
        if (t.omitted < 0)
            return "  ⚠ listados " + n + " de " + t.count + ": esta imagen no dice cuáles faltan"
                 + " y pueden ser ACTIVOS (#539) → regrabar la imagen";
        if (t.activeOmitted > 0)
            return "  ⚠ " + t.omitted + " sin listar, " + t.activeOmitted + " de ellos ACTIVOS"
                 + " (hay más activos de los que caben en la lista)";
        return "  (listados " + n + ": faltan los " + t.omitted + " borrados más antiguos)";
    }

    static String fecha(long segundos) {
        return segundos > 0 ? FECHA.format(Instant.ofEpochSecond(segundos)) : "—";
    }

    static String kb(long bytes) { return (bytes / 1024L) + " KB"; }

    /* ===== los comandos ===== */

    private void registra() {
        comandos.registra("packs_listar", "Packs de la placa: listar", this::lista)
                .corto("Refrescar")
                .explica("La zona de packs de la placa conectada (o del micro simulado): «zona» (su tamaño, lo que queda libre y"
                        + " si la cadena está bien) y «packs», cada uno con su nombre, versión, fecha, bytes, cuántos"
                        + " ficheros lleva, si está activo, si su CRC está bien y su «offset». Uno retirado sigue ocupando"
                        + " sitio hasta que se formatea la zona.");
        comandos.registra("pack_ficheros", "Packs de la placa: los ficheros de uno", this::ficheros)
                .explica("Los ficheros que lleva dentro un pack grabado en la placa (sus módulos, recursos, motor…). Di su"
                        + " nombre o su offset.")
                .parametro("pack", "el nombre del pack (si hay varios activos con él, el último)")
                .parametro("offset", "numero", "o su offset, el de «packs_listar»");
        comandos.registra("pack_grabar", "Packs de la placa: grabar uno", this::grabaUno)
                .corto("Copiar pack a la placa…")
                .explica("Graba un pack en la zona de packs de la placa conectada (o del micro simulado): uno de la instalación"
                        + " («pack») o un .pack del PC («fichero»). El IDE lo prepara para esa placa: se queda con el motor"
                        + " de su familia y lo sella para el sitio que ella le da. Escribe su flash, y tarda (la GUI, un"
                        + " minuto o más). Si la placa tenía motores cargados se reinicia: acaba cuando ha vuelto y está"
                        + " reconectada. Devuelve lo grabado, los «avisos» (lo que le va a faltar), si ya había otro activo"
                        + " con su nombre («habia»: gana el último grabado; el viejo se retira después con «pack_borrar») y"
                        + " la zona como queda. Un motor (la GUI, SQLite) necesita ANTES su bloque de RAM, packRam o SQLite,"
                        + " en el entorno, y la placa reiniciada: qué lleva cada placa, en docs/INSTALAR_FIRMWARE.md, «Los"
                        + " packs de la placa».")
                .parametro("pack", "uno de la instalación, por su nombre: Stdlib, Gui, GuiMini, DriverSpi o SQLite")
                .parametro("fichero", "ruta", "o la ruta de un .pack");
        comandos.registra("pack_borrar", "Packs de la placa: retirar uno", this::retira)
                .corto("Borrar")
                .explica("Retira un pack de la placa conectada (o del micro simulado): deja de usarse, y su sitio no se recupera"
                        + " hasta formatear la zona. Si la placa tenía motores cargados se reinicia: acaba cuando ha vuelto"
                        + " y está reconectada. Devuelve la zona como queda.")
                .parametro("pack", "el nombre del pack")
                .parametro("offset", "numero", "su offset (el de «packs_listar»), si hay varios activos con ese nombre");
        comandos.registra("packs_formatear", "Packs de la placa: formatear la zona", this::formatea)
                .corto("Formatear zona…")
                .explica("Deja VACÍA la zona de packs de la placa conectada (o del micro simulado): borra todos sus packs, y no"
                        + " se puede deshacer. Antes el IDE se lo pregunta a la persona que lo usa, con los packs que se"
                        + " pierden: si dice que no, no se hace. Para estrenar la zona, arreglar una cadena rota o recuperar"
                        + " el sitio de los retirados; después hay que volver a grabar los packs de la placa.")
                .pregunta(p -> loQueBorraFormatear());
    }

    /** «packs_listar»: la zona y sus packs. */
    private CompletableFuture<Resultado> lista(Map<String, Object> p) {
        return enSegundoPlano(p, c -> c.packList(T), t -> {
            avisa(t);
            return conLaZona(Resultado.bien(resumen(t)), t);
        });
    }

    /** «pack_ficheros»: lo que lleva dentro un pack grabado. */
    private CompletableFuture<Resultado> ficheros(Map<String, Object> p) {
        final String nombre = texto(p.get("pack"));
        final long offset = numero(p.get("offset"));
        if (nombre == null && offset < 0) return noVale(p, "pack_ficheros: di el pack, por su nombre o por su offset");
        return enSegundoPlano(p, c -> {
            BpvmClient.PackTable t = c.packList(T);
            BpvmClient.PackInfo pi = null;
            if (offset < 0) pi = ultimoActivo(t, nombre);
            else for (BpvmClient.PackInfo x : t.packs) if (x.offset == offset) pi = x;
            if (pi == null) {
                throw new IOException(offset >= 0 ? "no hay ningún pack en el offset " + offset
                        : "no hay ningún pack activo llamado «" + nombre + "»");
            }
            return new Object[] { pi, c.packEntries(pi.offset, T) };
        }, r -> {
            BpvmClient.PackInfo pi = (BpvmClient.PackInfo) r[0];
            @SuppressWarnings("unchecked")
            List<BpvmClient.PackEntry> entradas = (List<BpvmClient.PackEntry>) r[1];
            List<Map<String, Object>> fs = new ArrayList<>();
            for (BpvmClient.PackEntry e : entradas) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nombre", e.nombre + "." + e.tipo);
                m.put("tipo", e.tipo);
                m.put("bytes", e.size);
                fs.add(m);
            }
            // la placa lista como mucho 32 (PACK_ENT_MAX): lo que falta, se dice
            String cuantos = pi.files > fs.size() ? pi.files + " ficheros (la placa lista los " + fs.size() + " primeros)"
                    : fs.size() + " ficheros";
            return Resultado.bien("«" + pi.name + "»: " + cuantos).con("pack", pi.name).con("offset", pi.offset)
                    .con("total", Math.max(pi.files, fs.size())).con("ficheros", fs);
        });
    }

    /** «pack_grabar»: un pack de la instalación (por su nombre) o un .pack del PC. */
    private CompletableFuture<Resultado> grabaUno(Map<String, Object> p) {
        String nombre = texto(p.get("pack"));
        String ruta = texto(p.get("fichero"));
        if ((nombre == null) == (ruta == null)) {
            return noVale(p, "pack_grabar: di uno de los dos, «pack» (uno de la instalación) o «fichero»");
        }
        final File f = ruta != null ? new File(ruta) : deLaInstalacion(nombre);
        if (f == null) {
            return noVale(p, "no hay ningún pack «" + nombre + "» en la instalación; los que hay: "
                    + String.join(", ", losDeLaInstalacion()));
        }
        if (!f.isFile()) return noVale(p, "no existe el fichero " + f);
        avance("grabando " + f.getName() + "…");
        return cambiando(p, c -> graba(c, f, perfil.get(), consola, (hechos, total) -> {
            final int pct = total > 0 ? (int) ((hechos * 100L) / total) : 0;
            avance("grabando " + f.getName() + "… " + pct + " %  (" + (hechos / 1024) + "/" + (total / 1024) + " KB)");
        }), g -> {
            /* Lo GRABADO, no el fichero de origen: con la poda ya no son lo mismo, y el panel lee de la flash. */
            String que = "grabado " + f.getName() + " (" + g.bytes + " B"
                    + (g.delFichero != g.bytes ? " — podado de " + g.delFichero + " para esta placa" : "")
                    + ") en offset " + g.offset;
            consola.accept("[Packs] " + que);
            return que;
        }, (g, r) -> {
            r.con("pack", g.nombre).con("bytes", g.bytes).con("offset", g.offset);
            if (!g.avisos.isEmpty()) r.con("avisos", g.avisos);
            if (!g.habia.isEmpty()) {
                List<Map<String, Object>> h = new ArrayList<>();
                for (BpvmClient.PackInfo pi : g.habia) h.add(datosDe(pi));
                r.con("habia", h);
            }
            return r;
        });
    }

    /** «pack_borrar»: lo marca retirado (no se borra: su sitio se recupera formateando). */
    private CompletableFuture<Resultado> retira(Map<String, Object> p) {
        final String nombre = texto(p.get("pack"));
        final long offset = numero(p.get("offset"));
        if (nombre == null && offset < 0) return noVale(p, "pack_borrar: di el pack, por su nombre o por su offset");
        return cambiando(p, c -> {
            BpvmClient.PackTable t = c.packList(T);
            List<BpvmClient.PackInfo> activos = new ArrayList<>();
            for (BpvmClient.PackInfo pi : t.packs) {
                if (offset >= 0 ? pi.offset == offset : pi.active && pi.name.equals(nombre)) activos.add(pi);
            }
            if (activos.isEmpty()) {
                throw new IOException(offset >= 0 ? "no hay ningún pack en el offset " + offset
                        : "no hay ningún pack activo llamado «" + nombre + "»");
            }
            if (activos.size() > 1) {
                StringBuilder s = new StringBuilder();
                for (BpvmClient.PackInfo pi : activos) {
                    s.append(s.length() > 0 ? "; " : "").append("offset ").append(pi.offset).append(", del ")
                     .append(fecha(pi.date));
                }
                throw new IOException("hay " + activos.size() + " packs activos llamados «" + nombre + "» (" + s
                        + "): di cuál con «offset»");
            }
            BpvmClient.PackInfo pi = activos.get(0);
            if (!pi.active) throw new IOException("'" + pi.name + "' ya está retirado");
            c.packDel(pi.offset, T_FLASH);
            return pi;
        }, pi -> {
            String que = "'" + pi.name + "' marcado como borrado (offset " + pi.offset + ")";
            consola.accept("[Packs] " + que);
            return que;
        }, (pi, r) -> r.con("pack", pi.name).con("offset", pi.offset));
    }

    /** «packs_formatear»: deja vacía la zona. La IA no llega aquí sin el sí de la persona (su pregunta, en el registro). */
    private CompletableFuture<Resultado> formatea(Map<String, Object> p) {
        avance("formateando zona de packs…");
        return cambiando(p, c -> { c.packFormat(T_FLASH); return Boolean.TRUE; }, x -> {
            consola.accept("[Packs] zona de packs formateada");
            return "zona de packs formateada";
        }, (x, r) -> r);
    }

    /** Lo que se le pregunta a la persona antes de formatear, si lo pide la IA: la placa y lo que se pierde. null sin placa
     *  (lo dirá el comando). */
    private CompletableFuture<String> loQueBorraFormatear() {
        final BpvmClient c = cliente.get();
        final CompletableFuture<String> fin = new CompletableFuture<>();
        if (c == null) { fin.complete(null); return fin; }
        final String placa = quePlaca.get();
        new SwingWorker<BpvmClient.PackTable, Void>() {
            @Override protected BpvmClient.PackTable doInBackground() throws Exception { return c.packList(T); }
            @Override protected void done() {
                StringBuilder s = new StringBuilder("FORMATEAR la zona de packs de la placa conectada:\n\n   ")
                        .append(placa).append("\n\n");
                try {
                    List<String> activos = new ArrayList<>();
                    for (BpvmClient.PackInfo pi : get().packs) if (pi.active) activos.add(pi.name);
                    s.append(activos.isEmpty() ? "No hay ningún pack activo en ella."
                            : "Se borran TODOS sus packs: " + String.join(", ", activos) + ".");
                } catch (Exception e) {
                    s.append("Se borran TODOS sus packs (no se ha podido leer cuáles hay).");
                }
                s.append("\nNo se puede deshacer: habría que volver a grabarlos.");
                fin.complete(s.toString());
            }
        }.execute();
        return fin;
    }

    /* ===== los datos ===== */

    private static Resultado conLaZona(Resultado r, BpvmClient.PackTable t) {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("total", t.regionSize);
        z.put("libre", t.free);
        z.put("cadena_bien", t.chainOk);
        z.put("packs", t.count);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (BpvmClient.PackInfo pi : t.packs) ps.add(datosDe(pi));
        return r.con("zona", z).con("packs", ps);
    }

    private static Map<String, Object> datosDe(BpvmClient.PackInfo pi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", pi.name);
        if (!pi.version.isEmpty()) m.put("version", pi.version);
        m.put("fecha", fecha(pi.date));
        m.put("bytes", pi.size);
        m.put("ficheros", pi.files);
        m.put("activo", pi.active);
        m.put("crc_bien", pi.crcOk);
        m.put("offset", pi.offset);
        return m;
    }

    private static BpvmClient.PackInfo ultimoActivo(BpvmClient.PackTable t, String nombre) {
        BpvmClient.PackInfo u = null;
        for (BpvmClient.PackInfo pi : t.packs) {
            if (pi.active && pi.name.equals(nombre) && (u == null || pi.offset > u.offset)) u = pi;
        }
        return u;
    }

    private static String texto(Object v) {
        String s = v == null ? "" : v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long numero(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return v == null ? -1 : Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return -1; }
    }

    /* ===== quien tiene la zona a la vista ===== */

    public void mira(Mirador quien) { mirando.add(quien); }

    public void dejaDeMirar(Mirador quien) { mirando.remove(quien); }

    private void avisa(BpvmClient.PackTable t) {
        for (Mirador m : mirando) m.zona(t);
    }

    private void avance(String que) {
        Runnable r = () -> { for (Mirador m : mirando) m.avance(que); };
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    /* ===== el cable, nunca en el EDT ===== */

    private interface Tarea<R> { R corre(BpvmClient c) throws IOException; }

    private static Resultado sobre(Map<String, Object> p, Resultado r) {
        Object s = p.get("sobre");   // la mete la ventana que lo pide; la IA no puede pasarla
        return s instanceof Component ? r.sobre((Component) s) : r;
    }

    private static CompletableFuture<Resultado> noVale(Map<String, Object> p, String porque) {
        return CompletableFuture.completedFuture(sobre(p, Resultado.aviso(TITULO, porque, JOptionPane.INFORMATION_MESSAGE)));
    }

    /** Lo que sólo lee. */
    private <R> CompletableFuture<Resultado> enSegundoPlano(Map<String, Object> p, Tarea<R> tarea,
                                                            java.util.function.Function<R, Resultado> alAcabar) {
        final CompletableFuture<Resultado> fin = new CompletableFuture<>();
        corre(p, tarea, (c, r) -> fin.complete(alAcabar.apply(r)), fin);
        return fin;
    }

    /** Lo que cambia la zona: tras hacerlo (`hecho`: qué, para el mensaje), la placa puede reiniciarse sola (#671): se la
     *  espera y se reconecta; y la zona como queda va en el resultado, con lo suyo (`datos`). */
    private <R> CompletableFuture<Resultado> cambiando(Map<String, Object> p, Tarea<R> tarea,
            java.util.function.Function<R, String> hecho, BiFunction<R, Resultado, Resultado> datos) {
        final CompletableFuture<Resultado> fin = new CompletableFuture<>();
        corre(p, tarea, (c, r) -> {
            final String que = hecho.apply(r);
            if (!c.reiniciaTrasPack()) {
                laZonaYAcaba(c, que, r, datos, fin);
                return;
            }
            consola.accept("[Packs] la placa tenía motores cargados y se REINICIA para cargar los de la zona nueva (#671)");
            avance("la placa se está reiniciando…");
            reconecta.trasReinicio(nuevo -> {
                consola.accept("[Packs] la placa ha vuelto con los motores de la zona nueva");
                laZonaYAcaba(nuevo, que + "; la placa se ha reiniciado y está reconectada", r, datos, fin);
            }, motivo -> {
                consola.accept("[Packs] la placa no ha vuelto (" + motivo + "): reconecta a mano");
                avance("la placa no ha vuelto: reconecta a mano");
                fin.complete(datos.apply(r, Resultado.bien(que + "; la placa se reinicia y no ha vuelto (" + motivo
                        + "): hay que reconectar")));
            });
        }, fin);
        return fin;
    }

    private <R> void laZonaYAcaba(BpvmClient c, String que, R r, BiFunction<R, Resultado, Resultado> datos,
                                  CompletableFuture<Resultado> fin) {
        new SwingWorker<BpvmClient.PackTable, Void>() {
            @Override protected BpvmClient.PackTable doInBackground() throws Exception { return c.packList(T); }
            @Override protected void done() {
                Resultado res;
                try {
                    BpvmClient.PackTable t = get();
                    avisa(t);
                    res = conLaZona(Resultado.bien(que + " · libre " + kb(t.free) + " de " + kb(t.regionSize)), t);
                } catch (Exception e) {   // lo hecho, hecho está; la lista, no
                    res = Resultado.bien(que + " (la zona no se ha podido leer: "
                            + PicoExplorer.motivo(e.getCause() != null ? e.getCause() : e) + ")");
                }
                fin.complete(datos.apply(r, res));
            }
        }.execute();
    }

    private <R> void corre(Map<String, Object> p, Tarea<R> tarea, java.util.function.BiConsumer<BpvmClient, R> alAcabar,
                           CompletableFuture<Resultado> fin) {
        final BpvmClient c = cliente.get();
        if (c == null) {
            avance("(sin conexión)");
            fin.complete(sobre(p, Resultado.aviso(TITULO, "no hay ninguna placa conectada", JOptionPane.WARNING_MESSAGE)));
            return;
        }
        new SwingWorker<R, Void>() {
            @Override protected R doInBackground() throws Exception { return tarea.corre(c); }
            @Override protected void done() {
                R r;
                try {
                    r = get();
                } catch (Exception e) {
                    String m = PicoExplorer.motivo(e.getCause() != null ? e.getCause() : e);
                    // sin zona de packs (antes de las particiones) es un estado normal, no un diálogo
                    if (m.contains("sin zona de packs")) {
                        String no = "la placa no expone packs (configura las particiones primero)";
                        avance(no);
                        fin.complete(Resultado.mal(no).con("sin_zona", true));
                        return;
                    }
                    if (m.contains("[UNSUPPORTED]")) {
                        m = "aquí no hay zona de packs: la tienen las placas y el micro simulado, no la VM Java (" + m + ")";
                    }
                    consola.accept("[Packs] error: " + m);
                    avance("error");
                    fin.complete(sobre(p, Resultado.aviso(TITULO, m, JOptionPane.ERROR_MESSAGE)));
                    return;
                }
                try {
                    alAcabar.accept(c, r);
                } catch (RuntimeException e) {
                    fin.complete(Resultado.mal(String.valueOf(e)));
                }
            }
        }.execute();
    }
}
