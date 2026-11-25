// ============================================================
// EnvDialog.java — #435: las variables de entorno, en su propio diálogo.
//
// Vivían en la mitad de arriba de BoardMgrPanel, compartiendo un split vertical
// con las particiones. Se mudan enteras (tabla + checkbox de PSRAM + añadir /
// editar / borrar) por decisión de Eduardo (17-ago): la ventana de la placa se
// queda con particiones y packs, y el hueco lo ocupa el panel de carpetas desde
// el que se añaden packs.
//
// V9 #876 (25-nov) — y el diálogo ya no habla con la placa: sus botones llaman a
// los comandos del entorno (EntornoPlaca), los mismos que puede llamar la IA, y la
// tabla enseña lo que ellos leen: si la IA cambia una variable con el diálogo
// abierto, se ve.
// ============================================================
package com.mycompany.bpide;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

public final class EnvDialog extends JDialog {

    /** La clave de la casilla, en su forma canónica (la placa no distingue mayúsculas). */
    private static final String PSRAM_KEY = "psram";

    private final Comandos comandos;
    private final EntornoPlaca entorno;
    private final Runnable alCambiar;      // el panel de placa se repinta (part.* comparte env)
    private final Consumer<List<BpvmClient.EnvVar>> loQueHay = this::pinta;
    private boolean updatingUi = false;

    private final JCheckBox psramCheck = new JCheckBox("PSRAM presente (0/1)");
    private final DefaultTableModel envModel =
            new DefaultTableModel(new Object[] { "Clave", "Valor" }, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTable envTable = new JTable(envModel);

    public EnvDialog(Window duenyo, Comandos comandos, EntornoPlaca entorno, Runnable alCambiar) {
        super(duenyo, "Variables de entorno", ModalityType.MODELESS);
        this.comandos = comandos;
        this.entorno = entorno;
        this.alCambiar = (alCambiar != null) ? alCambiar : () -> { };
        buildUi();
        setSize(460, 340);
        setLocationRelativeTo(duenyo);
        entorno.mira(loQueHay);
        refresh();
    }

    private void buildUi() {
        JPanel raiz = new JPanel(new BorderLayout(4, 4));
        raiz.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        psramCheck.addActionListener(e -> onPsramToggle());
        top.add(psramCheck);
        raiz.add(top, BorderLayout.NORTH);

        raiz.add(new JScrollPane(envTable), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton set = boton("entorno_poner");
        JButton del = boton("entorno_borrar");
        JButton ref = boton("entorno_listar");
        JButton cerrar = new JButton("Cerrar");
        set.addActionListener(e -> onEnvSet());
        del.addActionListener(e -> onEnvDel());
        ref.addActionListener(e -> refresh());
        cerrar.addActionListener(e -> dispose());
        btns.add(set); btns.add(del); btns.add(ref); btns.add(cerrar);
        raiz.add(btns, BorderLayout.SOUTH);

        setContentPane(raiz);
    }

    /** El botón de un comando: su texto, el del comando. */
    private JButton boton(String id) {
        return new JButton(comandos.get(id).textoCorto());
    }

    @Override
    public void dispose() {
        entorno.dejaDeMirar(loQueHay);
        super.dispose();
    }

    /** Lo que acaba de leerse de la placa (lo haya pedido este diálogo, otro o la IA), en la tabla y en la casilla. */
    private void pinta(List<BpvmClient.EnvVar> env) {
        updatingUi = true;
        try {
            envModel.setRowCount(0);
            boolean psram = false;
            for (BpvmClient.EnvVar e : env) {
                envModel.addRow(new Object[] { e.key, e.value });
                if (PSRAM_KEY.equalsIgnoreCase(e.key)) psram = "1".equals(e.value.trim());
            }
            psramCheck.setSelected(psram);
        } finally {
            updatingUi = false;
        }
    }

    /** Un comando del entorno, con los parámetros ya dichos; su aviso, si lo hay, sobre este diálogo. */
    private CompletableFuture<Resultado> manda(String id, Map<String, Object> p, boolean cambia) {
        p.put("sobre", this);
        CompletableFuture<Resultado> f = comandos.desdeLaVentana(id, p);
        f.thenAccept(r -> { if (cambia && r.ok) alCambiar.run(); });
        return f;
    }

    public void refresh() {
        manda("entorno_listar", new HashMap<>(), false);
    }

    private CompletableFuture<Resultado> pone(String clave, String valor) {
        Map<String, Object> p = new HashMap<>();
        p.put("clave", clave);
        p.put("valor", valor);
        return manda("entorno_poner", p, true);
    }

    private void onPsramToggle() {
        if (updatingUi) return;
        final boolean si = psramCheck.isSelected();
        pone(PSRAM_KEY, si ? "1" : "0").thenAccept(r -> { if (!r.ok) psramCheck.setSelected(!si); });   // no se pudo: como estaba
    }

    private void onEnvSet() {
        int row = envTable.getSelectedRow();
        String defKey = row >= 0 ? String.valueOf(envModel.getValueAt(row, 0)) : "";
        String defVal = row >= 0 ? String.valueOf(envModel.getValueAt(row, 1)) : "";
        JTextField keyF = new JTextField(defKey, 16);
        JTextField valF = new JTextField(defVal, 16);
        JPanel form = new JPanel(new GridLayout(2, 2, 4, 4));
        form.add(new JLabel("Clave:"));  form.add(keyF);
        form.add(new JLabel("Valor:"));  form.add(valF);
        int ok = JOptionPane.showConfirmDialog(this, form, "Variable de entorno",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        if (keyF.getText().trim().isEmpty()) return;
        pone(keyF.getText(), valF.getText());
    }

    private void onEnvDel() {
        int row = envTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Selecciona una variable para borrar.", "Entorno",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String key = String.valueOf(envModel.getValueAt(row, 0));
        if (JOptionPane.showConfirmDialog(this, "¿Borrar la variable '" + key + "'?",
                "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        Map<String, Object> p = new HashMap<>();
        p.put("clave", key);
        manda("entorno_borrar", p, true);
    }
}
