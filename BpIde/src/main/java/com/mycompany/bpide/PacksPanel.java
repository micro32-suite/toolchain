// ============================================================
// PacksPanel.java
// H3 — panel de gestión de PACKS de la placa: la zona, sus packs y los ficheros
// de cada uno. Autocontenido (Swing a mano, sin .form), como BoardMgrPanel.
//
// Retoques (Eduardo, 23-jul tras probar la v1):
//   - al seleccionar un pack se ven los FICHEROS de dentro.
//   - columna FECHA (la fecha de build del pack: sirve para comparar si el
//     grabado en placa es más viejo que el recién construido en el PC).
//   - ocupación de la zona en bytes y % (la partición se ajusta una vez;
//     el día a día es "¿cuánto me queda?") → barra + resumen.
//
// La tabla muestra la CADENA completa (activos Y tombstones): el tombstone
// sigue ocupando flash hasta compactar, y verlo cuenta la verdad del espacio.
//
// V9 #875 (25-nov) — y ya no habla con la placa: sus botones llaman a los
// comandos de los packs (PacksPlaca), los mismos que puede llamar la IA, y
// enseña lo que ellos leen: si la IA graba un pack con la ventana abierta, se
// ve, con su avance.
// ============================================================
package com.mycompany.bpide;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public final class PacksPanel extends JPanel {

    private static final String TITULO = "Packs de la placa";

    /** Los comandos y los packs de la placa conectada (null: sin ellos, sólo se ve vacío). */
    private Comandos comandos;
    private PacksPlaca packs;
    private final PacksPlaca.Mirador mirador = new PacksPlaca.Mirador() {
        @Override public void zona(BpvmClient.PackTable t) { fill(t); }
        @Override public void avance(String que) { summaryLabel.setText(que); }
    };

    private final JLabel summaryLabel = new JLabel("(sin conexión)");
    private final JProgressBar usageBar = new JProgressBar(0, 100);

    private final DefaultTableModel packModel = new DefaultTableModel(
            new Object[] { "Pack", "Versión", "Fecha", "Tamaño (KB)", "Fich.", "Estado", "CRC" }, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable packTable = new JTable(packModel);
    /** offsets de los packs listados, alineados con las filas de packModel
     *  (el offset identifica al pack: pack_ficheros y pack_borrar). */
    private final List<Long> packOffsets = new ArrayList<>();

    private final DefaultTableModel fileModel = new DefaultTableModel(
            new Object[] { "Fichero", "Tipo", "Tamaño (B)" }, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable fileTable = new JTable(fileModel);
    private final JPanel filePanel = new JPanel(new BorderLayout(4, 4));
    private final JButton btnRefrescar = new JButton("Refrescar");
    private final JButton btnCopiar    = new JButton("Copiar pack a la placa…");
    private final JButton btnBorrar    = new JButton("Borrar");
    private final JButton btnFormatear = new JButton("Formatear zona…");

    public PacksPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        buildUi();
    }

    /** V9 #875 — los comandos con los que trabaja. Desde ahora enseña lo que ellos lean, hasta {@link #cierra}. */
    public void setComandos(Comandos comandos, PacksPlaca packs) {
        this.comandos = comandos;
        this.packs = packs;
        btnRefrescar.setText(comandos.get("packs_listar").textoCorto());   // el texto, el de su comando
        btnCopiar.setText(comandos.get("pack_grabar").textoCorto());
        btnBorrar.setText(comandos.get("pack_borrar").textoCorto());
        btnFormatear.setText(comandos.get("packs_formatear").textoCorto());
        packs.mira(mirador);
    }

    /** La ventana se cierra: ya no mira. */
    public void cierra() {
        if (packs != null) packs.dejaDeMirar(mirador);
    }

    public void attach(BpvmClient c) {
        if (c == null) { summaryLabel.setText("(sin conexión)"); clearAll(); }
        else refresh();
    }

    private void buildUi() {
        /* V7/H8 E1 — el pack con la cabecera o el contenido mal (PACK_LS: crcOk = false, la columna CRC dice MAL), en
         * rojo: lo mismo que el árbol de ficheros (Eduardo: «en rojo, módulos/packs dañados o con un formato no
         * válido»). */
        packTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                           boolean foco, int fila, int col) {
                java.awt.Component c = super.getTableCellRendererComponent(t, v, sel, foco, fila, col);
                Object crc = t.getModel().getValueAt(t.convertRowIndexToModel(fila), t.getModel().getColumnCount() - 1);
                if (!sel) c.setForeground("MAL".equals(crc) ? PicoExplorer.COLOR_MAL : t.getForeground());
                return c;
            }
        });
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        north.add(new JLabel("Packs de la placa:"));
        btnRefrescar.addActionListener(e -> refresh());
        north.add(btnRefrescar);
        btnCopiar.addActionListener(e -> onBurn());
        north.add(btnCopiar);
        btnBorrar.addActionListener(e -> onDelete());
        north.add(btnBorrar);
        btnFormatear.addActionListener(e -> onFormat());
        north.add(btnFormatear);
        add(north, BorderLayout.NORTH);

        JPanel packPanel = new JPanel(new BorderLayout(4, 4));
        packPanel.setBorder(BorderFactory.createTitledBorder("Packs (cadena completa: activos y borrados)"));
        packTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onPackSelected();
        });
        packPanel.add(new JScrollPane(packTable), BorderLayout.CENTER);

        filePanel.setBorder(BorderFactory.createTitledBorder("Ficheros del pack"));
        filePanel.add(new JScrollPane(fileTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, packPanel, filePanel);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        // resumen de ocupación: barra (con % pintado encima) + detalle en bytes
        JPanel south = new JPanel(new BorderLayout(4, 2));
        usageBar.setStringPainted(true);
        usageBar.setString("");
        south.add(usageBar, BorderLayout.NORTH);
        south.add(summaryLabel, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    private void clearAll() {
        packModel.setRowCount(0);
        packOffsets.clear();
        fileModel.setRowCount(0);
        setFilesTitle(null);
        usageBar.setValue(0);
        usageBar.setString("");
    }

    private void setFilesTitle(String packName) {
        filePanel.setBorder(BorderFactory.createTitledBorder(
                packName == null ? "Ficheros del pack (selecciona uno arriba)"
                                 : "Ficheros de '" + packName + "'"));
        filePanel.repaint();
    }

    /** Un comando de los packs, con los parámetros ya dichos; su aviso, si lo hay, sobre este panel. null: sin comandos. */
    private CompletableFuture<Resultado> manda(String id, Object... kv) {
        if (comandos == null) { summaryLabel.setText("(sin conexión)"); return null; }
        Map<String, Object> p = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        p.put("sobre", this);
        return comandos.desdeLaVentana(id, p);
    }

    /** La zona: repuebla la tabla + ocupación. Una placa sin zona de packs aún se muestra como estado, no como error a
     *  gritos. */
    public void refresh() {
        if (comandos == null) { summaryLabel.setText("(sin conexión)"); return; }
        summaryLabel.setText("consultando…");
        manda("packs_listar").thenAccept(r -> { if (r.datos().containsKey("sin_zona")) clearAll(); });
    }

    private void fill(BpvmClient.PackTable t) {
        clearAll();
        for (BpvmClient.PackInfo p : t.packs) {
            packModel.addRow(new Object[] { p.name, p.version, PacksPlaca.fecha(p.date),
                    p.size / 1024L, p.files,
                    p.active ? "activo" : "borrado", p.crcOk ? "OK" : "MAL" });
            packOffsets.add(p.offset);
        }
        long used = t.regionSize - t.free;   // ocupado = cadena entera (tombstones incluidos)
        int pct = t.regionSize > 0 ? (int) Math.round(used * 100.0 / t.regionSize) : 0;
        usageBar.setValue(t.chainOk ? pct : 100);
        usageBar.setString(t.chainOk ? ("ocupado " + pct + "%") : "CADENA CORRUPTA");
        summaryLabel.setText(PacksPlaca.resumen(t));
    }

    /** Selección en la tabla de packs → los ficheros del de esa fila. */
    private void onPackSelected() {
        int row = packTable.getSelectedRow();
        fileModel.setRowCount(0);
        if (row < 0 || row >= packOffsets.size() || comandos == null) { setFilesTitle(null); return; }
        final long off = packOffsets.get(row);
        setFilesTitle(String.valueOf(packModel.getValueAt(row, 0)));
        manda("pack_ficheros", "offset", off).thenAccept(r -> {
            Object fs = r.datos().get("ficheros");
            if (!(fs instanceof List)) return;
            Object total = r.datos().get("total");
            int n = ((List<?>) fs).size();
            if (total instanceof Integer && (Integer) total > n) {   // la placa no los lista todos
                filePanel.setBorder(BorderFactory.createTitledBorder("Ficheros de '" + r.datos().get("pack")
                        + "' (los " + n + " primeros de " + total + ")"));
                filePanel.repaint();
            }
            fileModel.setRowCount(0);
            for (Object o : (List<?>) fs) {
                Map<?, ?> e = (Map<?, ?>) o;
                fileModel.addRow(new Object[] { e.get("nombre"), e.get("tipo"), e.get("bytes") });
            }
        });
    }

    /** "Copiar pack a la placa…": elige un .pack (por defecto el último dir) y lo graba. */
    private void onBurn() {
        if (comandos == null) { summaryLabel.setText("(sin conexión)"); return; }
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(lastBurnDir);
        fc.setDialogTitle("Copiar pack a la placa");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Packs (*.pack)", "pack"));
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        grabarPack(fc.getSelectedFile());
    }

    /** #435 — la grabación, separada de cómo se ELIGE el fichero: el panel de carpetas de {@link BoardMgrPanel} manda aquí
     *  el `.pack` seleccionado con el botón «Añadir». Un solo camino de grabado para las dos entradas (y, desde #875, para
     *  la IA: el comando). */
    public void grabarPack(final java.io.File f) {
        if (f == null || !f.isFile()) { summaryLabel.setText("(no es un fichero)"); return; }
        lastBurnDir = f.getParentFile();
        manda("pack_grabar", "fichero", f.getAbsolutePath());
    }

    /** "Borrar" = tombstone del pack seleccionado: se MARCA borrado (no se borra); el espacio se recupera compactando. */
    private void onDelete() {
        int row = packTable.getSelectedRow();
        if (row < 0 || row >= packOffsets.size() || comandos == null) {
            JOptionPane.showMessageDialog(this, "Selecciona un pack de la lista.", TITULO,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final String name = String.valueOf(packModel.getValueAt(row, 0));
        if ("borrado".equals(String.valueOf(packModel.getValueAt(row, 5)))) {
            JOptionPane.showMessageDialog(this, "'" + name + "' ya está borrado.", TITULO,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "¿Marcar '" + name + "' como borrado?\n"
                + "(No se borra de la flash: el espacio se recupera compactando desde el PC.)",
                "Borrar pack", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        manda("pack_borrar", "offset", packOffsets.get(row));
    }

    /** "Formatear zona…": borra la zona de packs ENTERA. Es el estreno de una
     *  zona recién reparticionada (lleva restos de littlefs/FS viejo y el scan
     *  la ve corrupta, correctamente) o la recuperación de una cadena rota.
     *  Confirmación explícita SIEMPRE — borra todos los packs. */
    private void onFormat() {
        if (comandos == null) { summaryLabel.setText("(sin conexión)"); return; }
        int ok = JOptionPane.showConfirmDialog(this,
                "Esto BORRA TODOS los packs de la placa (la zona entera).\n"
                + "Es lo correcto para estrenar una zona recién particionada\n"
                + "(contiene restos de su uso anterior) o recuperar una cadena rota.\n\n"
                + "¿Formatear la zona de packs?",
                "Formatear zona de packs", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        manda("packs_formatear");
    }

    private static java.io.File lastBurnDir = null;   // recordar carpeta entre usos
}
