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
 * {@link BatchedLoggerBenchmark}'s benchmarks for a Parley object logging through BaseObject and the JDK's
 * System.Logger with Log4j 2 (its log4j-jpl bridge) behind it. It is a class of its own because its JVMs
 * need their own System.LoggerFinder (see {@link LoggerFixture}).
 */
@Fork(value = 1, jvmArgsAppend = LoggerFixture.FINDER_LOG4J)
@State(Scope.Benchmark)
public class BatchedViaLog4jBenchmark extends AbstractFileLoggingBenchmark {

	@Param({LoggerFixture.BASE_OBJECT_LOG4J})
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
