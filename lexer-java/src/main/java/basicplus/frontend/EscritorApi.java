// ============================================================
// EscritorApi.java — V9 H1.7 (#858): la API de un módulo, en BP
// ============================================================
package basicplus.frontend;

import basicplus.frontend.ModuleInterface.ClassSig;
import basicplus.frontend.ModuleInterface.ConstSig;
import basicplus.frontend.ModuleInterface.EnumSig;
import basicplus.frontend.ModuleInterface.EventSig;
import basicplus.frontend.ModuleInterface.FieldSig;
import basicplus.frontend.ModuleInterface.FuncSig;
import basicplus.frontend.ModuleInterface.ParamSig;
import basicplus.frontend.ModuleInterface.PropSig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * La interfaz de un módulo —la que lleva su {@code .mod}, {@link ModuleInterface}— escrita en BP: el módulo sin los
 * cuerpos, cada declaración como se escribe. Es lo que imprime {@code bp api <Módulo>} (V9 H1.7, {@code #858}; Eduardo,
 * 23-nov: «Si»): el índice de la API sin leer la referencia ni los fuentes, y de los módulos que no traen fuente (los de
 * un pack: {@code SQLite}, {@code Orm}), lo único.
 *
 * <p>Lo público, sin lo del sistema: los nombres que empiezan por {@code __} son las primitivas de la VM y lo que
 * sintetiza el compilador, y nadie más puede declararlos (manual §3.2); en {@code Gui} son 91 de sus funciones. Con
 * {@code todo}, todo lo que lleva la interfaz: también eso y lo que ve quien hereda (los campos y lo que no lleva
 * modificador; lo privado no está en la interfaz). Sin {@code Core.} delante de lo de {@code Core}: todo módulo lo
 * importa solo, y así se escribe. Las funciones, las clases y las demás declaraciones del módulo, por nombre (en la
 * interfaz van en el orden de una tabla); lo de dentro de una clase, en el suyo. Sin comentarios: el {@code .mod} no los
 * lleva.
 */
public final class EscritorApi {

    private EscritorApi() { }

    /** ¿Un nombre del sistema (manual §3.2)? */
    private static boolean delSistema(String nombre) {
        return nombre.startsWith("__");
    }

    /** La API de {@code mi}, con una primera línea que dice de dónde salió ({@code origen}; null, sin ella). Líneas con
     *  {@code \n}. */
    public static String escribe(ModuleInterface mi, String origen, boolean todo) {
        StringBuilder sb = new StringBuilder();
        if (origen != null) sb.append("// ").append(origen).append('\n');
        if (mi.library != null && !mi.library.isEmpty()) sb.append("library \"").append(mi.library).append("\"\n");
        sb.append("module ");
        if (mi.isInterface) sb.append("interface ");
        sb.append(mi.moduleName);
        if (mi.isInterface && mi.extendsName != null) sb.append(" extends ").append(mi.extendsName);
        if (!mi.isInterface && mi.implementsName != null) sb.append(" implements ").append(mi.implementsName);
        sb.append('\n');

        List<String> bloque = new ArrayList<>();
        for (FuncSig f : porNombre(mi.functions, f -> f.name)) {
            if (todo || (f.isPublic && !delSistema(f.name)))
                bloque.add("  " + (f.isPublic ? "public " : "") + "function " + firma(f, null));
        }
        parrafo(sb, bloque);

        bloque = new ArrayList<>();
        for (ConstSig c : porNombre(mi.consts, c -> c.name))
            if (todo || !delSistema(c.name))
                bloque.add("  public const " + c.name + ": " + tipo(c.type) + " := " + literal(c.value, c.type));
        for (PropSig p : porNombre(mi.properties, p -> p.name))
            if (todo || !delSistema(p.name))
                bloque.add("  public " + (p.isSync ? "sync " : "") + "property " + p.name + ": " + tipo(p.type));
        parrafo(sb, bloque);

        for (EnumSig e : porNombre(mi.enums, e -> e.name)) {
            if (!todo && delSistema(e.name)) continue;
            bloque = new ArrayList<>();
            bloque.add("  public enum " + e.name);
            for (Map.Entry<String, Long> kv : e.values.entrySet())
                bloque.add("    " + kv.getKey() + " := " + kv.getValue());
            bloque.add("  end " + e.name);
            parrafo(sb, bloque);
        }

        for (ClassSig c : porNombre(mi.classes, c -> c.name))
            if (todo || !delSistema(c.name)) parrafo(sb, clase(c, todo));

        sb.append("end ").append(mi.moduleName).append('\n');
        return sb.toString();
    }

    /** ¿Sale este miembro? Público y no del sistema; con {@code todo}, todo lo que lleva la interfaz. */
    private static boolean sale(boolean publico, String nombre, boolean todo) {
        return todo || (publico && !delSistema(nombre));
    }

    /** Una clase o una interfaz de clase, con sus miembros en su orden: los campos (sólo con {@code todo}), los
     *  constructores, las properties, lo estático (constantes y properties), los eventos, los métodos y los métodos
     *  estáticos. */
    private static List<String> clase(ClassSig c, boolean todo) {
        List<String> l = new ArrayList<>();
        if (c.element != null) l.add("  @element{" + tipo(c.element) + "}");
        if (c.isInterface) {
            l.add("  public interface " + c.name + (c.interfaces.isEmpty() ? "" : " extends " + lista(c.interfaces)));
            for (PropSig p : c.properties) l.add("    property " + p.name + ": " + tipo(p.type));
            for (FuncSig m : c.methods) l.add("    function " + firma(m, null));
            l.add("  end " + c.name);
            return l;
        }
        StringBuilder cab = new StringBuilder("  public ");
        if (c.isFinal) cab.append("final ");
        cab.append("class ").append(c.name);
        if (c.baseClassName != null && !c.baseClassName.isEmpty() && !"Core.Object".equals(c.baseClassName)
                && !"Object".equals(c.baseClassName))
            cab.append(" extends ").append(sinCore(c.baseClassName));
        if (!c.interfaces.isEmpty()) cab.append(" implements ").append(lista(c.interfaces));
        l.add(cab.toString());

        if (todo)
            for (FieldSig f : c.fields)
                if (!f.isPrivate) l.add("    var " + (f.isOwner ? "owner " : "") + f.name + ": " + tipo(f.type));

        // Los constructores: la cadena EFECTIVA (los propios o, sin ellos, los heredados), que es como se construye. Sin
        // ninguno de usuario, el de por defecto, sin argumentos.
        if (c.ctorParams == null) {
            l.add("    public function " + c.name + "()");
        } else {
            constructor(l, c.name, c.ctorParams, c.ctorVis, todo);
            for (int k = 0; k < c.ctorOverloads.size(); k++) {
                String vis = k < c.ctorOverloadsVis.size() ? c.ctorOverloadsVis.get(k) : "public";
                constructor(l, c.name, c.ctorOverloads.get(k), vis, todo);
            }
        }

        for (PropSig p : c.properties) {
            if (p.isPrivate || !sale(p.isPublic, p.name, todo)) continue;
            l.add("    " + (p.isPublic ? "public " : "") + (p.isFinal ? "final " : "") + (p.isSync ? "sync " : "")
                    + "property " + p.name + ": " + tipo(p.type));
        }
        for (ConstSig k : porNombre(c.staticConsts, k -> k.name))   // como las del módulo: vienen en el orden de una tabla
            if (sale(true, k.name, todo))
                l.add("    public const " + c.name + "." + k.name + ": " + tipo(k.type) + " := " + literal(k.value, k.type));
        for (PropSig p : c.staticProps) {
            if (p.isPrivate || !sale(p.isPublic, p.name, todo)) continue;
            l.add("    " + (p.isPublic ? "public " : "") + (p.isFinal ? "final " : "")
                    + "property " + c.name + "." + p.name + ": " + tipo(p.type));
        }
        // Un evento es siempre público (el parser rechaza el modificador): se escribe sin él.
        for (EventSig e : c.events) {
            if (!sale(true, e.name, todo)) continue;
            if (e.opaque) l.add("    event " + e.name + "   // su firma lleva un tipo que no sale del módulo");
            else l.add("    event " + e.name + "(" + parametros(e.params) + ")");
        }
        for (FuncSig m : c.methods) {
            if (!sale(m.isPublic, m.name, todo)) continue;
            l.add("    " + (m.isPublic ? "public " : "") + (m.isFinal ? "final " : "") + "function " + firma(m, null));
        }
        for (FuncSig m : c.staticMethods) {
            if (!sale(m.isPublic, m.name, todo)) continue;
            l.add("    " + (m.isPublic ? "public " : "") + (m.isFinal ? "final " : "") + "function " + firma(m, c.name));
        }
        l.add("  end " + c.name);
        return l;
    }

    private static void constructor(List<String> l, String clase, List<ParamSig> params, String vis, boolean todo) {
        if ("private".equals(vis)) return;
        boolean publico = !"protected".equals(vis);
        if (!publico && !todo) return;
        l.add("    " + (publico ? "public " : "") + "function " + clase + "(" + parametros(params) + ")");
    }

    /** `nombre(params): tipo` (sin «: tipo» si no devuelve nada); con {@code clase}, el nombre cualificado de un
     *  estático (`Clase.nombre`), como se declara. */
    private static String firma(FuncSig f, String clase) {
        StringBuilder sb = new StringBuilder();
        if (clase != null) sb.append(clase).append('.');
        sb.append(f.name).append('(').append(parametros(f.params)).append(')');
        if (f.returnType != null && !(f.returnType instanceof BpType.VoidType)) sb.append(": ").append(tipo(f.returnType));
        return sb.toString();
    }

    private static String parametros(List<ParamSig> ps) {
        StringBuilder sb = new StringBuilder();
        if (ps == null) return "";
        for (int i = 0; i < ps.size(); i++) {
            ParamSig p = ps.get(i);
            if (i > 0) sb.append(", ");
            if (p.isVar) sb.append("var ");
            sb.append(p.name).append(": ").append(tipo(p.type));
            if (p.defaultValue != null) sb.append(" := ").append(literal(p.defaultValue, p.type));
        }
        return sb.toString();
    }

    /** El tipo como se escribe en BP: el que el compilador enseña a las personas ({@code byte}, {@code word},
     *  {@code (boolean, integer)}), sin {@code Core.} delante. */
    static String tipo(BpType t) {
        if (t == null || t instanceof BpType.VoidType) return "void";
        if (t instanceof BpType.ArrayType) return tipo(((BpType.ArrayType) t).element) + "[]";
        if (t instanceof BpType.TupleType) {
            StringBuilder sb = new StringBuilder("(");
            List<BpType> els = ((BpType.TupleType) t).elements;
            for (int i = 0; i < els.size(); i++) sb.append(i > 0 ? ", " : "").append(tipo(els.get(i)));
            return sb.append(')').toString();
        }
        if (t instanceof BpType.UnresolvedClassRef) {
            BpType.UnresolvedClassRef r = (BpType.UnresolvedClassRef) t;
            StringBuilder sb = new StringBuilder(sinCore(r.name));
            if (r.argumentos != null) {
                sb.append('<');
                for (int i = 0; i < r.argumentos.size(); i++) sb.append(i > 0 ? ", " : "").append(tipo(r.argumentos.get(i)));
                sb.append('>');
            }
            return sb.toString();
        }
        if (t instanceof BpType.UnresolvedEnumRef) return sinCore(((BpType.UnresolvedEnumRef) t).name);
        return t.display();
    }

    private static String sinCore(String nombre) {
        return nombre.startsWith("Core.") ? nombre.substring("Core.".length()) : nombre;
    }

    private static String lista(List<String> nombres) {
        StringBuilder sb = new StringBuilder();
        for (String n : nombres) sb.append(sb.length() > 0 ? ", " : "").append(sinCore(n));
        return sb.toString();
    }

    /** Un valor como se escribe en BP para su tipo: {@code 0L} un long, {@code 0.0d} un double, el texto entre comillas
     *  con sus escapes. */
    private static String literal(Object v, BpType tipo) {
        if (v instanceof String) {
            StringBuilder sb = new StringBuilder("\"");
            for (char ch : ((String) v).toCharArray()) {
                switch (ch) {
                    case '\\': sb.append("\\\\"); break;
                    case '"':  sb.append("\\\""); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\0': sb.append("\\0"); break;
                    default:   sb.append(ch);
                }
            }
            return sb.append('"').toString();
        }
        String s = String.valueOf(v);
        if (tipo instanceof BpType.PrimitiveType) {
            BpType.PrimitiveType.Kind k = ((BpType.PrimitiveType) tipo).tag;
            if (k == BpType.PrimitiveType.Kind.LONG && (v instanceof Long || v instanceof Integer)) return s + "L";
            if (k == BpType.PrimitiveType.Kind.DOUBLE && (v instanceof Double || v instanceof Float)) return s + "d";
        }
        return s;
    }

    /** Un grupo de líneas, con una en blanco delante; nada si está vacío. */
    private static void parrafo(StringBuilder sb, List<String> lineas) {
        if (lineas.isEmpty()) return;
        sb.append('\n');
        for (String l : lineas) sb.append(l).append('\n');
    }

    private static <T> List<T> porNombre(List<T> xs, java.util.function.Function<T, String> nombre) {
        List<T> l = new ArrayList<>(xs);
        l.sort(Comparator.comparing(nombre, String.CASE_INSENSITIVE_ORDER));   // estable: las sobrecargas, en su orden
        return l;
    }
}
