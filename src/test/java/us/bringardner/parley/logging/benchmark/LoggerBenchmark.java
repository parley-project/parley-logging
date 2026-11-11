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

import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Compares what Parley logging costs: a ParleyLogger called directly, a Parley object logging
 * through BaseObject and the JDK's System.Logger to a ParleyLogger (what Parley code does in an
 * application that includes parley-logging), and java.util.logging called directly (what an
 * application with no System.Logger provider writes with). {@link LoggerFixture} describes each.
 * <p>
 * It is a JMH benchmark, not a unit test: surefire doesn't run it. Run it with
 * <pre>
 *   mvn -Pbenchmark test
 * </pre>
 * (it builds in target/benchmark, apart from Eclipse's and the normal build's classes)
 * and pass JMH options with -Djmh.args, for example a quick run of only the disabled-debug cases:
 * <pre>
 *   mvn -Pbenchmark test -Djmh.args="LoggerBenchmark.debug -wi 2 -i 3 -w 1s -r 1s"
 * </pre>
 * The results are printed at the end and written to target/benchmark/logger-benchmark.json
 * (which <a href="https://jmh.morethan.io">jmh.morethan.io</a> can chart).
 * After JMH's own table, {@link LoggerBenchmarkRunner} prints how the loggers compare (the fastest
 * for each benchmark is 1.00, the others multiples of its time), also saved to target/benchmark/relative.txt.
 * <p>
 * Each benchmark of each logger runs in its own JVM (fork), with every entry written to the file as
 * soon as it is logged (each logger's default). {@link BatchedLoggerBenchmark} measures the
 * file-writing cases again with entries collected and written together, and
 * {@link NoLevelSetBenchmark} the calls a logger holds back when no parley.log.level is set.
 * {@link ViaLog4jBenchmark}, {@link ViaLogbackBenchmark} and {@link ViaJulBenchmark} (and their
 * Batched classes) measure a Parley object with Log4j, Logback or java.util.logging behind
 * System.Logger; each is a class of its own because its JVMs need their own System.LoggerFinder.
 * <p>
 * What is measured (average time per call):
 * <ol>
 * <li>isDebugEnabled: the level check alone.</li>
 * <li>debugDisabled: a debug call with a constant message, when debug is off.</li>
 * <li>debugDisabledConcat: debug("value="+n) when debug is off; the caller pays for building the message.</li>
 * <li>debugDisabledSupplier: debug(() -&gt; "value="+n) when debug is off; the message is never built.</li>
 * <li>infoEnabled: info("value="+n), written to the file.</li>
 * <li>infoEnabled4Threads: the same from four threads at once, sharing one logger and one file.</li>
 * <li>warnWithThrowable: warn(msg, exception), written to the file with its stack trace.</li>
 * </ol>
 */
//  parley-logging's ParleyLoggerFinder is this JVM's System.LoggerFinder (see LoggerFixture)
@Fork(value = 1, jvmArgsAppend = LoggerFixture.FINDER_PARLEY)
@State(Scope.Benchmark)
public class LoggerBenchmark extends AbstractLoggerBenchmark {

	@Param({LoggerFixture.PARLEY, LoggerFixture.BASE_OBJECT, LoggerFixture.JUL, LoggerFixture.LOG4J, LoggerFixture.LOGBACK})
	public String loggerType;

	@Override
	protected String loggerType() {
		return loggerType;
	}
}
