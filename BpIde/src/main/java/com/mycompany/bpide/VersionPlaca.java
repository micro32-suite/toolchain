// ============================================================
// VersionPlaca.java
// V9 #907 — LA VERSIÓN DE LO QUE HAY AL OTRO LADO DEL CABLE, frente a la de
// esta instalación (25-nov; Eduardo: «que la imagen diga su versión al
// conectar, y el IDE avise si no es la de la instalación»).
//
// La imagen de una placa repone en cada arranque sus módulos de /lib, delante
// de los del Stdlib.pack: con una de otra versión, lo nuevo no enlaza o no va,
// y nada decía por qué (la IA de la prueba final lo diagnosticó mal dos veces).
// La dice su HELLO (`version`, bpvm_version.h en el runtime); una imagen sin
// ese campo es anterior a la 8.5. La VM-Java no la dice: va con el IDE.
// ============================================================
package com.mycompany.bpide;

final class VersionPlaca {

    private VersionPlaca() {}

    /** La primera que la dice: de una que no la dice se sabe que es anterior. */
    private static final String LA_PRIMERA = "8.5";

    private static boolean esLaVmJava(String quien) { return "bpvm-java".equals(quien); }
    private static boolean esElSimulado(String quien) { return "bpvm-sim".equals(quien); }

    /** La de esta instalación: la del manifest de su jar (la del pom, la del título de la ventana); "" desde clases
     *  sueltas, en desarrollo. */
    static String mia() {
        Package p = VersionPlaca.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "" : v;
    }

    /** Cómo se dice tras su nombre: «imagen 8.5», «imagen anterior a la 8.5» o, del micro simulado, «versión 8.5»; "" en la
     *  VM-Java. {@code quien}, su {@code serverName}; {@code version}, "" si no la dice. */
    static String dicha(String quien, String version) {
        if (esLaVmJava(quien)) return "";
        String que = esElSimulado(quien) ? "versión" : "imagen";
        return que + (version.isEmpty() ? " anterior a la " + LA_PRIMERA : " " + version);
    }

    /** La línea del INFO: «8.5 (la de esta instalación)», o cuál es cada una; "" en la VM-Java. */
    static String enElInfo(String quien, String version, String mia) {
        if (esLaVmJava(quien)) return "";
        String suya = version.isEmpty() ? "anterior a la " + LA_PRIMERA + " (no la dice)" : version;
        if (mia.isEmpty()) return suya;
        if (!version.isEmpty() && compara(version, mia) == 0) return suya + " (la de esta instalación)";
        return suya + "; esta instalación es la " + mia;
    }

    /** El aviso, si no es la de esta instalación; null si lo es o si no se puede saber (la VM-Java, o un IDE en clases
     *  sueltas). El remedio depende de quién se ha quedado atrás. */
    static String aviso(String quien, String version, String mia) {
        if (esLaVmJava(quien) || mia.isEmpty()) return null;
        int c = version.isEmpty() ? -1 : compara(version, mia);
        if (c == 0) return null;
        String suya = version.isEmpty() ? "anterior a la " + LA_PRIMERA + " (no dice su versión)" : "la " + version;
        String esto = esElSimulado(quien) ? "el micro simulado es " + (version.isEmpty() ? suya : "de " + suya)
                                          : "la imagen de la placa es " + suya;
        if (c > 0) return esto + ", más nueva que esta instalación (la " + mia + "): pon al día la instalación";
        return esto + " y esta instalación es la " + mia + ": lo nuevo puede no enlazar o no ir; "
                + (esElSimulado(quien) ? "usa el de esta instalación"
                                       : "grábale la de esta instalación (docs/INSTALAR_FIRMWARE.md)");
    }

    /** Por números: 8.5 &lt; 8.10, y 8.5 = 8.5.0. */
    static int compara(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(numero(x, i), numero(y, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int numero(String[] partes, int i) {
        try {
            return i < partes.length ? Integer.parseInt(partes[i].trim()) : 0;
        } catch (NumberFormatException noEsUnNumero) {
            return 0;
        }
    }
}
