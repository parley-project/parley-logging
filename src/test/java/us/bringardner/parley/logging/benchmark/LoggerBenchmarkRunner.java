/**
 * Copyright 1998-2026 Tony Bringardner
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.logging.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;

/**
 * Runs the benchmarks (taking the same options as JMH's own Main) and then prints how the loggers
 * compare, in up to three tables:
 * <ol>
 * <li>{@link LoggerBenchmark} and {@link NoLevelSetBenchmark}, every entry written as soon as it is logged: for each benchmark the
 *     fastest logger is 1.00 and the others are multiples of its time (2.50 = takes two and a half
 *     times as long). Lower is better.</li>
 * <li>{@link BatchedLoggerBenchmark}, entries collected and written together: the same, among the
 *     batched loggers.</li>
 * <li>Batched against not batched: each logger's batched time divided by its own time writing every
 *     entry at once (0.25 = four times faster with batching).</li>
 * </ol>
 * The tables are also written to relative.txt in the build folder (target/benchmark), given by
 * the system property benchmark.dir.
 */
public class LoggerBenchmarkRunner {

	public static void main(String[] args) throws Exception {
		CommandLineOptions options = new CommandLineOptions(args);
		if( options.shouldHelp() || options.shouldList() || options.shouldListWithParams()
				|| options.shouldListProfilers() || options.shouldListResultFormats() ) {
			//  Not a run: let JMH answer
			org.openjdk.jmh.Main.main(args);
			return;
		}

		Collection<RunResult> results = new Runner(options).run();

		String tables = relativeTables(results);
		System.out.println();
		System.out.print(tables);

		String dir = System.getProperty("benchmark.dir");
		if( dir != null ) {
			Path file = Paths.get(dir, "relative.txt");
			writeFile(file, tables);
			System.out.println("Comparison saved to " + file);
		}
	}

	/** Scores (ns/op) of one benchmark class: benchmark method -&gt; logger -&gt; score, and the loggers in run order. */
	static final class Scores {
		final Map<String, Map<String, Double>> byMethod = new TreeMap<>();
		final List<String> loggers = new ArrayList<>();

		void add(String method, String logger, double score) {
			if( !loggers.contains(logger) ) {
				loggers.add(logger);
			}
			byMethod.computeIfAbsent(method, k -> new LinkedHashMap<>()).put(logger, score);
		}

		Double get(String method, String logger) {
			Map<String, Double> row = byMethod.get(method);
			return row == null ? null : row.get(logger);
		}
	}

	/**
	 * @return the tables described in the class comment, for the benchmark classes that were run
	 */
	static String relativeTables(Collection<RunResult> results) {
		Scores each = new Scores();
		Scores batched = new Scores();

		for (RunResult r : results) {
			String benchmark = r.getParams().getBenchmark();
			String method = benchmark.substring(benchmark.lastIndexOf('.') + 1);
			String className = benchmark.substring(0, benchmark.lastIndexOf('.'));
			boolean isBatched = className.substring(className.lastIndexOf('.') + 1).startsWith("Batched");
			Scores target = isBatched ? batched : each;
			target.add(method, r.getParams().getParam("loggerType"), r.getPrimaryResult().getScore());
		}

		StringBuilder out = new StringBuilder();
		if( !each.byMethod.isEmpty() ) {
			out.append(fastestTable("Every entry written as soon as it is logged", each));
		}
		if( !batched.byMethod.isEmpty() ) {
			if( out.length() > 0 ) {
				out.append('\n');
			}
			out.append(fastestTable("Entries collected and written together (batched)", batched));
		}
		if( !each.byMethod.isEmpty() && !batched.byMethod.isEmpty() ) {
			out.append('\n').append(batchedTable(each, batched));
		}
		return out.toString();
	}

	/** Each logger's time divided by the fastest logger's time, for each benchmark. */
	private static String fastestTable(String title, Scores scores) {
		Map<String, Map<String, String>> cells = new TreeMap<>();
		for (Map.Entry<String, Map<String, Double>> row : scores.byMethod.entrySet()) {
			double fastest = Double.MAX_VALUE;
			for (double score : row.getValue().values()) {
				fastest = Math.min(fastest, score);
			}
			Map<String, String> line = new LinkedHashMap<>();
			for (Map.Entry<String, Double> e : row.getValue().entrySet()) {
				line.put(e.getKey(), format(e.getValue() / fastest));
			}
			cells.put(row.getKey(), line);
		}
		return table(title + ": relative time (1.00 = the fastest logger for that benchmark; lower is better)",
				cells, scores.loggers);
	}

	/** Each logger's batched time divided by its time writing every entry at once. */
	private static String batchedTable(Scores each, Scores batched) {
		Map<String, Map<String, String>> cells = new TreeMap<>();
		for (Map.Entry<String, Map<String, Double>> row : batched.byMethod.entrySet()) {
			Map<String, String> line = new LinkedHashMap<>();
			for (Map.Entry<String, Double> e : row.getValue().entrySet()) {
				Double unbatched = each.get(row.getKey(), e.getKey());
				if( unbatched != null ) {
					line.put(e.getKey(), format(e.getValue() / unbatched));
				}
			}
			if( !line.isEmpty() ) {
				cells.put(row.getKey(), line);
			}
		}
		if( cells.isEmpty() ) {
			return "";
		}
		return table("Batched against every entry written at once: batched time / unbatched time "
				+ "(0.25 = four times faster batched; lower is better)", cells, batched.loggers);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	/** A title, then a row for each benchmark and a column for each logger ("-" where there is no value). */
	private static String table(String title, Map<String, Map<String, String>> cells, List<String> loggers) {
		int nameWidth = "Benchmark".length();
		for (String b : cells.keySet()) {
			nameWidth = Math.max(nameWidth, b.length());
		}
		int colWidth = 8;
		for (String l : loggers) {
			colWidth = Math.max(colWidth, l.length());
		}

		StringBuilder out = new StringBuilder();
		out.append(title).append("\n\n");
		out.append(pad("Benchmark", nameWidth, false));
		for (String l : loggers) {
			out.append("  ").append(pad(l, colWidth, true));
		}
		out.append('\n');

		for (Map.Entry<String, Map<String, String>> row : cells.entrySet()) {
			out.append(pad(row.getKey(), nameWidth, false));
			for (String l : loggers) {
				String cell = row.getValue().getOrDefault(l, "-");
				out.append("  ").append(pad(cell, colWidth, true));
			}
			out.append('\n');
		}
		return out.toString();
	}

	private static String pad(String s, int width, boolean right) {
		StringBuilder b = new StringBuilder();
		if( !right ) {
			b.append(s);
		}
		for (int i = s.length(); i < width; i++) {
			b.append(' ');
		}
		if( right ) {
			b.append(s);
		}
		return b.toString();
	}

	private static void writeFile(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		try (PrintStream ps = new PrintStream(Files.newOutputStream(file), true, StandardCharsets.UTF_8.name())) {
			ps.print(text);
		}
	}
}
