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
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What every benchmark class shares: how long JMH measures, and the logger, which
 * {@link LoggerFixture} sets up before a benchmark and removes after it. A subclass says which
 * logger type to use and whether entries are batched.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public abstract class AbstractBenchmark {

	LoggerFixture fixture;
	BenchLogger logger;
	Exception error;

	/** A counter per thread, so the messages differ without the threads sharing a variable. */
	@State(Scope.Thread)
	public static class Counter {
		int n;
	}

	/** @return the logger type (the loggerType parameter) */
	protected abstract String loggerType();

	/** @return true to collect entries and write them together, false to write each one as it is logged */
	protected abstract boolean batched();

	@Setup(Level.Trial)
	public void setup() throws IOException {
		fixture = new LoggerFixture(loggerType(), batched());
		logger = fixture.logger;
		error = new IOException("Benchmark exception (not a real error)");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		if( fixture != null ) {
			fixture.close();
		}
	}
}
