package basicplus.frontend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 #860 (H1.9) — los mensajes que enseñan: además del fallo, cómo se escribe. Los casos son los de la sonda de
 * bp-analisis (una IA que escribe BP y JC sin haberlos visto, con las costumbres de Java) y los que salieron al mirarlos.
 */
class MensajesQueEnsenanTest {

    /** Lo que dice el compilador de un fuente que no compila. */
    private static String compila(Path tmp, String ext, String fuente) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.write(src.resolve("T" + ext), fuente.getBytes(StandardCharsets.UTF_8));
        BpBuild b = new BpBuild();
        b.projectDir = tmp.toString();
        b.sourceDir = src.toString();
        b.outDir = out.toString();
        b.main = "T";
        File std = new File("../../bpstdlib");
        Assumptions.assumeTrue(new File(std, "Core.mod").isFile(), "hace falta ../../bpstdlib/Core.mod");
        b.dependencies.add(std.getAbsolutePath());
        PrintStream out0 = System.out, err0 = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, "UTF-8");
        try {
            System.setOut(ps);
            System.setErr(ps);
            assertFalse(Main.buildProject(b, "mivm", false), "tenía que fallar: " + fuente);
        } finally {
            System.setOut(out0);
            System.setErr(err0);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void bp(Path tmp, String cuerpo, String... dichos) throws IOException {
        String m = compila(tmp, ".bp", "module T\n  import Collections\n  function Main()\n" + cuerpo + "  end Main\nend T\n");
        for (String d : dichos) assertTrue(m.contains(d), "se esperaba «" + d + "» en:\n" + m);
    }

    private static void jc(Path tmp, String cuerpo, String... dichos) throws IOException {
        String m = compila(tmp, ".jc", "module T\n{\n   import Collections;\n   function void Main()\n   {\n" + cuerpo + "   }\n}\n");
        for (String d : dichos) assertTrue(m.contains(d), "se esperaba «" + d + "» en:\n" + m);
    }

    // ---------------------------------------------------------------- BP

    @Test @DisplayName("BP: ninguna sobrecarga encaja — las que hay, y la caja de un número")
    void sobrecargas(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var m: Collections.Map := Collections.stringMap()\n    m.put(\"a\", 5)\n",
                "acepta los argumentos (string, integer); las que hay: ", "put(string, Object)",
                "Un número va en su caja, Integer(x)");
        bp(tmp, "    var m: Collections.Map := Collections.stringMap()\n    m.put(1, 2, 3)\n", "; las que hay: ");
    }

    @Test @DisplayName("BP: un argumento que va en su caja")
    void caja(@TempDir Path tmp) throws IOException {
        String m = compila(tmp, ".bp", "module T\n  import Core\n  function f(o: Object)\n    print o.toString()\n  end f\n"
                + "  function Main()\n    f(5)\n  end Main\nend T\n");
        assertTrue(m.contains("'integer' no asignable a 'Object': un número va en su caja, Integer(x)"), m);
    }

    @Test @DisplayName("BP: lo protegido de una clase de otro módulo — lo que tiene público")
    void protegido(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var l: List<Integer> := List()\n    print l.size()\n",
                "'size' es protegido de 'List'", "lo público de 'List' es add(), ", "length()");
    }

    @Test @DisplayName("BP: lo protegido de una clase de aquí — como hasta ahora")
    void protegidoDeAqui(@TempDir Path tmp) throws IOException {
        String m = compila(tmp, ".bp", "module T\n  class A\n    function f()\n      print 1\n    end f\n  end A\n"
                + "  function Main()\n    var a: A := A()\n    a.f()\n  end Main\nend T\n");
        assertTrue(m.contains("'f' es protegido de 'A': lo ven esa clase y sus descendientes, no desde fuera. Hazlo 'public'"), m);
    }

    @Test @DisplayName("BP: un miembro que no existe — lo público de la clase")
    void miembro(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var e: Exception := Exception(\"x\")\n    print e.getMessage()\n",
                "'Exception' no tiene miembro de instancia 'getMessage': lo público de 'Exception' es msg, toString()");
        bp(tmp, "    print Integer.parseInt(\"3\")\n", "no tiene miembro estático 'parseInt': es una función, parseInt(");
    }

    @Test @DisplayName("BP: un string no es un objeto — la función que es")
    void cadenas(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var s: string := \"a\"\n    print s.length()\n    print s.toUpperCase()\n    print s.trim()\n"
                + "    print s.equals(\"b\")\n    print s.loquesea()\n",
                "se escribe len(s)", "es una función, upper(s)", "es una función, trim(s)", "se comparan con =,",
                "las cadenas se tratan con funciones, len(s)");
        bp(tmp, "    var n: integer := 3\n    print n.toString()\n    var v: integer[] := [1, 2]\n    print v.length\n",
                "es una función, intToString(x)", "se escribe len(v)");
    }

    @Test @DisplayName("BP: un nombre de un módulo que se importa, o que hay y no se importa")
    void nombres(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var m: Collections.Map := Map()\n", "no se puede llamar a 'Map': está en 'Collections', y se escribe Collections.Map");
        bp(tmp, "    print Math.PI\n", "hay un módulo 'Math'; falta import Math");
        bp(tmp, "    System.out.println(\"x\")\n", "para escribir en la consola, print x");
    }

    @Test @DisplayName("BP: un tipo con el nombre de otro lenguaje")
    void tipos(@TempDir Path tmp) throws IOException {
        bp(tmp, "    var i: int := 3\n", "tipo 'int' no encontrado: en BP es 'integer'");
        bp(tmp, "    var b: bool := true\n", "en BP es 'boolean'");
        bp(tmp, "    var c: char := 3\n", "en BP no hay char; el código de un carácter es un integer");
        bp(tmp, "    var l: ArrayList := List()\n", "en BP es 'List'");
        bp(tmp, "    var h: HashMap := null\n", "en BP es 'Collections.Map'");
    }

    @Test @DisplayName("BP: lo que escribe quien viene de otro BASIC, y de Java")
    void otrosBasic(@TempDir Path tmp) throws IOException {
        bp(tmp, "    dim x as integer\n", "en BP una variable se declara con 'var': var x: integer");
        bp(tmp, "    int z := 3\n", "en BP una variable se declara con 'var': var z: integer");
        bp(tmp, "    let y = 3\n", "en BP se asigna sin 'let': y := ");
        bp(tmp, "    var x: integer = 3\n", "'=' no asigna en BP: usa ':='");
        bp(tmp, "    for i = 0 to 3 do\n      print i\n    next i\n", "'=' no asigna en BP: usa ':='");
        bp(tmp, "    var x: integer := 1\n    x++\n", "en BP se suma y se resta con += y -=: x += 1");
        bp(tmp, "    print \"a\" & \"b\"\n", "las cadenas se unen con +");
        String m = compila(tmp, ".bp", "module T\n  const K: integer = 3\n  function Main()\n    print K\n  end Main\nend T\n");
        assertTrue(m.contains("'=' no asigna en BP"), m);
        m = compila(tmp, ".bp", "module T\n  sub Main()\n    print 1\n  end sub\nend T\n");
        assertTrue(m.contains("en BP es 'function', sin tipo si no devuelve nada"), m);
        m = compila(tmp, ".bp", "module T\n  class A\n    protected var x: integer\n  end A\n  function Main()\n  end Main\nend T\n");
        assertTrue(m.contains("en BP un miembro sin marca ya es protegido"), m);
    }

    @Test @DisplayName("BP: un = en una declaración no arrastra más errores")
    void igualSinCascada(@TempDir Path tmp) throws IOException {
        String m = compila(tmp, ".bp", "module T\n  function Main()\n    var x: integer = 3\n    for i = 0 to x do\n      print i\n"
                + "    next i\n  end Main\nend T\n");
        assertEquals(2, m.split("error sintáctico", -1).length - 1, m);
        assertFalse(m.contains("error semántico"), m);
    }

    @Test @DisplayName("BP: final no va en una variable (antes se tomaba, y no hacía nada)")
    void finalDeBp(@TempDir Path tmp) throws IOException {
        String m = compila(tmp, ".bp", "module T\n  final var k: integer := 3\n  function Main()\n    print k\n  end Main\nend T\n");
        assertTrue(m.contains("lo que no cambia es una constante: const K: integer := 3"), m);
    }

    // ---------------------------------------------------------------- JC, la sintaxis: el primer error

    private static void jcLee(String fuente, String dicho) {
        JcLexer.Error e = assertThrows(JcLexer.Error.class, () -> JcParser.leer(fuente), fuente);
        assertTrue(e.getMessage().contains(dicho), "se esperaba «" + dicho + "»: " + e.getMessage());
    }

    private static void jcMiembro(String miembro, String dicho) {
        jcLee("module M\n{\n   public class K\n   {\n      " + miembro + "\n   }\n}\n", dicho);
    }

    private static void jcSentencia(String s, String dicho) {
        jcLee("module M\n{\n   function void Main()\n   {\n      " + s + "\n   }\n}\n", dicho);
    }

    @Test @DisplayName("JC: la clase base y las interfaces, tras ':'")
    void jcHerencia() {
        jcLee("module M\n{\n   class P extends Item\n   {\n   }\n}\n", "class P : Item");
        jcLee("module M\n{\n   class P extends Item implements Forma, Otra\n   {\n   }\n}\n", "class P : Item, Forma, Otra");
        jcLee("module M\n{\n   class P implements Lib.Forma\n   {\n   }\n}\n", "class P : Lib.Forma");
        jcLee("module M\n{\n   class P : Item implements Forma\n   {\n   }\n}\n", "en la misma lista");
        jcLee("module M\n{\n   interface A extends B\n   {\n   }\n}\n", "interface A : B");
    }

    @Test @DisplayName("JC: 'function' delante, en un constructor, en un método y en una función")
    void jcFunction() {
        jcMiembro("public K(int x) { }", "un constructor lleva 'function' delante: function K(…)");
        jcMiembro("K() { }", "un constructor lleva 'function' delante: function K(…)");
        jcMiembro("public int getX() { return 1; }", "un método lleva 'function' delante del tipo: function int getX(…)");
        jcMiembro("public List<Integer> todos() { return null; }", "function List<Integer> todos(…)");
        jcMiembro("public void f() { }", "un método lleva 'function' delante del tipo: function void f(…)");
        jcLee("module M\n{\n   int doble(int x) { return 2 * x; }\n}\n", "una función lleva 'function' delante del tipo: function int doble(…)");
        jcLee("module M\n{\n   void Main() { }\n}\n", "function void Main(…)");
        jcLee("module M\n{\n   public static void main(String[] args) { }\n}\n", "una función del módulo no lo lleva");
    }

    @Test @DisplayName("JC: @Override, abstract, override, virtual y synchronized")
    void jcModificadores() {
        jcMiembro("@Override\n      public function int f() { return 1; }", "@override{}");
        jcMiembro("public abstract function int f() { }", "JC no tiene 'abstract'");
        jcMiembro("public override string ToString() { return \"a\"; }", "en JC no va 'override'");
        jcMiembro("public virtual void f() { }", "en JC no va 'virtual'");
        jcMiembro("public synchronized void f() { }", "un Mutex");
        // y un miembro que se llame así sigue valiendo
        assertNotNull(JcParser.leer("module M\n{\n   class K\n   {\n      int override, virtual;\n   }\n}\n"));
    }

    @Test @DisplayName("JC: for (T x : c) es foreach")
    void jcForEach() {
        jcSentencia("for (Item it : items) { }", "foreach (var it in items)");
        jcSentencia("for (final Item it : items) { }", "foreach (var it in items)");
        jcSentencia("for (var it : items) { }", "foreach (var it in items)");
        jcSentencia("for (int x : v) print(x);", "foreach (var x in v)");
        jcSentencia("for (List<Item> l : todos()) { }", "foreach (var l in …)");
        assertNotNull(JcParser.leer("module M\n{\n   function void Main()\n   {\n      for (int i = 0; i <= 3; i++) print(i);\n   }\n}\n"));
    }

    @Test @DisplayName("JC: var x = 5, final y int v[]")
    void jcVariables() {
        jcSentencia("var x = 5;", "no con 'var': int x = …");
        jcSentencia("var s = \"a\";", "string s = …");
        jcSentencia("var l = new List<Integer>();", "List l = …");
        jcMiembro("var x = 2.5;", "double x = …");
        jcSentencia("final int K = 3;", "const int K = …");
        jcMiembro("private final int K = 3;", "const int K = …");
        jcMiembro("public static final string K = \"a\";", "const string K = …");
        jcLee("module M\n{\n   final int K = 3;\n}\n", "const int K = …");
        jcSentencia("int v[] = new int[4];", "los corchetes sin tamaño van con el tipo: int[] v");
        jcSentencia("int[] w = {1, 2, 3};", "entre corchetes: [1, 2, 3]");
        jcSentencia("int[] w = new int[] {1, 2, 3};", "sin new: [1, 2, 3]");
        jcSentencia("List<Integer> l = new List<>();", "JC no tiene el <> de Java");
        assertNotNull(JcParser.leer("module M\n{\n   class K\n   {\n      public final int x { get; set; }\n      public final function void f()\n      {\n      }\n   }\n}\n"));
    }

    @Test @DisplayName("JC: System.out.println, x++ y una asignación dentro de una expresión, una lambda")
    void jcSentencias() {
        jcSentencia("System.out.println(\"x\");", "en JC es println(x)");
        jcSentencia("System.err.print(\"x\");", "en JC es println(x)");
        jcSentencia("int y = x++;", "no dentro de una expresión");
        jcSentencia("f(x--);", "no dentro de una expresión");
        jcSentencia("a = b = 3;", "una asignación es una sentencia");
        jcSentencia("if (a = 3) print(a);", "para comparar, ==");
        jcSentencia("Thread t = new Thread(() -> f());", "JC no tiene lambdas");
        jcSentencia("b.alPulsar(e => f(e));", "JC no tiene lambdas");
        jcSentencia("b.alPulsar((a, e) -> { f(e); });", "JC no tiene lambdas");
        jcSentencia("if (a instanceof K) print(1);", "en JC es 'is'");
        assertNotNull(JcParser.leer("module M\n{\n   function void Main()\n   {\n      int x = 3;\n      x++;\n      print((x) - 1, x - -1);\n"
                + "      bool b = x > 2 && (x) >= 1;\n   }\n}\n"));
    }

    @Test @DisplayName("JC: el constructor de la base o el otro de la misma clase, en la cabecera")
    void jcConstructores() {
        jcMiembro("public function K(int x)\n      {\n         super(x);\n      }", "function K(…) : base(…)");
        jcMiembro("public function K(int x)\n      {\n         this();\n      }", "function K(…) : this(…)");
        jcMiembro("public function void f() throws Exception { }", "'throws' no va");
        jcMiembro("public function int f() => 3;", "JC no tiene los cuerpos con =>");
        jcMiembro("public function void f(final int x) { }", "un parámetro no lleva 'final'");
    }

    @Test @DisplayName("JC: la cabecera de un fichero de Java, los import y los genéricos propios")
    void jcCabecera() {
        jcLee("package es.prueba;\nmodule M\n{\n}\n", "un .jc es un módulo, module Nombre { … }");
        jcLee("public class K\n{\n}\n", "un .jc es un módulo, module Nombre { … }");
        jcLee("module M\n{\n   import java.util.*;\n}\n", "import nombra un módulo, sin '*'");
        jcLee("module M\n{\n   using System;\n}\n", "se importa con import");
        jcLee("module M\n{\n   class Caja<T>\n   {\n   }\n}\n", "@element{Tipo}");
        jcMiembro("public <T> void f(T x) { }", "@element{Tipo}");
    }

    // ---------------------------------------------------------------- JC, lo que ve el análisis

    @Test @DisplayName("JC: String, char, la consola y las cadenas")
    void jcAnalisis(@TempDir Path tmp) throws IOException {
        jc(tmp, "      String s = \"a\";\n", "tipo 'String' no encontrado: en JC es 'string'");
        jc(tmp, "      char c = 'a';\n", "en JC no hay char; 'a' es su código, un int");
        jc(tmp, "      Console.WriteLine(\"a\");\n", "para escribir en la consola, println(x)");
        jc(tmp, "      string s = \"a\";\n      println(s.length(), s.equals(\"b\"));\n", "se escribe len(s)", "se comparan con ==,");
        jc(tmp, "      string s = String.valueOf(3);\n", "las cadenas se tratan con funciones; de un número, intToString(n)");
        jc(tmp, "      Collections.Map m = Collections.stringMap();\n      m.put(\"a\", 5);\n", "Un número va en su caja, new Integer(x)");
    }
}
