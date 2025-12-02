import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

public class QuizApp {

    // Results CSV file
    private static final Path RESULTS_CSV = Paths.get("results.csv");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Quiz data
    private final java.util.List<Question> questions = new ArrayList<>();

    // Swing components
    private JFrame frame;

    // Login / user form
    private JTextField nameField;
    private JTextField emailField;
    private JButton startBtn;

    // Quiz panel components
    private JLabel lblQuestion;
    private JRadioButton[] choiceButtons;
    private ButtonGroup choiceGroup;
    private JButton btnNext;
    private JButton btnPauseResume;
    private JProgressBar timerBar;
    private JLabel lblTimer;
    private JLabel lblQIndex;

    // State
    private int currentIndex = 0;
    private final Map<Integer, Integer> userAnswers = new HashMap<>(); // questionIdx -> choiceIdx
    private javax.swing.Timer questionTimer;
    private int secondsLeft;
    private final int secondsPerQuestion = 20;
    private boolean paused = false;
    private User currentUser;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new QuizApp().initAndShow());
    }

    public QuizApp() {
        // Sample questions - you can replace these or load from a file
        questions.add(new Question("Which data structure uses FIFO order?",
                new String[]{"Stack", "Queue", "Tree", "Graph"}, 1));
        questions.add(new Question("Which keyword is used to inherit a class in Java?",
                new String[]{"implements", "extends", "inherits", "uses"}, 1));
        questions.add(new Question("What is the time complexity of binary search (sorted array)?",
                new String[]{"O(n)", "O(log n)", "O(n log n)", "O(1)"}, 1));
        questions.add(new Question("Which HTML tag is used for the largest heading?",
                new String[]{"<h1>", "<head>", "<header>", "<h6>"}, 0));
        questions.add(new Question("Which of these is NOT a primitive type in Java?",
                new String[]{"int", "boolean", "String", "double"}, 2));
    }

    private void initAndShow() {
        frame = new JFrame("Online Quiz Application");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(820, 520);
        frame.setLocationRelativeTo(null);

        frame.setJMenuBar(buildMenuBar());

        frame.setContentPane(buildLoginPanel());
        frame.setVisible(true);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu app = new JMenu("App");
        JMenuItem adminView = new JMenuItem("Admin: View Results");
        JMenuItem exit = new JMenuItem("Exit");
        app.add(adminView);
        app.addSeparator();
        app.add(exit);
        mb.add(app);

        adminView.addActionListener(e -> showAdminDialog());
        exit.addActionListener(e -> System.exit(0));
        return mb;
    }

    // ---------------- Login / User details UI ----------------
    private JPanel buildLoginPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("<html><h1 style='font-family:sans-serif'>Welcome to the Quiz</h1></html>", SwingConstants.CENTER);
        p.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8, 8, 8, 8);
        g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.LINE_END;
        center.add(new JLabel("Full name:"), g);
        g.gridy = 1;
        center.add(new JLabel("Email:"), g);

        g.gridx = 1; g.gridy = 0; g.anchor = GridBagConstraints.LINE_START;
        nameField = new JTextField(28);
        center.add(nameField, g);
        g.gridy = 1;
        emailField = new JTextField(28);
        center.add(emailField, g);

        g.gridy = 2; g.gridx = 0; g.gridwidth = 2; g.anchor = GridBagConstraints.CENTER;
        startBtn = new JButton("Start Quiz");
        startBtn.setPreferredSize(new Dimension(160, 36));
        center.add(startBtn, g);

        p.add(center, BorderLayout.CENTER);

        JLabel info = new JLabel("<html><i>Note: Each question has a " + secondsPerQuestion + " second timer. You can pause/resume per question.</i></html>", SwingConstants.CENTER);
        info.setBorder(new EmptyBorder(10,10,10,10));
        p.add(info, BorderLayout.SOUTH);

        startBtn.addActionListener(e -> onStartClicked());

        return p;
    }

    private void onStartClicked() {
        String name = nameField.getText().trim();
        String email = emailField.getText().trim();
        if (name.isEmpty() || email.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your name and email.", "Missing info", JOptionPane.WARNING_MESSAGE);
            return;
        }
        currentUser = new User(name, email);
        // Reset state
        currentIndex = 0;
        userAnswers.clear();
        paused = false;

        frame.setContentPane(buildQuizPanel());
        frame.revalidate();
        frame.repaint();

        startQuestionTimer();
        showQuestion(currentIndex);
    }

    // ---------------- Quiz UI ----------------
    private JPanel buildQuizPanel() {
        JPanel p = new JPanel(new BorderLayout(12,12));
        p.setBorder(new EmptyBorder(12,12,12,12));

        // Top: quiz header
        JPanel top = new JPanel(new BorderLayout());
        JLabel header = new JLabel("Quiz — Good luck, " + (currentUser != null ? currentUser.name : "Student"));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 18f));
        top.add(header, BorderLayout.WEST);

        lblQIndex = new JLabel();
        top.add(lblQIndex, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        // Center: question + choices
        JPanel center = new JPanel(new BorderLayout(8,8));
        lblQuestion = new JLabel("", SwingConstants.LEFT);
        lblQuestion.setFont(lblQuestion.getFont().deriveFont(16f));
        lblQuestion.setBorder(new EmptyBorder(6,6,6,6));
        center.add(lblQuestion, BorderLayout.NORTH);

        JPanel choicesPanel = new JPanel();
        choicesPanel.setLayout(new BoxLayout(choicesPanel, BoxLayout.Y_AXIS));
        choiceButtons = new JRadioButton[4];
        choiceGroup = new ButtonGroup();
        for (int i = 0; i < choiceButtons.length; i++) {
            final int idx = i;
            choiceButtons[i] = new JRadioButton();
            choiceButtons[i].setActionCommand(String.valueOf(i));
            choiceButtons[i].addActionListener(ev -> {
                userAnswers.put(currentIndex, idx);
            });
            choiceGroup.add(choiceButtons[i]);
            choicesPanel.add(choiceButtons[i]);
            choicesPanel.add(Box.createVerticalStrut(6));
        }
        center.add(choicesPanel, BorderLayout.CENTER);

        p.add(center, BorderLayout.CENTER);

        // South: controls and timer
        JPanel bottom = new JPanel(new BorderLayout(8,8));

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        btnPauseResume = new JButton("Pause");
        btnNext = new JButton("Next");
        leftControls.add(btnPauseResume);
        leftControls.add(btnNext);
        bottom.add(leftControls, BorderLayout.WEST);

        JPanel timerPanel = new JPanel(new BorderLayout(6,6));
        timerBar = new JProgressBar(0, secondsPerQuestion);
        timerBar.setStringPainted(true);
        timerBar.setPreferredSize(new Dimension(300, 24));
        lblTimer = new JLabel();
        timerPanel.add(timerBar, BorderLayout.CENTER);
        timerPanel.add(lblTimer, BorderLayout.EAST);
        bottom.add(timerPanel, BorderLayout.EAST);

        p.add(bottom, BorderLayout.SOUTH);

        btnPauseResume.addActionListener(e -> togglePauseResume());
        btnNext.addActionListener(e -> nextQuestion());

        // Keyboard shortcuts: Enter -> next
        frame.getRootPane().setDefaultButton(btnNext);

        return p;
    }

    private void showQuestion(int idx) {
        if (idx < 0 || idx >= questions.size()) return;
        Question q = questions.get(idx);
        lblQuestion.setText("<html><b>Q" + (idx + 1) + ".</b> " + q.text + "</html>");
        String[] opts = q.choices;
        for (int i = 0; i < choiceButtons.length; i++) {
            if (i < opts.length) {
                choiceButtons[i].setText(opts[i]);
                choiceButtons[i].setVisible(true);
            } else {
                choiceButtons[i].setVisible(false);
            }
            choiceButtons[i].setSelected(false);
        }
        // Restore previous answer if any
        if (userAnswers.containsKey(idx)) {
            int sel = userAnswers.get(idx);
            if (sel >= 0 && sel < choiceButtons.length) {
                choiceButtons[sel].setSelected(true);
            }
        } else {
            choiceGroup.clearSelection();
        }
        lblQIndex.setText("Question " + (idx + 1) + " / " + questions.size());

        // reset timer for question
        resetTimer();
    }

    // ---------------- Timer ----------------
    private void startQuestionTimer() {
        secondsLeft = secondsPerQuestion;
        updateTimerUI();
        if (questionTimer != null && questionTimer.isRunning()) questionTimer.stop();
        questionTimer = new javax.swing.Timer(1000, e -> {
            if (!paused) {
                secondsLeft--;
                updateTimerUI();
                if (secondsLeft <= 0) {
                    // time out -> auto next
                    Toolkit.getDefaultToolkit().beep();
                    nextQuestion();
                }
            }
        });
        questionTimer.setInitialDelay(1000);
        questionTimer.start();
    }

    private void resetTimer() {
        secondsLeft = secondsPerQuestion;
        paused = false;
        btnPauseResume.setText("Pause");
        if (questionTimer == null) startQuestionTimer();
        updateTimerUI();
    }

    private void updateTimerUI() {
        timerBar.setMaximum(secondsPerQuestion);
        timerBar.setValue(secondsLeft);
        timerBar.setString(secondsLeft + "s");
        lblTimer.setText("Time left: " + secondsLeft + "s");
    }

    private void togglePauseResume() {
        paused = !paused;
        btnPauseResume.setText(paused ? "Resume" : "Pause");
    }

    // ---------------- Navigation ----------------
    private void nextQuestion() {
        // Save current selection already handled by listener; ensure null handled
        // Move to next
        if (currentIndex < questions.size() - 1) {
            currentIndex++;
            showQuestion(currentIndex);
        } else {
            // End of quiz
            if (questionTimer != null) questionTimer.stop();
            showResults();
        }
    }

    // ---------------- Results ----------------
    private void showResults() {
        int correct = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Results for ").append(currentUser.name).append(" (").append(currentUser.email).append(")\n\n");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            Integer ans = userAnswers.get(i);
            int chosen = ans == null ? -1 : ans;
            boolean ok = (chosen == q.correctIndex);
            if (ok) correct++;
            sb.append(String.format("Q%d: %s\n", i + 1, ok ? "Correct" : "Incorrect"));
            sb.append("  Your answer: ").append(chosen >= 0 ? q.choices[chosen] : "<no answer>").append("\n");
            sb.append("  Correct answer: ").append(q.choices[q.correctIndex]).append("\n\n");
        }
        int score = Math.round((100f * correct) / questions.size());
        sb.append("Score: ").append(correct).append(" / ").append(questions.size()).append("  (").append(score).append("%)\n");

        // Save to CSV
        try {
            saveResultToCsv(currentUser, score, correct, questions.size(), userAnswers);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Failed to save result: " + ex.getMessage(), "IO Error", JOptionPane.ERROR_MESSAGE);
        }

        // Show results panel
        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false);
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(640, 360));

        int option = JOptionPane.showOptionDialog(frame, sp, "Quiz Results",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, new String[]{"Close", "View/Export Results (Admin)"}, "Close");

        if (option == 1) {
            showAdminDialog();
        } else {
            // After close, reset to login screen
            frame.setContentPane(buildLoginPanel());
            frame.revalidate();
            frame.repaint();
        }
    }

    private void saveResultToCsv(User user, int scorePercent, int correctCount, int totalQ, Map<Integer, Integer> answers) throws IOException {
        boolean writeHeader = !Files.exists(RESULTS_CSV);
        try (BufferedWriter bw = Files.newBufferedWriter(RESULTS_CSV, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (writeHeader) {
                bw.write("timestamp,name,email,score_percent,correct,total_questions,answers\n");
            }
            String ts = LocalDateTime.now().format(TS_FMT);
            // answers format: q1:idx|q2:idx|...
            StringBuilder ansSb = new StringBuilder();
            for (int i = 0; i < questions.size(); i++) {
                Integer a = answers.get(i);
                if (i > 0) ansSb.append("|");
                ansSb.append("q").append(i + 1).append(":").append(a == null ? "-" : a);
            }
            String line = String.format("%s,%s,%s,%d,%d,%d,%s\n",
                    escapeCsv(ts), escapeCsv(user.name), escapeCsv(user.email),
                    scorePercent, correctCount, totalQ, escapeCsv(ansSb.toString()));
            bw.write(line);
            bw.flush();
        }
    }

    // ---------------- Admin dialog ----------------
    private void showAdminDialog() {
        JDialog dlg = new JDialog(frame, "Admin - Saved Results", true);
        dlg.setSize(820, 520);
        dlg.setLocationRelativeTo(frame);

        JPanel root = new JPanel(new BorderLayout(8,8));
        root.setBorder(new EmptyBorder(8,8,8,8));
        DefaultTableModel tm = new DefaultTableModel(new Object[]{"Timestamp", "Name", "Email", "Score%", "Correct", "Total", "Answers"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tm);
        table.setAutoCreateRowSorter(true);
        root.add(new JScrollPane(table), BorderLayout.CENTER);

        // Load CSV into table
        if (Files.exists(RESULTS_CSV)) {
            try {
                List<String> lines = Files.readAllLines(RESULTS_CSV, StandardCharsets.UTF_8);
                boolean first = true;
                for (String ln : lines) {
                    if (first) { first = false; continue; } // header
                    String[] parts = splitCsvLine(ln);
                    if (parts.length >= 7) {
                        tm.addRow(new Object[]{parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]});
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Failed to load results: " + ex.getMessage(), "IO Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton btnExport = new JButton("Export CSV");
        JButton btnClear = new JButton("Clear All Results");
        JButton btnClose = new JButton("Close");
        bottom.add(btnClear);
        bottom.add(btnExport);
        bottom.add(btnClose);
        root.add(bottom, BorderLayout.SOUTH);

        btnExport.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("results_export.csv"));
            if (fc.showSaveDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.copy(RESULTS_CSV, fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                    JOptionPane.showMessageDialog(dlg, "Exported to: " + fc.getSelectedFile().getAbsolutePath());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(dlg, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        btnClear.addActionListener(e -> {
            int yn = JOptionPane.showConfirmDialog(dlg, "Delete ALL saved results? This cannot be undone.", "Confirm", JOptionPane.YES_NO_OPTION);
            if (yn == JOptionPane.YES_OPTION) {
                try {
                    Files.deleteIfExists(RESULTS_CSV);
                    tm.setRowCount(0);
                    JOptionPane.showMessageDialog(dlg, "Results cleared.");
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(dlg, "Failed to clear: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        btnClose.addActionListener(e -> dlg.dispose());

        dlg.setContentPane(root);
        dlg.setVisible(true);
    }

    // ---------------- Utilities ----------------
    private static String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // Basic CSV splitter that handles simple quoted fields
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // ---------------- Simple types ----------------
    private static class Question {
        final String text;
        final String[] choices;
        final int correctIndex;
        Question(String text, String[] choices, int correctIndex) {
            this.text = text;
            this.choices = choices;
            this.correctIndex = correctIndex;
        }
    }

    private static class User {
        final String name;
        final String email;
        User(String name, String email) { this.name = name; this.email = email; }
    }
}
