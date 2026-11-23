// ============================================================
// Instalacion.java — V9 H1.2 (#853): dónde está la instalación y su BpVM.cfg
// ============================================================
package basicplus.bp;

import edu.bpgenvm.config.VmConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * La INSTALACIÓN de las herramientas: la carpeta del jar (en el ZIP, su raíz; en el repositorio, el {@code target} de
 * su proyecto) y el {@code BpVM.cfg} que viaja con ella. Es el respaldo de cuando un fuente no tiene BpVM.cfg, ni
 * subiendo desde él, y se trabaja desde una carpeta sin él: lo usan el IDE (V9 #867) y la orden {@code bp} (#853), y
 * por eso vive aquí, en un sitio para los dos.
 */
public final class Instalacion {

    private Instalacion() { }

    /** La carpeta del jar (o de las clases) de {@code c}; null si no se sabe. */
    public static Path dirDe(Class<?> c) {
        try {
            Path aqui = Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(aqui) ? aqui : aqui.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /** V9 #867 — el BpVM.cfg de la instalación en {@code dir}: junto a ella (el ZIP lo trae en su raíz) o subiendo (en
     *  el repositorio, el jar vive en toolchain/…/target y el cfg en la raíz). Si no aparece ninguno, uno con las
     *  carpetas que viajan junto al jar (bpstdlib/, bpdevices/); null si tampoco están. Con sus propios pasos, no con
     *  {@code loadDefaultFor}, que mira antes el cwd. */
    public static VmConfig cfg(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path f = d.resolve(VmConfig.DEFAULT_FILENAME);
            if (Files.isRegularFile(f)) {
                try { return VmConfig.load(f); }
                catch (IOException ex) {
                    System.err.println("Advertencia: no se pudo leer " + f + ": " + ex.getMessage());
                    break;   // existe pero ilegible: como loadDefaultFor, no se sigue subiendo
                }
            }
        }
        if (dir == null) return null;
        Path lib = dir.resolve("bpstdlib");
        if (!Files.isDirectory(lib)) return null;
        VmConfig c = VmConfig.defaults();
        c.stdlibDir = lib.toString();
        Path dev = dir.resolve("bpdevices");
        if (Files.isDirectory(dev)) c.devicesDir = dev.toString();
        return c;
    }

    /** V9 #867 — el BpVM.cfg que manda para {@code ancla} (un fuente, un .mod, una carpeta; null = sólo el cwd): el de
     *  siempre ({@code loadDefaultFor}: el cwd, o subiendo desde el ancla), y si no aparece ninguno, el de la
     *  instalación ({@code deLaInstalacion}). El compilador hace lo mismo (Main.setCfgDelHospedador): la stdlib con la
     *  que se compila y la que se le da a la VM son la misma. Nunca null. */
    public static VmConfig cfgPara(Path ancla, VmConfig deLaInstalacion) {
        VmConfig cfg = VmConfig.loadDefaultFor(ancla);
        if (cfg.sourcePath != null) return cfg;
        return deLaInstalacion != null ? deLaInstalacion : cfg;
    }
}
