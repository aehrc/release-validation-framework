import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * What each pass of RVF's structural testing costs on a real RF2 file, and what
 * a single fused pass would cost instead.
 *
 * Mirrors RF2FileStructureTester.runTestForFile (three passes) and
 * ColumnPatternTester's per-line work (a fourth), then does the same checks in
 * one pass.
 */
public class StructBench {
	static final Pattern SCTID = Pattern.compile("^\\d{6,18}$");
	static final Pattern DATE = Pattern.compile("^\\d{8}$");
	static final Pattern BOOLEAN = Pattern.compile("[0-1]");
	static final Pattern NOT_BLANK = Pattern.compile("^(?=\\s*\\S).*$");
	static final String CRLF = "\r\n";

	static BufferedReader open(Path p) throws IOException {
		return new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8), 1 << 16);
	}

	public static void main(String[] args) throws Exception {
		Path file = Path.of(args[0]);
		System.out.printf("file: %s (%.1f MB)%n", file.getFileName(),
				Files.size(file) / 1048576.0);

		// ---- pass 1: count lines (RF2FileStructureTester:73)
		long t = System.nanoTime();
		int totalLine = 0;
		try (BufferedReader r = open(file)) { while (r.readLine() != null) totalLine++; }
		long p1 = System.nanoTime() - t;
		System.out.printf("  pass 1  count lines            %6.2fs   (%d lines)%n", p1 / 1e9, totalLine);

		// ---- pass 2: Scanner over CRLF (RF2FileStructureTester:79)
		t = System.nanoTime();
		int scanned = 0;
		try (Scanner s = new Scanner(open(file))) {
			s.useDelimiter(CRLF);
			while (s.hasNext()) { s.next(); scanned++; }
		}
		long p2 = System.nanoTime() - t;
		System.out.printf("  pass 2  Scanner CRLF count     %6.2fs   (%d)%n", p2 / 1e9, scanned);

		// ---- pass 3: re-read to inspect the LAST line (RF2FileStructureTester:91)
		t = System.nanoTime();
		try (BufferedReader r = open(file)) {
			for (int i = 1; i <= totalLine; i++) {
				r.readLine();
				if (i == (totalLine - 1)) {
					int read; StringBuilder b = new StringBuilder();
					while ((read = r.read()) != -1) {
						char c = (char) read;
						if (c == '\r' || c == '\n') b.append(c);
					}
				}
			}
		}
		long p3 = System.nanoTime() - t;
		System.out.printf("  pass 3  re-read for last line  %6.2fs%n", p3 / 1e9);

		// ---- pass 4: column checks (ColumnPatternTester)
		t = System.nanoTime();
		long fields = 0;
		try (BufferedReader r = open(file)) {
			String line = r.readLine();               // header
			while ((line = r.readLine()) != null) {
				String[] cols = line.split("\t", -1);
				for (int i = 0; i < cols.length; i++) {
					fields++;
					switch (i) {
						case 0 -> SCTID.matcher(cols[i]).matches();
						case 1 -> DATE.matcher(cols[i]).matches();
						case 2 -> BOOLEAN.matcher(cols[i]).matches();
						default -> NOT_BLANK.matcher(cols[i]).matches();
					}
				}
			}
		}
		long p4 = System.nanoTime() - t;
		System.out.printf("  pass 4  split + regex columns  %6.2fs   (%d fields)%n", p4 / 1e9, fields);

		System.out.printf("  ----------------------------------------%n");
		System.out.printf("  TOTAL as RVF does it           %6.2fs%n", (p1 + p2 + p3 + p4) / 1e9);

		// ---- fused: everything in ONE pass, char checks instead of regex
		t = System.nanoTime();
		int lines = 0; long f2 = 0;
		try (BufferedReader r = open(file)) {
			String line = r.readLine(); lines++;
			while ((line = r.readLine()) != null) {
				lines++;
				int start = 0, col = 0;
				for (int i = 0; i <= line.length(); i++) {
					if (i == line.length() || line.charAt(i) == '\t') {
						f2++;
						check(line, start, i, col);
						start = i + 1; col++;
					}
				}
			}
		}
		long fused = System.nanoTime() - t;
		System.out.printf("  FUSED one pass, char checks    %6.2fs   (%d lines, %d fields)%n",
				fused / 1e9, lines, f2);
		System.out.printf("  speedup                        %6.2fx%n", (p1 + p2 + p3 + p4) / (double) fused);
	}

	/** The same three column checks, without regex or substring allocation. */
	private static boolean check(String s, int from, int to, int col) {
		int len = to - from;
		return switch (col) {
			case 0 -> len >= 6 && len <= 18 && allDigits(s, from, to);
			case 1 -> len == 8 && allDigits(s, from, to);
			case 2 -> len == 1 && (s.charAt(from) == '0' || s.charAt(from) == '1');
			default -> notBlank(s, from, to);
		};
	}

	private static boolean allDigits(String s, int from, int to) {
		for (int i = from; i < to; i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') return false;
		}
		return true;
	}

	private static boolean notBlank(String s, int from, int to) {
		for (int i = from; i < to; i++) if (!Character.isWhitespace(s.charAt(i))) return true;
		return false;
	}
}
