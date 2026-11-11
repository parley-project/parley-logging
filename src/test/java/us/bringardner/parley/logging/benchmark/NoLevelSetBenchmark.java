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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * {@link LoggerBenchmark}'s level check and disabled debug calls for a Parley object when no
 * parley.log.level is set: BaseObject then passes every message on, and parley-logging's logger
 * holds DEBUG and INFO back, looking at the parley.log.level settings for each one. This is what a
 * debug call costs in an application that sets no level (fsh, for one).
 * <p>
 * The benchmark methods have LoggerBenchmark's names, so {@link LoggerBenchmarkRunner} puts the
 * results in the same table, as one more logger.
 */
//  parley-logging's ParleyLoggerFinder is this JVM's System.LoggerFinder (see LoggerFixture)
@Fork(value = 1, jvmArgsAppend = LoggerFixture.FINDER_PARLEY)
@State(Scope.Benchmark)
public class NoLevelSetBenchmark extends AbstractBenchmark {

	@Param({LoggerFixture.BASE_OBJECT_DEFAULT})
	public String loggerType;

	@Override
	protected String loggerType() {
		return loggerType;
	}

	@Override
	protected boolean batched() {
		return false;
	}

	@Benchmark
	public boolean isDebugEnabled() {
		return logger.isDebugEnabled();
	}

	@Benchmark
	public void debugDisabled() {
		logger.debug("A debug message that is never written");
	}

	@Benchmark
	public void debugDisabledConcat(Counter c) {
		logger.debug("value=" + c.n++);
	}

	@Benchmark
	public void debugDisabledSupplier(Counter c) {
		int n = c.n++;
		logger.debug(() -> "value=" + n);
	}
}
