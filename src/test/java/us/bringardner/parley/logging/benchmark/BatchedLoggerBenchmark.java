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
 * The file-writing cases of {@link LoggerBenchmark} again, with each logger collecting entries and
 * writing them together (batched) instead of writing every entry as soon as it is logged. How each
 * logger does that is described in {@link LoggerFixture}. The level checks and disabled debug calls
 * don't write anything, so they aren't repeated here.
 * <p>
 * It runs with LoggerBenchmark (mvn -Pbenchmark test), and {@link LoggerBenchmarkRunner} prints how
 * much faster batching is for each logger.
 */
@Fork(1)
@State(Scope.Benchmark)
public class BatchedLoggerBenchmark extends AbstractFileLoggingBenchmark {

	@Param({LoggerFixture.PARLEY, LoggerFixture.BASE_OBJECT, LoggerFixture.JUL, LoggerFixture.LOG4J, LoggerFixture.LOGBACK})
	public String loggerType;

	@Override
	protected String loggerType() {
		return loggerType;
	}

	@Override
	protected boolean batched() {
		return true;
	}
}
