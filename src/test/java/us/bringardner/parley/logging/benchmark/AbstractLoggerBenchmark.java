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

/**
 * Every benchmark, with each entry written to the file as soon as it is logged: the file-writing ones
 * from {@link AbstractFileLoggingBenchmark} plus the level check and debug calls when debug is off.
 */
public abstract class AbstractLoggerBenchmark extends AbstractFileLoggingBenchmark {

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
