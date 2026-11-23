// ============================================================
// Traza.java
// V9 #855 (c) — LA TRAZA DE UN ERROR, CON SUS NOMBRES.
//
// Las dos VMs dicen dónde falló un programa en posiciones del bytecode, de
// dentro afuera: «=== traza: Rompe+122 Rompe+39 ===». Esto las traduce con los
// .dbg a «Rompe.bp:8 (Main)»: el fichero, la línea y la función. UN traductor
// (Eduardo, 23-nov: «Un traductor, en Java»), y tres que lo usan: la VM Java (la
// suya: es la fusionada, tiene el .dbg), `bp` (la de la VM-C, que no lo tiene:
// como en la placa, la traducción es del lado del host) y el IDE (TrazaError).
//
// Viene de TrazaError (V8 #582, #788), ahora con la función. Cada posición es la
// instrucción SIGUIENTE a la que falló o llamó, así que la línea y la función son
// las de la posición menos uno. Lo que cae en la línea 0 (el __init del módulo)
// no es del programa, y no se cuenta. Un módulo sin .dbg sale tal cual
// («Core+1200»): se pierde la línea, no el dato. El espejo en Python,
// bpgenvm-c/tools/traza_dbg.py.
// ============================================================
package edu.bpgenvm.vm.debug;

import edu.bpgenvm.vm.ModuleManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Traza {

    private Traza() {}

    /** Lo que antecede a una traza en la salida de errores de las dos VMs. */
    public static final String PREFIJO = "=== traza: ";
    private static final String SUFIJO = " ===";

    /** La traza de una línea «=== traza: … ===» (la que escriben las dos VMs); null si la línea no es eso. */
    public static String deLaLinea(String linea) {
        if (linea == null) return null;
        String l = linea.trim();
        if (!l.startsWith(PREFIJO) || !l.endsWith(SUFIJO) || l.length() < PREFIJO.length() + SUFIJO.length()) {
            return null;
        }
        return l.substring(PREFIJO.length(), l.length() - SUFIJO.length()).trim();
    }

    /**
     * La traza («Mod+rel Mod+rel …»), marco a marco: «Rompe.bp:8 (Main)», o «Mod+rel» si no hay .dbg de ese módulo.
     * Los .dbg se buscan en las carpetas de {@code donde}, por orden (el primero que se lee).
     */
    public static List<String> marcos(String traza, List<Path> donde) {
        List<String> salida = new ArrayList<>();
        if (traza == null || traza.trim().isEmpty()) return salida;
        Map<String, DbgFile> dbgs = new HashMap<>();
        for (String marco : traza.trim().split("\\s+")) {
            int mas = marco.lastIndexOf('+');
            if (mas <= 0) { salida.add(marco); continue; }
            String mod = marco.substring(0, mas);
            int rel;
            try { rel = Integer.parseInt(marco.substring(mas + 1)); }
            catch (NumberFormatException e) { salida.add(marco); continue; }
            if (!dbgs.containsKey(mod)) dbgs.put(mod, busca(mod, donde));
            DbgFile dbg = dbgs.get(mod);
            if (dbg == null) { salida.add(marco); continue; }
            int linea = dbg.lineForRelPc(rel - 1);
            if (linea <= 0) continue;   // el __init del módulo: no es del programa
            ModuleManager.FunctionVars f = dbg.functionForRelPc(rel - 1);
            String funcion = (f != null && f.name != null && !f.name.isEmpty()) ? " (" + f.name + ")" : "";
            salida.add(fichero(dbg, mod) + ":" + linea + funcion);
        }
        return salida;
    }

    /** V8 #788 — las líneas para leer: «  en X» el primero y «  llamado desde Y» los demás. Las mismas en la consola
     *  del IDE (la placa y la VM local), en la VM Java y en `bp`. */
    public static List<String> lineas(List<String> marcos) {
        List<String> out = new ArrayList<>(marcos.size());
        for (int i = 0; i < marcos.size(); i++) out.add((i == 0 ? "  en " : "  llamado desde ") + marcos.get(i));
        return out;
    }

    /** Las dos cosas de una vez: las líneas de la traza, traducida con los .dbg de {@code donde}. */
    public static List<String> lineas(String traza, List<Path> donde) {
        return lineas(marcos(traza, donde));
    }

    private static DbgFile busca(String mod, List<Path> donde) {
        for (Path c : donde) {
            if (c == null) continue;
            Path p = c.resolve(mod + ".dbg");
            if (Files.exists(p)) {
                DbgFile d = DbgFile.load(p.toString());   // null si no se lee: es como no tenerlo, sale la posición
                if (d != null) return d;
            }
        }
        return null;
    }

    private static String fichero(DbgFile dbg, String mod) {
        String fuente = dbg.getSource();
        if (fuente == null || fuente.isEmpty()) return mod + ".bp";
        return Paths.get(fuente.replace('\\', '/')).getFileName().toString();
    }
}
